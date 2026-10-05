package moe.shizuku.manager.app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.util.Log
import android.view.Gravity
import moe.shizuku.manager.ShizukuSettings
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Support for a user picked custom window background.
 *
 * The picked image is copied into the app private storage, so
 * * no storage permission is required (the image is picked with the system document picker),
 * * the background keeps working after the original file is deleted or the picker grant expires.
 *
 * "No custom background" is represented by the absence of the backing file, so no preference
 * has to be kept in sync with it.
 */
object BackgroundHelper {

    private const val TAG = "BackgroundHelper"

    /** Directory inside [Context.getFilesDir] that holds the custom background. */
    private const val DIR_NAME = "background"

    /** File inside [DIR_NAME] that holds the custom background. */
    private const val FILE_NAME = "background.img"

    /** Decoded bitmaps are never larger than this on their longest side. */
    private const val MAX_BITMAP_DIMEN = 2560

    /** Size, in dp, of the preview icon shown in the settings list. */
    private const val THUMBNAIL_SIZE_DP = 48

    /**
     * 背景亮度（0~100，100 = 原图）。低于 100 时在图上盖一层黑色。
     *
     * 以前这里盖的是主题背景色（浅色主题盖白、深色主题盖黑），于是同一个数值在深浅色下
     * 观感完全不同。改成固定盖黑之后，两边的结果一致，也更符合"亮度"这个说法。
     *
     * 必须与 res/xml/settings.xml 中该条目的 android:key 一致（由 tools/check-edits.sh 检查）。
     */
    const val KEY_BACKGROUND_BRIGHTNESS = "background_brightness"

    /** 纯色背景的颜色，0 = 未设置。 */
    const val KEY_BACKGROUND_COLOR = "background_color"

    /** Used when the preference was never set (matches settings.xml). */
    private const val DEFAULT_BRIGHTNESS = 70

    /**
     * 模糊强度（0~100）。0 = 不模糊，数值越大越糊。
     *
     * 强度不是直接当模糊半径用：真正的柔化程度由「中间图缩到多小」决定，
     * 半径只固定取一个小值负责抹平重采样的块状感。这样滑块才是真正连续的，
     * 否则半径只能取整数，100 档里其实只有 20 来种效果。
     *
     * 同样必须与 res/xml/settings.xml 保持一致。
     */
    const val KEY_BACKGROUND_BLUR = "background_blur_intensity"

    /** Used when the preference was never set (matches settings.xml). */
    private const val DEFAULT_BLUR_INTENSITY = 0

    /**
     * 模糊前把图缩到的最长边范围。
     *
     * 强度 0 时不缩（不模糊）；强度越大缩得越小，再让 BitmapDrawable 放大回窗口尺寸。
     * 柔化程度主要由这个缩放比决定 —— 缩小再放大本身就是一次双线性低通滤波 —— 
     * 于是滑块是连续可调的，而不是只有几个整数半径可选。
     */
    private const val BLUR_MAX_TARGET_LONG_SIDE = 480
    private const val BLUR_MIN_TARGET_LONG_SIDE = 96

    /** 盒式模糊跑三遍，按中心极限定理已经足够接近高斯分布。 */
    private const val BLUR_PASSES = 3

    /**
     * 通道平面的定点小数位数，见 [blurPixels]。
     *
     * 取 16 是因为每遍除法都截断一次，位数太小会把模糊后已经很暗的尾部
     * （单像素冲激铺开后每个像素不足一个色阶）整片抹成 0。数值上界：
     * 255<<16 再乘窗口 21 约 3.5e8，离 Int 上限还有很大余量。
     */
    private const val FIXED_SHIFT = 16

    /**
     * 固定的小半径，只负责抹平缩放带来的块状感，柔化程度交给缩放比。
     *
     * 取 1 而不是 2：这个半径在低强度时会被放大倍率放大，是滑块"第一格就明显变糊"的
     * 来源。取 1 已经把阶梯感抹掉，同时把低端的突变减半。
     */
    private const val BLUR_RADIUS = 1

    private var cachedBitmap: Bitmap? = null
    private var cachedKey: String? = null

    // ------------------------------------------------------------- appearance

    /**
     * 背景亮度，0~100，100 = 原图。
     *
     * 盖的是固定黑色，所以浅色/深色主题下同一个数值的表现一致。
     */
    fun getBrightness(context: Context): Int {
        val preferences = ShizukuSettings.getPreferences() ?: return DEFAULT_BRIGHTNESS
        return preferences.getInt(KEY_BACKGROUND_BRIGHTNESS, DEFAULT_BRIGHTNESS).coerceIn(0, 100)
    }

    /**
     * 模糊强度，0~100。
     *
     * 见 [blurTargetLongSide]：强度决定中间图的尺寸，而不是直接当半径。
     */
    fun getBlurIntensity(context: Context): Int {
        val preferences = ShizukuSettings.getPreferences() ?: return DEFAULT_BLUR_INTENSITY
        return preferences.getInt(KEY_BACKGROUND_BLUR, DEFAULT_BLUR_INTENSITY).coerceIn(0, 100)
    }

    /**
     * 模糊前把图缩到多小（最长边像素）。强度 0 返回 0 表示"不模糊"。
     *
     * 纯函数，方便在 JVM 上验证（见 tools/test-blur.sh）。
     */
    fun blurTargetLongSide(intensity: Int): Int {
        val value = intensity.coerceIn(0, 100)
        if (value == 0) return 0
        val span = BLUR_MAX_TARGET_LONG_SIDE - BLUR_MIN_TARGET_LONG_SIDE
        return BLUR_MAX_TARGET_LONG_SIDE - span * value / 100
    }

    // ------------------------------------------------------------------ files

    private fun backgroundFile(context: Context): File =
        File(File(context.filesDir, DIR_NAME), FILE_NAME)

    /** 是否设置过背景图。 */
    fun hasCustomImage(context: Context): Boolean {
        val file = backgroundFile(context)
        return file.isFile && file.length() > 0L
    }

    /**
     * 是否有自定义背景（背景图或纯色，两者只会存在一个）。
     *
     * 窗口背景、顶栏透明等判断都走这里。
     */
    fun hasCustomBackground(context: Context): Boolean =
        hasCustomImage(context) || hasCustomColor(context)

    // ------------------------------------------------------------- solid color

    /**
     * 纯色背景。0 表示未设置（0 是全透明，不会作为用户选择被存进来）。
     *
     * 和背景图是互斥的：设了纯色就清掉图片，反之亦然 —— 两套设置同时生效只会让人困惑。
     */
    fun getCustomColor(context: Context): Int {
        val preferences = ShizukuSettings.getPreferences() ?: return 0
        return preferences.getInt(KEY_BACKGROUND_COLOR, 0)
    }

    fun hasCustomColor(context: Context): Boolean = getCustomColor(context) != 0

    fun setCustomColor(context: Context, color: Int) {
        ShizukuSettings.getPreferences()?.edit()?.putInt(KEY_BACKGROUND_COLOR, color)?.apply()
    }

    fun clearCustomColor(context: Context) {
        ShizukuSettings.getPreferences()?.edit()?.remove(KEY_BACKGROUND_COLOR)?.apply()
    }

    /**
     * Copies the image behind [uri] into the app private storage.
     *
     * @return true if the image was stored and could be decoded, false otherwise.
     */
    fun saveBackground(context: Context, uri: Uri): Boolean {
        val target = backgroundFile(context)
        val temp = File(target.parentFile, "$FILE_NAME.tmp")
        return try {
            target.parentFile?.mkdirs()

            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                Log.w(TAG, "openInputStream returned null for $uri")
                return false
            }
            input.use { source ->
                FileOutputStream(temp).use { destination -> source.copyTo(destination) }
            }

            if (!isDecodable(temp)) {
                Log.w(TAG, "picked image can not be decoded")
                temp.delete()
                return false
            }

            target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            invalidateCache()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "saveBackground", t)
            temp.delete()
            false
        }
    }

    fun clearBackground(context: Context) {
        backgroundFile(context).delete()
        invalidateCache()
    }

    private fun isDecodable(file: File): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    @Synchronized
    private fun invalidateCache() {
        // The bitmaps are intentionally not recycled here: they may still be used by a window
        // that is currently visible. Just drop the reference and let the GC handle them.
        cachedBitmap = null
        cachedKey = null
    }

    // --------------------------------------------------------------- drawable

    /**
     * The window background to use, or null when no custom background is set.
     */
    fun createBackgroundDrawable(context: Context): Drawable? {
        // 纯色优先：设了纯色就不再看图片（两者互斥，界面上也只会存在一个）
        val color = getCustomColor(context)
        if (color != 0) return ColorDrawable(color)

        val bitmap = loadBitmap(context) ?: return null

        val image = BitmapDrawable(context.resources, bitmap).apply {
            setGravity(Gravity.FILL)
        }

        // 亮度：固定盖一层黑色，深浅色主题下表现一致（以前盖的是主题背景色，
        // 于是同一个数值在浅色下像"洗白"、深色下像"压暗"，两边结果不同）。
        val overlay = brightnessOverlayColor(context)
        if (overlay == 0) return image

        return LayerDrawable(arrayOf<Drawable>(image, ColorDrawable(overlay)))
    }

    /** Applies the custom background to the window of [activity]. No-op when unset. */
    fun applyToWindow(activity: Activity) {
        val drawable = createBackgroundDrawable(activity) ?: return
        activity.window?.setBackgroundDrawable(drawable)
    }

    /**
     * 把窗口背景恢复成主题里定义的那个，用于移除背景图之后。
     *
     * 不能简单地 setBackgroundDrawable(null)：那样窗口底下就没有东西了。
     */
    fun restoreWindowBackground(activity: Activity) {
        val window = activity.window ?: return
        val attributes = activity.theme.obtainStyledAttributes(
            intArrayOf(android.R.attr.windowBackground)
        )
        try {
            window.setBackgroundDrawable(attributes.getDrawable(0))
        } finally {
            attributes.recycle()
        }
    }

    /**
     * Small square preview of the current background, or null when unset.
     *
     * 这里刻意不盖遮罩：图标在列表里只有 24dp 左右，盖上 60%~80% 的遮罩就成了一块
     * 纯色方块，反而认不出是哪张图。真正实时的预览就是设置界面本身——改完可见度会
     * 立刻重建界面，整屏都能看到效果。
     */
    fun createThumbnail(context: Context): Drawable? {
        val file = backgroundFile(context)
        if (!file.isFile) return null

        return try {
            val size = (THUMBNAIL_SIZE_DP * context.resources.displayMetrics.density)
                .toInt().coerceAtLeast(1)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, size, size)
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null

            val side = min(decoded.width, decoded.height)
            val square = Bitmap.createBitmap(
                decoded,
                (decoded.width - side) / 2,
                (decoded.height - side) / 2,
                side,
                side
            )
            val scaled = if (side == size) square
            else Bitmap.createScaledBitmap(square, size, size, true)

            BitmapDrawable(context.resources, scaled)
        } catch (t: Throwable) {
            Log.w(TAG, "createThumbnail", t)
            null
        }
    }

    // ---------------------------------------------------------------- bitmaps

    @Synchronized
    private fun loadBitmap(context: Context): Bitmap? {
        val file = backgroundFile(context)
        if (!file.isFile || file.length() <= 0L) return null

        val metrics = context.resources.displayMetrics
        val requestedWidth = metrics.widthPixels.coerceAtLeast(1)
        val requestedHeight = metrics.heightPixels.coerceAtLeast(1)
        val blurIntensity = getBlurIntensity(context)
        val blurTarget = blurTargetLongSide(blurIntensity)
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}" +
                "|${requestedWidth}x$requestedHeight|blur=$blurTarget"

        val cached = cachedBitmap
        if (cached != null && !cached.isRecycled && cachedKey == key) return cached

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = if (blurTarget > 0) {
                    // 要走模糊，就解出与强度对应的小图（强度越大缩得越小、放大后越糊）
                    calculateInSampleSizeForLongSide(
                        bounds.outWidth, bounds.outHeight, blurTarget
                    )
                } else {
                    calculateInSampleSize(
                        bounds.outWidth, bounds.outHeight, requestedWidth, requestedHeight
                    )
                }
            }
            // Never decode (and keep in memory) more than MAX_BITMAP_DIMEN, whatever the screen
            // and the source image sizes are.
            var sampleSize = options.inSampleSize
            while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_BITMAP_DIMEN) {
                sampleSize *= 2
            }
            options.inSampleSize = sampleSize

            val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null

            var bitmap = decoded
            if (blurTarget > 0) {
                // inSampleSize 只能是 2 的幂。如果直接拿解码结果当"中间图"，尺寸就只有
                // 500 / 250 / 125 这么几档，滑块会变成几级跳变（比原来的四档菜单还糟）。
                // 所以粗采样之后再精确缩到目标长边，强度才是连续变化的。
                val exact = scaleToLongSide(decoded, blurTarget)
                if (exact !== decoded) decoded.recycle()
                bitmap = exact
            } else if (max(decoded.width, decoded.height) > MAX_BITMAP_DIMEN) {
                val scaled = scaleDown(decoded, MAX_BITMAP_DIMEN)
                if (scaled !== decoded) decoded.recycle()
                bitmap = scaled
            }

            // Crop to the screen ratio so that the image can be stretched to the window
            // without being distorted.
            val cropped = cropToAspect(bitmap, requestedWidth.toFloat() / requestedHeight)
            if (cropped !== bitmap) bitmap.recycle()

            // 模糊放在裁剪之后：先去掉画不出来的部分，再对剩下的像素做运算
            var result = cropped
            if (blurTarget > 0) {
                val blurred = boxBlur(cropped, BLUR_RADIUS)
                if (blurred != null && blurred !== cropped) {
                    cropped.recycle()
                    result = blurred
                }
            }

            cachedKey = key
            cachedBitmap = result
            result
        } catch (t: Throwable) {
            Log.w(TAG, "loadBitmap", t)
            null
        }
    }

    /** 精确缩放到指定长边。inSampleSize 只能按 2 的幂缩，做不到这一点。 */
    private fun scaleToLongSide(bitmap: Bitmap, target: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= 0 || target <= 0 || longest <= target) return bitmap

        val ratio = target.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun scaleDown(bitmap: Bitmap, maxDimen: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDimen) return bitmap

        val ratio = maxDimen.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun cropToAspect(bitmap: Bitmap, aspect: Float): Bitmap {
        if (bitmap.width <= 0 || bitmap.height <= 0 || aspect <= 0f) return bitmap

        val width = bitmap.width
        val height = bitmap.height
        val current = width.toFloat() / height
        if (abs(current - aspect) < 0.01f) return bitmap

        return if (current > aspect) {
            // Too wide, cut the sides.
            val newWidth = (height * aspect).toInt().coerceIn(1, width)
            Bitmap.createBitmap(bitmap, (width - newWidth) / 2, 0, newWidth, height)
        } else {
            // Too tall, cut the top and the bottom.
            val newHeight = (width / aspect).toInt().coerceIn(1, height)
            Bitmap.createBitmap(bitmap, 0, (height - newHeight) / 2, width, newHeight)
        }
    }

    private fun calculateInSampleSize(
        width: Int, height: Int, requestedWidth: Int, requestedHeight: Int
    ): Int {
        var sampleSize = 1
        if (width <= 0 || height <= 0) return sampleSize

        while (width / (sampleSize * 2) >= requestedWidth
            && height / (sampleSize * 2) >= requestedHeight
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * 只按最长边取采样率。
     *
     * 走模糊时不能沿用 [calculateInSampleSize]：那个要求两个方向都满足，遇到
     * 「宽图 + 竖屏」这种比例差很大的组合会退化成几乎不降采样。
     */
    private fun calculateInSampleSizeForLongSide(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1

        var sampleSize = 1
        while (max(width, height) / (sampleSize * 2) >= target) {
            sampleSize *= 2
        }
        return sampleSize
    }

    // ------------------------------------------------------------------ blur

    /**
     * 盒式模糊（box blur）跑 [BLUR_PASSES] 遍，逼近高斯模糊。
     *
     * 用可分离的一维滑动窗口实现：水平一遍、垂直一遍，复杂度 O(像素数)，
     * 与半径无关，所以在小图上跑三遍依然很快。
     *
     * @return 模糊后的新图；失败时返回 null，调用方会退回原图。
     */
    private fun boxBlur(source: Bitmap, radius: Int): Bitmap? {
        if (radius <= 0) return source

        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return source

        return try {
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)

            val blurred = blurPixels(pixels, width, height, radius)

            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(blurred, 0, width, 0, 0, width, height)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "boxBlur", t)
            null
        }
    }

    /**
     * 模糊的实际运算，输入输出都是打包成 ARGB 的像素。
     *
     * 刻意不依赖任何 Android 类型，这样同一份代码可以在 JVM 上直接做数值验证
     * （见 tools/ 下的验证脚本）。
     *
     * 实现要点：先把四个通道拆成独立的平面，并且用 8.8 定点数（左移 [FIXED_SHIFT] 位）
     * 保存。原因是每一遍都要除以窗口大小，如果直接对 8 位整数做除法，6 遍累积下来的
     * 截断误差会把暗部细节整片抹掉——实测一张只有单个亮点（255）的图会被抹成全黑。
     * 用定点数把每遍的量化误差压到 1/256 个色阶，肉眼与统计上都不可见。
     */
    private fun blurPixels(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val size = pixels.size
        if (radius <= 0 || width <= 0 || height <= 0) return pixels

        val planes = Array(4) { IntArray(size) }
        for (i in 0 until size) {
            val p = pixels[i]
            planes[0][i] = (p ushr 24 and 0xFF) shl FIXED_SHIFT
            planes[1][i] = (p ushr 16 and 0xFF) shl FIXED_SHIFT
            planes[2][i] = (p ushr 8 and 0xFF) shl FIXED_SHIFT
            planes[3][i] = (p and 0xFF) shl FIXED_SHIFT
        }

        val scratch = IntArray(size)
        for (channel in 0..3) {
            val plane = planes[channel]
            repeat(BLUR_PASSES) {
                blurPlanePass(plane, scratch, width, height, radius, horizontal = true)
                blurPlanePass(scratch, plane, width, height, radius, horizontal = false)
            }
        }

        val result = IntArray(size)
        val half = 1 shl (FIXED_SHIFT - 1)
        for (i in 0 until size) {
            val a = (planes[0][i] + half) shr FIXED_SHIFT
            val r = (planes[1][i] + half) shr FIXED_SHIFT
            val g = (planes[2][i] + half) shr FIXED_SHIFT
            val b = (planes[3][i] + half) shr FIXED_SHIFT
            result[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return result
    }

    /**
     * 单方向的一维盒式模糊。
     *
     * @param horizontal true 沿 x 方向、false 沿 y 方向
     */
    /**
     * 单方向的一维盒式模糊，作用于**单个通道平面**。
     *
     * 注意这里一个 int 只表示一个通道（而且是定点数），不是打包的 ARGB：
     * 拆包逻辑在 [blurPixels] 里已经做过了。
     *
     * @param horizontal true 沿 x 方向、false 沿 y 方向
     */
    private fun blurPlanePass(
        source: IntArray,
        target: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean
    ) {
        val lineCount = if (horizontal) height else width
        val lineLength = if (horizontal) width else height
        // 沿扫描方向每前进一格跨多少数组下标
        val step = if (horizontal) 1 else width
        // 换到下一条线要跳多少
        val lineStep = if (horizontal) width else 1
        val windowSize = radius * 2 + 1

        for (line in 0 until lineCount) {
            val start = line * lineStep

            var sum = 0
            // 起始窗口：超出边界的部分用边缘像素补齐
            for (i in -radius..radius) {
                sum += source[start + clampIndex(i, lineLength) * step]
            }

            for (i in 0 until lineLength) {
                target[start + i * step] = sum / windowSize

                // 窗口右移一格：去掉最左、加入最右
                sum += source[start + clampIndex(i + radius + 1, lineLength) * step] -
                        source[start + clampIndex(i - radius, lineLength) * step]
            }
        }
    }

    private fun clampIndex(index: Int, length: Int): Int = when {
        index < 0 -> 0
        index >= length -> length - 1
        else -> index
    }

    /**
     * 亮度对应的遮罩颜色：固定黑色，alpha = (100 - 亮度)。
     *
     * @return 亮度为 100（原图）时返回 0，表示不需要额外盖一层
     */
    private fun brightnessOverlayColor(context: Context): Int {
        val alpha = ((100 - getBrightness(context)) / 100f * 255).toInt().coerceIn(0, 255)
        if (alpha == 0) return 0
        return alpha shl 24
    }

    /**
     * 从当前背景图里提取一个适合当主题色种子的颜色。
     *
     * 为了快，解码成很小的图（最长边 [SEED_SAMPLE_LONG_SIDE]）再统计 —— 主题色不需要
     * 精确到像素，几十毫秒内出结果更重要。
     *
     * @return 归一化后的 ARGB；没有背景图或提取不出颜色时返回 null
     */
    fun extractThemeSeedColor(context: Context): Int? {
        val file = backgroundFile(context)
        if (!file.isFile) return null

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSizeForLongSide(
                    bounds.outWidth, bounds.outHeight, SEED_SAMPLE_LONG_SIDE
                )
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null

            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap.recycle()

            PaletteExtractor.extract(pixels)?.let { PaletteExtractor.normalizeSeed(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "extractThemeSeedColor", t)
            null
        }
    }

    private const val SEED_SAMPLE_LONG_SIDE = 96
}
