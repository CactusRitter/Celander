package com.celander.app.designsystem

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassTokensTest {
    private val default = GlassTokens.DEFAULT_TRANSPARENCY

    @Test
    fun zeroTransparencyIsSolidWithoutBlur() {
        assertTrue(GlassTokens.isSolid(0f))
        assertEquals(0.dp, GlassTokens.blurRadius(0f))
        for (dark in listOf(false, true)) {
            assertEquals(1f, GlassTokens.fillAlpha(0f, dark, blurActive = false))
            assertEquals(1f, GlassTokens.sheetFillAlpha(0f, dark, blurActive = false))
            assertEquals(1f, GlassTokens.tileAlpha(0f, dark))
        }
    }

    @Test
    fun defaultMatchesTheFormerStandardGlass() {
        assertEquals(0.52f, GlassTokens.fillAlpha(default, dark = true, blurActive = true), 0.02f)
        assertEquals(0.58f, GlassTokens.fillAlpha(default, dark = false, blurActive = true), 0.02f)
        assertEquals(0.82f, GlassTokens.sheetFillAlpha(default, dark = true, blurActive = true), 0.02f)
        assertEquals(0.86f, GlassTokens.sheetFillAlpha(default, dark = false, blurActive = true), 0.02f)
        assertEquals(0.46f, GlassTokens.tileAlpha(default, dark = true), 0.03f)
        assertEquals(0.56f, GlassTokens.tileAlpha(default, dark = false), 0.03f)
        assertEquals(25.dp, GlassTokens.blurRadius(default))
    }

    @Test
    fun moreTransparencyMeansClearerGlassWithTheSameBlur() {
        val steps = (0..20).map { it / 20f }
        for (dark in listOf(false, true)) {
            for (blur in listOf(false, true)) {
                val fills = steps.map { GlassTokens.fillAlpha(it, dark, blur) }
                assertTrue(fills.zipWithNext().all { (a, b) -> b < a })
            }
        }
        assertTrue(steps.drop(1).all { GlassTokens.blurRadius(it) == GlassTokens.blur })
    }

    @Test
    fun sheetsStayFullerThanFloatingGlass() {
        for (dark in listOf(false, true)) {
            for (transparency in listOf(GlassTokens.DEFAULT_TRANSPARENCY, 0.9f)) {
                assertTrue(GlassTokens.sheetFillAlpha(transparency, dark, blurActive = true) > GlassTokens.fillAlpha(transparency, dark, blurActive = true))
            }
        }
    }

    @Test
    fun fullTransparencyIsFullyClear() {
        for (dark in listOf(false, true)) {
            for (blur in listOf(false, true)) {
                assertEquals(0f, GlassTokens.fillAlpha(1f, dark, blur))
                assertEquals(0f, GlassTokens.sheetFillAlpha(1f, dark, blur))
            }
            assertEquals(0f, GlassTokens.tileAlpha(1f, dark))
        }
    }

    @Test
    fun widgetsKeepAFloorOfOpacityBecauseTheyCannotBlur() {
        for (dark in listOf(false, true)) {
            assertEquals(GlassTokens.WIDGET_MIN_ALPHA, GlassTokens.widgetAlpha(1f, dark))
            assertEquals(1f, GlassTokens.widgetAlpha(0f, dark))
        }
    }

    @Test
    fun valuesOutsideTheRangeAreClamped() {
        assertEquals(GlassTokens.fillAlpha(1f, dark = true, blurActive = true), GlassTokens.fillAlpha(1.5f, dark = true, blurActive = true))
    }
}
