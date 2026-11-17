package com.celander.app.widget

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.celander.app.designsystem.ShadowLayer
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The glass of a widget card as alpha masks the launcher tints for day and night: [rim] is the light
 * edge of the card (bright at the top left, fading, catching light again at the bottom right) and
 * [shadow] the soft shadow around it, outside the card only, as `softShadow` draws it in the app.
 * Widgets cannot blur the wallpaper; the frost is the card's translucent fill.
 */
internal class WidgetGlass(val rim: Bitmap, val shadow: Bitmap) {
    companion object {
        /**
         * Masks for a widget of [size] whose card sits [WidgetElevation] inside it. The rim is drawn at
         * screen [density] unless the masks of all reported sizes would pass their share of [memory];
         * the shadow is soft, so a quarter of the pixels draw it as well.
         */
        fun render(size: DpSize, density: Float, memory: PictureMemory): WidgetGlass {
            // Nothing to draw before the launcher reported a size.
            if (size.width <= 0.dp || size.height <= 0.dp) return WidgetGlass(createBitmap(1, 1, Bitmap.Config.ALPHA_8), createBitmap(1, 1, Bitmap.Config.ALPHA_8))
            val scale = glassScale(size.width.value * size.height.value * density * density, density, memory)
            val rimDensity = density * scale
            val card = cardSize(size)
            val rim = rim(px(card.width, rimDensity), px(card.height, rimDensity), rimDensity)
            val shadowDensity = rimDensity * SHADOW_RESOLUTION
            val shadow = shadow(px(size.width, shadowDensity), px(size.height, shadowDensity), shadowDensity)
            return WidgetGlass(rim, shadow)
        }

        /** The card inside a widget of [size]; content inside it sees this as its size. */
        fun cardSize(size: DpSize): DpSize = DpSize(
            (size.width - WidgetElevation.side * 2).coerceAtLeast(0.dp),
            (size.height - WidgetElevation.top - WidgetElevation.bottom).coerceAtLeast(0.dp),
        )

        private fun px(value: Dp, density: Float): Int = (value.value * density).roundToInt().coerceAtLeast(1)

        private fun rim(width: Int, height: Int, density: Float): Bitmap {
            val bitmap = createBitmap(width, height, Bitmap.Config.ALPHA_8)
            val stroke = WidgetElevation.rim.value * density
            val half = stroke / 2
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                shader = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    RIM.map { (_, alpha) -> Color.argb((alpha * 255).roundToInt(), 255, 255, 255) }.toIntArray(),
                    RIM.map { (stop, _) -> stop }.toFloatArray(),
                    Shader.TileMode.CLAMP,
                )
            }
            val radius = WidgetTokens.corner.value * density - half
            Canvas(bitmap).drawRoundRect(RectF(half, half, width - half, height - half), radius, radius, paint)
            return bitmap
        }

        private fun shadow(width: Int, height: Int, density: Float): Bitmap {
            val bitmap = createBitmap(width, height, Bitmap.Config.ALPHA_8)
            val canvas = Canvas(bitmap)
            val card = RectF(
                WidgetElevation.side.value * density,
                WidgetElevation.top.value * density,
                width - WidgetElevation.side.value * density,
                height - WidgetElevation.bottom.value * density,
            )
            val radius = WidgetTokens.corner.value * density
            WidgetElevation.layers.forEach { layer ->
                val blur = layer.blur.value * density
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    alpha = (layer.alpha * 255).roundToInt()
                    if (blur >= MIN_BLUR_PX) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
                }
                val offset = layer.offsetY.value * density
                canvas.drawRoundRect(RectF(card.left, card.top + offset, card.right, card.bottom + offset), radius, radius, paint)
            }
            // Only outside the card: under translucent glass a shadow would read as a dirty fill.
            canvas.drawRoundRect(card, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
            return bitmap
        }

        /**
         * The rim's color for the app's glass edge [edge]: on a dark card the widget has no blur to set
         * its edge off, so the rim shines brighter there than in the app.
         */
        fun rimColor(edge: androidx.compose.ui.graphics.Color, dark: Boolean): androidx.compose.ui.graphics.Color =
            if (dark) edge.copy(alpha = DARK_RIM_ALPHA) else edge

        /** The rim's strength from the top left corner to the bottom right one, as (position, alpha). */
        val RIM = listOf(0f to 1f, 0.4f to 0.35f, 0.65f to 0.27f, 1f to 0.7f)

        private const val DARK_RIM_ALPHA = 0.4f
        private const val SHADOW_RESOLUTION = 0.25f
        private const val MIN_BLUR_PX = 0.5f
    }
}

/**
 * The widget card's depth: the card sits [side], [top] and [bottom] inside the widget so its shadow
 * has room, a faint contact and a soft ambient shadow, scaled to that room so neither looks hard.
 */
internal object WidgetElevation {
    val side = 10.dp
    val top = 8.dp
    val bottom = 12.dp
    val rim = 1.25.dp

    /** Each layer's blur plus offset stays inside the room around the card, so no shadow is cut at the widget's edge. */
    val layers = listOf(ShadowLayer(blur = 3.dp, offsetY = 1.dp, alpha = 0.3f), ShadowLayer(blur = 6.dp, offsetY = 2.dp, alpha = 0.6f))
}

/**
 * One scale for the glass masks of every reported size, within at most [GLASS_SHARE] of the system's
 * bitmap limit; the grids of month and year keep the rest (see [layerPixelBudget]).
 */
internal fun glassScale(area: Float, density: Float, memory: PictureMemory): Float =
    min(1f, sqrt(memory.screenPixels * SYSTEM_BYTES_PER_SCREEN_PIXEL * GLASS_SHARE / GLASS_BYTES_PER_PIXEL / glassPixels(area, density, memory)))

/** Bytes the glass masks of all reported sizes take. */
internal fun glassBytes(area: Float, density: Float, memory: PictureMemory): Float {
    val scale = glassScale(area, density, memory)
    return glassPixels(area, density, memory) * scale * scale * GLASS_BYTES_PER_PIXEL
}

private fun glassPixels(area: Float, density: Float, memory: PictureMemory): Float {
    val reported = memory.sizes.sumOf { it.width.value.toDouble() * it.height.value * density * density }.toFloat()
    return (if (memory.sizes.isEmpty()) area * FALLBACK_GLASS_PICTURES else maxOf(reported, area)).coerceAtLeast(1f)
}

/** The rim at full size plus the shadow at a sixteenth of the pixels, one byte each. */
internal const val GLASS_BYTES_PER_PIXEL = 1f + 1f / 16f
private const val GLASS_SHARE = 0.15f
private const val FALLBACK_GLASS_PICTURES = 2
