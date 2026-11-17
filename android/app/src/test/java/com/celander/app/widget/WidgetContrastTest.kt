package com.celander.app.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.celander.app.ui.glass.GlassTokens
import com.celander.app.ui.theme.Accent
import com.celander.app.ui.theme.glassColors
import com.celander.app.ui.theme.glassScheme
import org.junit.Assert.assertTrue
import org.junit.Test

/** Widgets cannot blur the wallpaper: up to the default transparency the card keeps its text readable on any wallpaper. */
class WidgetContrastTest {
    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun theDefaultCardKeepsTextAtWcagAaOnBlackAndWhiteWallpapers() {
        for (dark in listOf(false, true)) {
            val palette = Accent.INDIGO.palette
            val colors = glassScheme(dark, palette, glassColors(dark, 65, palette))
            val card = (if (dark) WidgetCardDark else WidgetCardLight).copy(alpha = GlassTokens.widgetAlpha(transparency = GlassTokens.DEFAULT_TRANSPARENCY, dark = dark))
            for (wallpaper in listOf(Color.Black, Color.White)) {
                val surface = card.compositeOver(wallpaper)
                for (text in listOf(colors.onSurface, colors.onSurfaceVariant)) {
                    val ratio = contrast(text.compositeOver(surface), surface)
                    assertTrue("dark=$dark on $wallpaper: $ratio", ratio >= 4.5f)
                }
            }
        }
    }
}
