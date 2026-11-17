package com.celander.app.widget

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The wallpaper read, blurred and cut to the piece behind a card, once Android hands it out (Android 12: storage). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "w360dp-h800dp-xxhdpi")
class WallpaperBlurTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = WallpaperBlur.forget(context)

    private fun allowReading(allowed: Boolean) {
        val app = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
        if (allowed) app.grantPermissions(WallpaperBlur.photoPermission) else app.denyPermissions(WallpaperBlur.photoPermission)
    }

    /** Red on the left half, blue on the right, in the screen's shape. */
    private fun setWallpaper() {
        val screen = WallpaperBlur.screenSize(context)
        val bitmap = createBitmap(screen.width() / 2, screen.height() / 2)
        Canvas(bitmap).apply {
            drawRect(0f, 0f, bitmap.width / 2f, bitmap.height.toFloat(), Paint().apply { color = Color.RED })
            drawRect(bitmap.width / 2f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), Paint().apply { color = Color.BLUE })
        }
        WallpaperManager.getInstance(context).setBitmap(bitmap)
    }

    @Test
    fun withoutAccessThereIsNoBlur() {
        allowReading(false)
        setWallpaper()
        assertNull(WallpaperBlur.current(context))
    }

    @Test
    fun aCardGetsTheBlurredPieceItSitsOn() {
        allowReading(true)
        setWallpaper()
        val blurred = WallpaperBlur.current(context)
        assertNotNull(blurred)
        val screen = WallpaperBlur.screenSize(context)
        assertEquals(screen.width() / 8, blurred!!.bitmap.width)

        val left = blurred.behind(PixelRect(0, 0, screen.width() / 4, screen.height() / 4))!!
        val right = blurred.behind(PixelRect(screen.width() * 3 / 4, 0, screen.width(), screen.height() / 4))!!
        val l = left.getPixel(0, left.height / 2)
        val r = right.getPixel(right.width - 1, right.height / 2)
        assertTrue("left ${Integer.toHexString(l)}", Color.red(l) > 200 && Color.blue(l) < 60)
        assertTrue("right ${Integer.toHexString(r)}", Color.blue(r) > 200 && Color.red(r) < 60)
        // Across the edge in the middle the colors run into each other.
        val middle = blurred.bitmap.getPixel(blurred.bitmap.width / 2, blurred.bitmap.height / 2)
        assertTrue("middle ${Integer.toHexString(middle)}", Color.red(middle) in 40..215 && Color.blue(middle) in 40..215)

        // Read once: the next widget update gets the kept copy.
        assertSame(blurred, WallpaperBlur.current(context))
    }

    @Test
    fun aNewWallpaperIsReadAgain() {
        allowReading(true)
        setWallpaper()
        val first = WallpaperBlur.current(context)
        setWallpaper()
        assertTrue(first !== WallpaperBlur.current(context))
    }
}
