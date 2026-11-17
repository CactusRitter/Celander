package com.celander.app.ui

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.celander.app.R
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Curated calendar palette. Each entry has a tone for light and one for dark mode so bars, dots and
 * tinted chips keep their contrast in both. Only [key] is persisted.
 */
enum class CalendarColor(
    val key: String,
    private val lightTone: Color,
    private val darkTone: Color,
    @StringRes val label: Int,
) {
    LAVENDER("lavender", Color(0xFF7468DA), Color(0xFFA99FFF), R.string.color_lavender),
    BLUE("blue", Color(0xFF3473DB), Color(0xFF82AEFF), R.string.color_blue),
    SKY("sky", Color(0xFF1592C9), Color(0xFF6CC8F2), R.string.color_sky),
    TEAL("teal", Color(0xFF138C81), Color(0xFF5CCFC2), R.string.color_teal),
    SAGE("sage", Color(0xFF55905F), Color(0xFF93CB9C), R.string.color_sage),
    GREEN("green", Color(0xFF2B9348), Color(0xFF6BD48A), R.string.color_green),
    MUSTARD("mustard", Color(0xFFB98A0E), Color(0xFFF0C95A), R.string.color_mustard),
    ORANGE("orange", Color(0xFFDB6B1F), Color(0xFFFFA866), R.string.color_orange),
    CORAL("coral", Color(0xFFD9534C), Color(0xFFFF958E), R.string.color_coral),
    RED("red", Color(0xFFC0313F), Color(0xFFFF7C88), R.string.color_red),
    PINK("pink", Color(0xFFC94D91), Color(0xFFFF8DC6), R.string.color_pink),
    GRAPHITE("graphite", Color(0xFF636A79), Color(0xFFA6ACBA), R.string.color_graphite);

    /** Solid tone for bars, dots and accents. */
    fun base(dark: Boolean): Color = if (dark) darkTone else lightTone

    /** Tinted background for chips; text on it uses the regular primary text color. */
    fun container(dark: Boolean, surface: Color): Color =
        lerp(surface, base(dark), if (dark) 0.30f else 0.20f)

    companion object {
        val DEFAULT = LAVENDER

        fun fromKey(key: String?): CalendarColor = entries.firstOrNull { it.key == key } ?: DEFAULT

        /** Below this OKLab chroma a color reads as grey and maps to [GRAPHITE]. */
        private const val GREY_CHROMA = 0.04

        /**
         * Chroma differences count less than hue differences: the palette's light and dark tones of
         * one color differ mostly in lightness and chroma, while the hue carries its identity.
         */
        private const val CHROMA_WEIGHT = 0.75

        private val tones: List<Pair<CalendarColor, List<Lab>>> by lazy {
            entries.map { color -> color to listOf(color.lightTone, color.darkTone).map { Lab.of(it.red, it.green, it.blue) } }
        }

        /**
         * Palette entry closest to an external color such as a provider calendar's ARGB value. Compares in
         * OKLab against both tones of each entry; alpha is ignored and low-chroma colors become [GRAPHITE].
         */
        fun nearest(argb: Int): CalendarColor {
            val target = Lab.of((argb shr 16 and 0xFF) / 255f, (argb shr 8 and 0xFF) / 255f, (argb and 0xFF) / 255f)
            if (target.chroma < GREY_CHROMA) return GRAPHITE
            return tones.minBy { (_, labs) -> labs.minOf { target.distanceTo(it) } }.first
        }
    }

    /** OKLab coordinates (Björn Ottosson, 2020) of an sRGB color. */
    private class Lab(val l: Double, val a: Double, val b: Double) {
        val chroma: Double get() = hypot(a, b)

        /** Squared distance with lightness and hue at full weight and chroma at [CHROMA_WEIGHT]. */
        fun distanceTo(other: Lab): Double {
            val dl = l - other.l
            val dc = chroma - other.chroma
            val de2 = dl * dl + (a - other.a).pow(2) + (b - other.b).pow(2)
            val dh2 = (de2 - dl * dl - dc * dc).coerceAtLeast(0.0)
            return dl * dl + CHROMA_WEIGHT * dc * dc + dh2
        }

        companion object {
            fun of(red: Float, green: Float, blue: Float): Lab {
                val r = linear(red)
                val g = linear(green)
                val b = linear(blue)
                val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
                val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
                val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
                return Lab(
                    l = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                    a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                    b = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
                )
            }

            private fun linear(channel: Float): Double {
                val c = channel.toDouble()
                return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
        }
    }
}

@Composable
@ReadOnlyComposable
fun CalendarColor.color(): Color = base(MaterialTheme.colorScheme.isDark)

@Composable
@ReadOnlyComposable
fun CalendarColor.containerColor(): Color = MaterialTheme.colorScheme.let { container(it.isDark, it.surface) }
