package moe.shizuku.manager.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 从背景图里挑一个适合当主题色「种子」的颜色。
 *
 * 为什么不直接用 Material 的 `setContentBasedSource(Bitmap)`：那条路是平台在内部做量化，
 * 拿不到结果，设置界面就没法显示"提取到了什么颜色"、也没法让用户接着微调。这里自己算，
 * 于是 **手输色号** 与 **从背景提取** 走的是同一条路（都产出一个种子色 int）。
 *
 * 顺带一个好处：不依赖 android.graphics，纯整数/浮点运算，可以在 JVM 上直接做数值验证
 * （见 tools/test-palette.sh）。
 */
object PaletteExtractor {

    /** 色相分桶数量。桶太少会挑出偏灰的颜色，太多又容易挑到零星的噪点。 */
    private const val HUE_BUCKETS = 24

    /** 太暗或太亮的像素不适合当种子：前者提不出可用配色，后者几乎等于白色。 */
    private const val MIN_VALUE = 0.12f
    private const val MAX_VALUE = 0.97f

    /** 低于这个饱和度的像素接近灰阶，对"主题色"没有信息量。 */
    private const val MIN_SATURATION = 0.08f

    /**
     * @return 提取到的 ARGB（不透明）；图里实在没有可用的颜色时返回 null。
     */
    fun extract(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null

        val weight = DoubleArray(HUE_BUCKETS)
        val sumR = DoubleArray(HUE_BUCKETS)
        val sumG = DoubleArray(HUE_BUCKETS)
        val sumB = DoubleArray(HUE_BUCKETS)
        var weighted = 0.0
        var allR = 0.0
        var allG = 0.0
        var allB = 0.0
        var counted = 0

        val hsv = FloatArray(3)
        for (argb in pixels) {
            val alpha = argb ushr 24 and 0xFF
            if (alpha < 128) continue

            val r = argb ushr 16 and 0xFF
            val g = argb ushr 8 and 0xFF
            val b = argb and 0xFF
            allR += r
            allG += g
            allB += b
            counted++

            rgbToHsv(r, g, b, hsv)
            val h = hsv[0]
            val s = hsv[1]
            val v = hsv[2]

            if (v < MIN_VALUE || v > MAX_VALUE || s < MIN_SATURATION) continue

            val bucket = min((h / 360f * HUE_BUCKETS).toInt(), HUE_BUCKETS - 1)
            // 偏好鲜艳的颜色：饱和度平方加权，避免选到一大片"接近灰"的背景
            val w = s * s
            weight[bucket] += w
            sumR[bucket] += r * w
            sumG[bucket] += g * w
            sumB[bucket] += b * w
            weighted += w
        }

        if (counted == 0) return null

        // 挑权重最大的桶
        var best = -1
        var bestWeight = 0.0
        for (i in 0 until HUE_BUCKETS) {
            if (weight[i] > bestWeight) {
                bestWeight = weight[i]
                best = i
            }
        }

        if (best < 0 || bestWeight <= 0.0 || weighted <= 0.0) {
            // 整张图几乎没有彩色信息（黑白照片之类），退回整图的平均色
            return argbOf(allR / counted, allG / counted, allB / counted)
        }

        return argbOf(
            sumR[best] / bestWeight,
            sumG[best] / bestWeight,
            sumB[best] / bestWeight
        )
    }

    /**
     * 把种子色调整到更适合生成配色的范围：
     * 太灰的拉一点饱和度，太暗/太亮的压到中间亮度。
     *
     * Material 对低饱和种子会生成很闷的配色，这一步能让"从背景提取"的结果更可用。
     */
    fun normalizeSeed(argb: Int): Int {
        val hsv = FloatArray(3)
        rgbToHsv(argb ushr 16 and 0xFF, argb ushr 8 and 0xFF, argb and 0xFF, hsv)

        val s = max(hsv[1], 0.28f)
        val v = hsv[2].coerceIn(0.35f, 0.90f)

        return hsvToArgb(hsv[0], s, v)
    }

    private fun argbOf(r: Double, g: Double, b: Double): Int =
        (0xFF shl 24) or
                (r.toInt().coerceIn(0, 255) shl 16) or
                (g.toInt().coerceIn(0, 255) shl 8) or
                b.toInt().coerceIn(0, 255)

    /** 不依赖 android.graphics.Color，方便在 JVM 上验证。 */
    fun rgbToHsv(r: Int, g: Int, b: Int, out: FloatArray) {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val maxC = max(rf, max(gf, bf))
        val minC = min(rf, min(gf, bf))
        val delta = maxC - minC

        val h = when {
            delta < 1e-6f -> 0f
            abs(maxC - rf) < 1e-6f -> 60f * (((gf - bf) / delta) % 6f)
            abs(maxC - gf) < 1e-6f -> 60f * (((bf - rf) / delta) + 2f)
            else -> 60f * (((rf - gf) / delta) + 4f)
        }

        out[0] = if (h < 0f) h + 360f else h
        out[1] = if (maxC <= 0f) 0f else delta / maxC
        out[2] = maxC
    }

    private fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return (0xFF shl 24) or
                (((r1 + m) * 255f).toInt().coerceIn(0, 255) shl 16) or
                (((g1 + m) * 255f).toInt().coerceIn(0, 255) shl 8) or
                ((b1 + m) * 255f).toInt().coerceIn(0, 255)
    }
}
