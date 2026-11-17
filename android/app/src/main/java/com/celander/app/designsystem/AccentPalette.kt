package com.celander.app.designsystem

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.celander.app.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A ready-made color in Settings › Design. */
data class AccentPreset(@StringRes val name: Int, val hue: Float)

/**
 * Derives the hue-dependent colors of the theme from the color picked in Settings › Design: accent,
 * its soft variant and the background washes. Saturation and lightness follow the hand-tuned indigo
 * default, which this reproduces at [DEFAULT_HUE]. The accent is then darkened (light theme) or
 * lightened (dark theme) until text in it reaches [MIN_CONTRAST] on every surface it is used on, so
 * any hue stays readable.
 */
object AccentPalette {
    const val DEFAULT_HUE = 236f

    /** WCAG AA for normal text. */
    const val MIN_CONTRAST = 4.5f

    private const val SAME_HUE = 0.5f

    val presets = listOf(
        AccentPreset(R.string.accent_indigo, DEFAULT_HUE),
        AccentPreset(R.string.accent_blue, 211f),
        AccentPreset(R.string.accent_teal, 178f),
        AccentPreset(R.string.accent_green, 145f),
        AccentPreset(R.string.accent_amber, 38f),
        AccentPreset(R.string.accent_coral, 12f),
        AccentPreset(R.string.accent_rose, 336f),
        AccentPreset(R.string.accent_violet, 272f),
    )

    /** The ready-made color with this hue, or null for a custom one. */
    fun presetFor(hue: Float): AccentPreset? = presets.firstOrNull { abs(it.hue - hue) < SAME_HUE }

    fun colors(base: CelanderColors, hue: Float): CelanderColors {
        val h = normalize(hue)
        return if (base.isDark) {
            val soft = hsl(h, 0.37f, 0.27f)
            val accent = readable(h, saturation = 1f, lightness = 0.804f, step = 0.01f, against = listOf(base.glassTint, soft))
            base.copy(
                accent = accent,
                onAccent = hsl(h, 0.44f, 0.125f),
                accentSoft = soft,
                washes = listOf(hsl(h - 1f, 0.444f, 0.304f), hsl(h - 59f, 0.662f, 0.1745f), hsl(h + 56f, 0.368f, 0.229f)),
            )
        } else {
            val soft = hsl(h, 1f, 0.94f)
            val accent = readable(h, saturation = 0.63f, lightness = 0.565f, step = -0.01f, against = listOf(base.background, soft))
            base.copy(
                accent = accent,
                onAccent = Color.White,
                accentSoft = soft,
                washes = listOf(hsl(h + 13f, 1f, 0.91f), hsl(h + 147f, 1f, 0.908f), hsl(h - 83f, 0.49f, 0.884f)),
            )
        }
    }

    fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }

    /** Steps the lightness until the color reaches [MIN_CONTRAST] against all of [against]. */
    private fun readable(hue: Float, saturation: Float, lightness: Float, step: Float, against: List<Color>): Color {
        var l = lightness
        var color = hsl(hue, saturation, l)
        while (against.any { contrast(color, it) < MIN_CONTRAST } && l + step in 0f..1f) {
            l += step
            color = hsl(hue, saturation, l)
        }
        return color
    }

    private fun hsl(hue: Float, saturation: Float, lightness: Float) = Color.hsl(normalize(hue), saturation, lightness)

    private fun normalize(hue: Float) = ((hue % 360f) + 360f) % 360f
}
