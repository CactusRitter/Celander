package com.celander.app.widget

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.glance.appwidget.LocalAppWidgetOptions
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A widget picture in separate layers. [ink], [sunday] and [accent] are alpha masks the launcher
 * tints with the day or night color of the theme; [color] holds calendar colors as translucent
 * tints that suit a light and a dark card alike. So one render serves both themes.
 */
internal class WidgetLayers(val width: Int, val height: Int, withAccent: Boolean) {
    val ink: Bitmap = createBitmap(width, height, Bitmap.Config.ALPHA_8)
    val sunday: Bitmap = createBitmap(width, height, Bitmap.Config.ALPHA_8)
    val accent: Bitmap? = if (withAccent) createBitmap(width, height, Bitmap.Config.ALPHA_8) else null
    val color: Bitmap = createBitmap(width, height)

    val inkCanvas = Canvas(ink)
    val sundayCanvas = Canvas(sunday)
    val accentCanvas = accent?.let(::Canvas)
    val colorCanvas = Canvas(color)

    /** Drawn tasks that can be checked off, so the widget can put a tap area over each. */
    val tasks = mutableListOf<TaskTarget>()

    /** Fades everything inside [rect] on every layer to [alpha] (days outside the month, past months). */
    fun fade(rect: RectF, alpha: Float) {
        val paint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            this.alpha = (alpha * 255).toInt()
        }
        listOfNotNull(inkCanvas, sundayCanvas, accentCanvas, colorCanvas).forEach { it.drawRect(rect, paint) }
    }
}

/** A task drawn in week [row], day column [col], [top] px below the row's top line and [height] px high. */
internal data class TaskTarget(val row: Int, val col: Int, val top: Float, val height: Float, val taskId: Long, val title: String, val done: Boolean)

/** Pixel size and density of a drawn area; see [gridCanvas]. */
internal data class GridCanvas(val width: Int, val height: Int, val density: Float)

/**
 * The bitmap memory of one widget update: the system allows 1.5 screens in ARGB ([screenPixels] ×
 * 6 bytes) across all its pictures, and Glance draws one picture per size the launcher reports
 * ([sizes], see [reportedSizes]): portrait and landscape on most phones, up to four on foldables.
 */
internal data class PictureMemory(val screenPixels: Long, val sizes: List<DpSize>)

/** [PictureMemory] of the widget being composed. */
@Composable
internal fun rememberPictureMemory(context: Context): PictureMemory {
    val options = LocalAppWidgetOptions.current
    return remember(context, options) { PictureMemory(bitmapScreenPixels(context), reportedSizes(options)) }
}

/**
 * The pixels for drawing a [width] x [height] area: at screen [density], so text stays as sharp as
 * the launcher's own, unless the pictures of all sizes together would pass [memory]; only then, on
 * a large widget, are they scaled down (see [pictureScale]).
 */
internal fun gridCanvas(width: Dp, height: Dp, density: Float, memory: PictureMemory, withAccent: Boolean): GridCanvas {
    val w = width.value * density
    val h = height.value * density
    val scale = pictureScale(w * h, density, memory, withAccent)
    return GridCanvas((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), density * scale)
}

/**
 * One scale for the pictures of every reported size, so that all of them together stay within
 * [layerPixelBudget]. A widget's size bounds the area drawn in it, so the reported sizes are a safe
 * estimate; when the launcher reports none, two pictures of [area] (portrait and landscape) are assumed.
 */
internal fun pictureScale(area: Float, density: Float, memory: PictureMemory, withAccent: Boolean): Float {
    val reported = memory.sizes.sumOf { it.width.value.toDouble() * it.height.value * density * density }.toFloat()
    val total = if (memory.sizes.isEmpty()) area * FALLBACK_PICTURES else maxOf(reported, area)
    return min(1f, sqrt(layerPixelBudget(memory.screenPixels, withAccent) / total.coerceAtLeast(1f)))
}

/**
 * Pixels each layer may have across all pictures of one widget: 80 % of the system's limit,
 * shared by a 4-byte color layer and 1-byte alpha masks.
 */
internal fun layerPixelBudget(screenPixels: Long, withAccent: Boolean): Float {
    val bytesPerPixel = COLOR_LAYER_BYTES + MASK_LAYERS + if (withAccent) 1 else 0
    return (screenPixels * SYSTEM_BYTES_PER_SCREEN_PIXEL * USED_SHARE).coerceAtLeast(0f) / bytesPerPixel
}

/**
 * The screen pixels the system's limit is based on. It measures the display once at boot; a
 * foldable may have booted on its outer screen, so there the count is capped (see [limitScreenPixels]).
 */
internal fun bitmapScreenPixels(context: Context): Long {
    val metrics = context.resources.displayMetrics
    val foldable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)
    return limitScreenPixels(metrics.widthPixels.toLong() * metrics.heightPixels, foldable)
}

/** [current] screen pixels, on a [foldable] at most as many as the smaller outer screens of today's foldables have. */
internal fun limitScreenPixels(current: Long, foldable: Boolean): Long = if (foldable) min(current, FOLDABLE_SCREEN_PIXELS) else current

internal const val SYSTEM_BYTES_PER_SCREEN_PIXEL = 6f
private const val USED_SHARE = 0.8f
private const val FALLBACK_PICTURES = 2
private const val COLOR_LAYER_BYTES = 4
private const val MASK_LAYERS = 2

/** Outer screens of book-style foldables have about 1.8 to 2.8 million pixels. */
private const val FOLDABLE_SCREEN_PIXELS = 1_800_000L

/** Text paints for drawing into alpha masks: the mask's alpha is the text's opacity. */
internal class WidgetPaints(private val density: Float, private val fontScale: Float) {
    private val regular: Typeface = Typeface.DEFAULT
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val bold: Typeface = Typeface.DEFAULT_BOLD

    fun dp(value: Float): Float = value * density

    fun sp(value: Float): Float = value * density * fontScale

    fun text(size: Float, weight: Weight = Weight.REGULAR, alpha: Float = 1f, align: Paint.Align = Paint.Align.LEFT): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = sp(size)
            typeface = when (weight) {
                Weight.REGULAR -> regular
                Weight.MEDIUM -> medium
                Weight.BOLD -> bold
            }
            this.alpha = (alpha * 255).toInt()
            textAlign = align
        }

    fun fill(alpha: Float = 1f): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.alpha = (alpha * 255).toInt() }

    fun fill(argb: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb }

    fun stroke(width: Float, alpha: Float = 1f): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        this.alpha = (alpha * 255).toInt()
    }

    /** Draws text by clearing the mask, so the card shows through (today's number in its badge). */
    fun cutOut(paint: Paint): Paint = Paint(paint).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    enum class Weight { REGULAR, MEDIUM, BOLD }
}

/** Draws [text] in one line from [x] at [baseline], clipped to [right] without an ellipsis, like the month cells. */
internal fun Canvas.drawClipped(text: String, x: Float, baseline: Float, right: Float, paint: Paint) {
    if (right <= x) return
    withClip(x, baseline - paint.textSize * 1.2f, right, baseline + paint.textSize * 0.4f) {
        drawText(text, x, baseline, paint)
    }
}
