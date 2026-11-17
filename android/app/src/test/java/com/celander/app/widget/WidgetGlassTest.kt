package com.celander.app.widget

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The glass of the widget card: a light rim on its edge and a shadow only outside it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetGlassTest {
    private val density = 2.625f
    private val size = DpSize(340.dp, 180.dp)
    private val memory = PictureMemory(1080L * 2400, listOf(size, DpSize(680.dp, 100.dp)))

    private fun alpha(bitmap: Bitmap, x: Int, y: Int) = Color.alpha(bitmap.getPixel(x, y))

    @Test
    fun theRimLightsTheEdgeBrightestAtTheTopLeftAndLeavesTheGlassClear() {
        val rim = WidgetGlass.render(size, density, memory).rim
        val card = WidgetGlass.cardSize(size)
        assertTrue(kotlin.math.abs((card.width.value * density).toInt() - rim.width) <= 1)
        val middleX = rim.width / 2
        val top = alpha(rim, middleX / 2, 1)
        val bottom = alpha(rim, middleX, rim.height - 2)
        assertTrue("top $top", top > 100)
        assertTrue("bottom $bottom", bottom in 1 until top)
        assertEquals(0, alpha(rim, middleX, rim.height / 2))
    }

    @Test
    fun theShadowFallsBelowTheCardAndNeverUnderIt() {
        val shadow = WidgetGlass.render(size, density, memory).shadow
        val scale = shadow.width / size.width.value
        val centerX = shadow.width / 2
        val cardBottom = ((size.height - WidgetElevation.bottom).value * scale).toInt()
        assertEquals(0, alpha(shadow, centerX, shadow.height / 2))
        assertTrue(alpha(shadow, centerX, cardBottom + 1) > 0)
        assertTrue("stronger below than above", alpha(shadow, centerX, cardBottom + 1) > alpha(shadow, centerX, (WidgetElevation.top.value * scale).toInt() - 2))
    }

    @Test
    fun theContentSeesTheCardsSize() {
        assertEquals(DpSize(320.dp, 160.dp), WidgetGlass.cardSize(size))
        assertEquals(DpSize(0.dp, 0.dp), WidgetGlass.cardSize(DpSize(10.dp, 10.dp)))
    }

    @Test
    fun aWidgetWithoutASizeYetDrawsNothing() {
        val glass = WidgetGlass.render(DpSize(0.dp, 0.dp), density, memory)
        assertEquals(1, glass.rim.width)
    }
}
