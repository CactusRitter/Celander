package com.celander.app.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.celander.app.designsystem.AccentPalette
import com.celander.app.designsystem.GlassTokens
import com.celander.app.designsystem.celanderColors
import org.junit.Assert.assertTrue
import org.junit.Test

/** Widgets cannot blur the wallpaper: up to the default transparency the card keeps its text readable on any wallpaper. */
class WidgetContrastTest {
    @Test
    fun theDefaultCardKeepsTextAtWcagAaOnBlackAndWhiteWallpapers() {
        for (dark in listOf(false, true)) {
            val colors = celanderColors(dark, accentHue = 0f)
            val card = (if (dark) WidgetCardDark else WidgetCardLight).copy(alpha = GlassTokens.widgetAlpha(transparency = GlassTokens.DEFAULT_TRANSPARENCY, dark = dark))
            for (wallpaper in listOf(Color.Black, Color.White)) {
                val surface = card.compositeOver(wallpaper)
                for (text in listOf(colors.textPrimary, colors.textSecondary)) {
                    val ratio = AccentPalette.contrast(text, surface)
                    assertTrue("dark=$dark on $wallpaper: $ratio", ratio >= 4.5f)
                }
            }
        }
    }
}
