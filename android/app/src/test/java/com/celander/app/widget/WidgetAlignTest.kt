package com.celander.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** One tap on a widget tells Celander where its card sits, so the blur behind it fits. */
@RunWith(RobolectricTestRunner::class)
class WidgetAlignTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val card = DpSize(320.dp, 160.dp)
    private val placements get() = WidgetPlacements.of(context)

    @Before
    fun setUp() = placements.clear()

    @Test
    fun aTapStoresWhereTheCardSitsForItsSize() {
        val tap = shadowOf(WidgetAlignReceiver.tap(context, 7, card)).savedIntent
        val before = WallpaperBlur.revision.value
        WidgetAlignReceiver().onReceive(context, Intent(tap).apply { sourceBounds = Rect(40, 500, 1040, 1000) })
        // Running widget sessions only redraw on observed state.
        assertEquals(before + 1, WallpaperBlur.revision.value)

        val screen = WallpaperBlur.screenSize(context)
        assertEquals(Placement(PixelRect(40, 500, 1040, 1000), screen.width(), screen.height()), placements.get(7, card))
        // Another size of the same widget (the other orientation) still needs its own tap.
        assertNull(placements.get(7, DpSize(600.dp, 120.dp)))
    }

    @Test
    fun aTapWithoutBoundsChangesNothing() {
        WidgetAlignReceiver().onReceive(context, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 7))
        assertNull(placements.get(7, card))
    }

    @Test
    fun placementsSurviveTheirOwnFormatAndAreForgottenOnRealign() {
        val placement = Placement(PixelRect(1, 2, 3, 4), 1080, 2400)
        assertEquals(placement, Placement.parse(placement.encode()))
        assertNull(Placement.parse("1,2,3"))
        placements.set(3, card, placement)
        placements.clear()
        assertNull(placements.get(3, card))
    }
}
