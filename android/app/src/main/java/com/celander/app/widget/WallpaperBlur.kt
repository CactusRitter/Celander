package com.celander.app.widget

import android.Manifest
import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The home screen wallpaper, blurred, behind the widget cards. Launchers cannot blur behind a
 * widget, so Celander reads the wallpaper itself (Android hands it out only with the access in
 * [missing]), blurs a small copy of the screen's part of it once per wallpaper and screen size, and
 * every card shows the piece it sits on ([WidgetPlacements]). Nothing of it leaves the device.
 */
internal object WallpaperBlur {
    /** Rounded clipping of the card, which the blurred picture needs, exists from Android 12. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** The photos permission: all photos from Android 13 on (a selection is not enough), storage before. */
    val photoPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    /**
     * The access Android still wants before it hands out the wallpaper, in the order to ask for it.
     * Android 12 checks storage; 13 to 15 "All files access" and then all photos (storage itself is
     * no longer granted there); 16 "All files access" alone.
     */
    fun missing(context: Context): List<BlurAccess> {
        val tiramisu = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        return missing(Build.VERSION.SDK_INT, allFiles = tiramisu && Environment.isExternalStorageManager(), photos = granted(context, photoPermission))
    }

    /** [missing] for Android [sdk], with [allFiles] and [photos] granted or not. */
    fun missing(sdk: Int, allFiles: Boolean, photos: Boolean): List<BlurAccess> = when {
        sdk < Build.VERSION_CODES.TIRAMISU -> listOfNotNull(BlurAccess.PHOTOS.takeUnless { photos })
        else -> listOfNotNull(
            BlurAccess.FILES.takeUnless { allFiles },
            BlurAccess.PHOTOS.takeUnless { photos || sdk >= Build.VERSION_CODES.BAKLAVA },
        )
    }

    /** Whether the wallpaper may be read. */
    fun permitted(context: Context): Boolean = supported && missing(context).isEmpty()

    private fun granted(context: Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @Volatile
    private var cached: Pair<String, BlurredWallpaper>? = null

    /**
     * Counts changes to what the cards show (wallpaper read again, a card aligned or all realigned).
     * Running widget sessions only recompose on observed state, so they read this.
     */
    val revision = MutableStateFlow(0)

    fun changed() = revision.update { it + 1 }

    /**
     * The blurred wallpaper of the screen as it is now, read once per wallpaper and screen size and
     * kept in memory and the cache folder. Null without access, for a live wallpaper or when it
     * cannot be read.
     */
    fun current(context: Context): BlurredWallpaper? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !permitted(context)) return null
        val manager = WallpaperManager.getInstance(context)
        if (manager.wallpaperInfo != null) return null
        val screen = screenSize(context)
        val key = "${manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)}_${screen.width()}x${screen.height()}"
        cached?.takeIf { it.first == key }?.let { return it.second }
        val file = File(folder(context), "$key.png")
        val bitmap = file.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            ?: render(manager, screen.width(), screen.height(), context.resources.displayMetrics.density)?.also { save(it, file) }
            ?: return null
        return BlurredWallpaper(bitmap, screen.width(), screen.height()).also { cached = key to it }
    }

    /** Forgets the blurred wallpaper, so the next widget update reads the wallpaper again. */
    fun forget(context: Context) {
        cached = null
        folder(context).deleteRecursively()
        changed()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun screenSize(context: Context): Rect = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds

    private fun folder(context: Context) = File(context.cacheDir, FOLDER)

    // Android 12 accepts storage instead of MANAGE_EXTERNAL_STORAGE, which the annotation does not know ([missing]).
    @SuppressLint("MissingPermission")
    private fun render(manager: WallpaperManager, screenWidth: Int, screenHeight: Int, density: Float): Bitmap? = try {
        val bytes = manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)?.let { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        bytes?.let { blurredScreen(it, screenWidth, screenHeight, density) }
    } catch (_: SecurityException) {
        null
    } catch (_: IOException) {
        null
    }

    private fun blurredScreen(bytes: ByteArray, screenWidth: Int, screenHeight: Int, density: Float): Bitmap? {
        val width = (screenWidth / SCALE).coerceAtLeast(1)
        val height = (screenHeight / SCALE).coerceAtLeast(1)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = BlurMath.sampleSize(bounds.outWidth, bounds.outHeight, width, height) }
        val wallpaper = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val crop = BlurMath.centerCrop(wallpaper.width, wallpaper.height, screenWidth, screenHeight)
        val small = createBitmap(width, height)
        Canvas(small).drawBitmap(
            wallpaper,
            Rect(crop.left, crop.top, crop.right, crop.bottom),
            Rect(0, 0, width, height),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        wallpaper.recycle()
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        BlurMath.blur(pixels, width, height, (BLUR_DP * density / SCALE).roundToInt())
        small.setPixels(pixels, 0, width, 0, 0, width, height)
        return small
    }

    private fun save(bitmap: Bitmap, file: File) {
        try {
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: IOException) {
            // Without the cache the next update blurs the wallpaper again.
        }
    }

    /** The blurred copy has an eighth of the screen's width and height; blurred, that loses nothing. */
    private const val SCALE = 8
    private const val BLUR_DP = 28f
    private const val FOLDER = "widget_blur"
}

/** Access the wallpaper needs: "All files access" in the system settings, or photos through the permission dialog. */
internal enum class BlurAccess { FILES, PHOTOS }

/** The blurred wallpaper at a fraction of the screen's [screenWidth] x [screenHeight] pixels. */
internal class BlurredWallpaper(val bitmap: Bitmap, val screenWidth: Int, val screenHeight: Int) {
    /** The part behind a card at [card] on the screen, or null when the card lies outside it. */
    fun behind(card: PixelRect): Bitmap? {
        val area = BlurMath.toBitmap(card, screenWidth, screenHeight, bitmap.width, bitmap.height) ?: return null
        return Bitmap.createBitmap(bitmap, area.left, area.top, area.width, area.height)
    }
}

/** A rectangle in pixels, right and bottom exclusive. */
internal data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** The arithmetic of the wallpaper blur, free of Android so it can be tested on its own. */
internal object BlurMath {
    /**
     * The part of a [width] x [height] wallpaper that fills a [screenWidth] x [screenHeight] screen,
     * as launchers show it: scaled to cover the screen and centered.
     */
    fun centerCrop(width: Int, height: Int, screenWidth: Int, screenHeight: Int): PixelRect {
        val scale = maxOf(screenWidth.toFloat() / width, screenHeight.toFloat() / height)
        val cropWidth = (screenWidth / scale).roundToInt().coerceIn(1, width)
        val cropHeight = (screenHeight / scale).roundToInt().coerceIn(1, height)
        val left = (width - cropWidth) / 2
        val top = (height - cropHeight) / 2
        return PixelRect(left, top, left + cropWidth, top + cropHeight)
    }

    /** The largest power of two to decode a [width] x [height] image with that still covers [targetWidth] x [targetHeight]. */
    fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
        return sample
    }

    /** [card] on a [screenWidth] x [screenHeight] screen in a [width] x [height] copy of it; null when nothing of it is on the screen. */
    fun toBitmap(card: PixelRect, screenWidth: Int, screenHeight: Int, width: Int, height: Int): PixelRect? {
        if (card.right <= 0 || card.bottom <= 0 || card.left >= screenWidth || card.top >= screenHeight) return null
        val scaleX = width.toFloat() / screenWidth
        val scaleY = height.toFloat() / screenHeight
        val left = (card.left * scaleX).roundToInt().coerceIn(0, width - 1)
        val top = (card.top * scaleY).roundToInt().coerceIn(0, height - 1)
        val right = (card.right * scaleX).roundToInt().coerceIn(left + 1, width)
        val bottom = (card.bottom * scaleY).roundToInt().coerceIn(top + 1, height)
        return PixelRect(left, top, right, bottom)
    }

    /** Blurs ARGB [pixels] in place: three box blurs of [radius] across and down, close to a Gaussian. */
    fun blur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        if (radius < 1 || width < 1 || height < 1) return
        val buffer = IntArray(pixels.size)
        repeat(PASSES) {
            boxPass(pixels, buffer, width, height, radius, across = true)
            boxPass(buffer, pixels, width, height, radius, across = false)
        }
    }

    private fun boxPass(source: IntArray, target: IntArray, width: Int, height: Int, radius: Int, across: Boolean) {
        val lines = if (across) height else width
        val length = if (across) width else height
        val window = radius * 2 + 1
        for (line in 0 until lines) {
            fun index(i: Int): Int {
                val at = i.coerceIn(0, length - 1)
                return if (across) line * width + at else at * width + line
            }
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val p = source[index(i)]
                a += p ushr 24
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            for (i in 0 until length) {
                target[index(i)] = ((a / window) shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val leaving = source[index(i - radius)]
                val entering = source[index(i + radius + 1)]
                a += (entering ushr 24) - (leaving ushr 24)
                r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }

    private const val PASSES = 3
}
