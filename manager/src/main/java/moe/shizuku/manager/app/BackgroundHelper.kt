package moe.shizuku.manager.app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.util.Log
import android.util.TypedValue
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
     * Preference key that stores how strongly the image is covered, in percent.
     *
     * 必须与 res/xml/settings.xml 中该条目的 android:key 一致（那边的检查由
     * tools/check-edits.sh 覆盖）。
     */
    const val KEY_BACKGROUND_VISIBILITY = "background_visibility"

    /** Used when the preference was never set (matches arrays.xml). */
    private const val DEFAULT_SCRIM_PERCENT = 60

    /**
     * Preference key that stores the gaussian blur level.
     *
     * 同样必须与 res/xml/settings.xml 保持一致。
     */
    const val KEY_BACKGROUND_BLUR = "background_blur"

    /** Used when the preference was never set (matches arrays.xml). */
    private const val DEFAULT_BLUR_LEVEL = 0

    private const val MAX_BLUR_LEVEL = 3

    /**
     * 模糊前先把图缩到最长边这么多像素，模糊完再让 BitmapDrawable 放大回窗口尺寸。
     *
     * 这样做有两个好处：模糊的运算量降低两个数量级（几十万像素而不是几百万），
     * 而且"缩小再放大"本身就是一次双线性低通滤波，和后面的盒式模糊叠加起来
     * 更接近高斯的效果。
     */
    private const val BLUR_TARGET_LONG_SIDE = 480

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
     * 各档位在小图上的模糊半径（像素）。索引 = 档位。
     *
     * 数值是按实测的高斯 sigma 定的（小图长边上限 480px，1080x2400 屏幕上放大
     * 约 6.4 倍）：radius 1/3/6 对应屏幕上的 sigma 约 10/22/43 像素。
     */
    private val BLUR_RADIUS = intArrayOf(0, 1, 3, 6)

    private var cachedBitmap: Bitmap? = null
    private var cachedKey: String? = null

    // ------------------------------------------------------------------ scrim

    /**
     * How strongly the image is covered by the theme background color, in percent.
     * 0 = raw image (most visible), 100 = image completely hidden.
     *
     * A cover is needed because the app text colors are designed for a solid background.
     */
    fun getScrimPercent(context: Context): Int {
        val preferences = ShizukuSettings.getPreferences() ?: return DEFAULT_SCRIM_PERCENT
        return preferences.getInt(KEY_BACKGROUND_VISIBILITY, DEFAULT_SCRIM_PERCENT).coerceIn(0, 100)
    }

    /**
     * 高斯模糊档位：0 = 关闭，1~3 = 由弱到强。
     *
     * 模糊只在解码阶段做一次并缓存，不会每帧重算。
     */
    fun getBlurLevel(context: Context): Int {
        val preferences = ShizukuSettings.getPreferences() ?: return DEFAULT_BLUR_LEVEL
        return preferences.getInt(KEY_BACKGROUND_BLUR, DEFAULT_BLUR_LEVEL).coerceIn(0, MAX_BLUR_LEVEL)
    }

    // ------------------------------------------------------------------ files

    private fun backgroundFile(context: Context): File =
        File(File(context.filesDir, DIR_NAME), FILE_NAME)

    fun hasCustomBackground(context: Context): Boolean {
        val file = backgroundFile(context)
        return file.isFile && file.length() > 0L
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
        val bitmap = loadBitmap(context) ?: return null

        val image = BitmapDrawable(context.resources, bitmap).apply {
            setGravity(Gravity.FILL)
        }
        // The image is covered by the theme background color so that the existing text colors
        // stay readable, whatever the image looks like.
        val scrim = ColorDrawable(scrimColor(context))

        return LayerDrawable(arrayOf<Drawable>(image, scrim))
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
        val blurLevel = getBlurLevel(context)
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}" +
                "|${requestedWidth}x$requestedHeight|blur=$blurLevel"

        val cached = cachedBitmap
        if (cached != null && !cached.isRecycled && cachedKey == key) return cached

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = if (blurLevel > 0) {
                    // 要走模糊，就只解出小图
                    calculateInSampleSizeForLongSide(
                        bounds.outWidth, bounds.outHeight, BLUR_TARGET_LONG_SIDE
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
            if (max(decoded.width, decoded.height) > MAX_BITMAP_DIMEN) {
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
            if (blurLevel > 0) {
                val blurred = boxBlur(cropped, blurLevel)
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
    private fun boxBlur(source: Bitmap, level: Int): Bitmap? {
        val radius = BLUR_RADIUS.getOrNull(level) ?: 0
        if (radius <= 0) return source

        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return source

        return try {
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)

            val blurred = blurPixels(pixels, width, height, level)

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
    private fun blurPixels(pixels: IntArray, width: Int, height: Int, level: Int): IntArray {
        val radius = BLUR_RADIUS.getOrNull(level) ?: 0
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

    private fun scrimColor(context: Context): Int {
        val value = TypedValue()
        val resolved = context.theme.resolveAttribute(android.R.attr.colorBackground, value, true)
        val base = if (resolved) value.data else Color.BLACK
        val alpha = (getScrimPercent(context) / 100f * 255).toInt().coerceIn(0, 255)
        return (base and 0x00FFFFFF) or (alpha shl 24)
    }
}
