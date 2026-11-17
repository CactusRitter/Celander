package com.celander.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arithmetic behind the widgets' blurred wallpaper. */
class BlurMathTest {
    @Test
    fun aWideWallpaperShowsItsMiddleOnAPortraitScreen() {
        // 3000 x 2000 scaled to cover 1000 x 2000: the middle 1000 x 2000.
        assertEquals(PixelRect(1000, 0, 2000, 2000), BlurMath.centerCrop(3000, 2000, 1000, 2000))
        // A wallpaper of the screen's shape is shown whole.
        assertEquals(PixelRect(0, 0, 1080, 2400), BlurMath.centerCrop(1080, 2400, 1080, 2400))
    }

    @Test
    fun theWallpaperIsDecodedNoSmallerThanTheCopyNeeds() {
        assertEquals(32, BlurMath.sampleSize(4320, 9600, 135, 300))
        assertEquals(16, BlurMath.sampleSize(4320, 9600, 136, 300))
        assertEquals(1, BlurMath.sampleSize(200, 400, 135, 300))
    }

    @Test
    fun aCardOnTheScreenMapsOntoTheSmallCopy() {
        val card = PixelRect(80, 400, 1000, 1200)
        assertEquals(PixelRect(10, 50, 125, 150), BlurMath.toBitmap(card, 1080, 2400, 135, 300))
        assertNull(BlurMath.toBitmap(PixelRect(-500, 0, -10, 100), 1080, 2400, 135, 300))
    }

    @Test
    fun blurSpreadsADotEvenlyAndKeepsAPlainColor() {
        val width = 21
        val pixels = IntArray(width * width) { 0xFF000000.toInt() }
        pixels[10 * width + 10] = 0xFFFFFFFF.toInt()
        BlurMath.blur(pixels, width, width, 2)
        val center = pixels[10 * width + 10] and 0xFF
        val near = pixels[10 * width + 12] and 0xFF
        val far = pixels[0] and 0xFF
        assertTrue("center $center near $near", center in 1 until 255 && near in 1 until center)
        assertEquals(0, far)
        assertEquals(pixels[10 * width + 8], pixels[10 * width + 12])

        val plain = IntArray(16) { 0xFF336699.toInt() }
        BlurMath.blur(plain, 4, 4, 3)
        assertTrue(plain.all { it == 0xFF336699.toInt() })
    }
}
