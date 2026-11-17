package com.celander.app.widget

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.util.SizeF
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The sizes a launcher reports, each of which gets pictures and a list of its own. */
@RunWith(RobolectricTestRunner::class)
class WidgetSizesTest {
    @Test
    fun foldablesReportEverySizeOnce() {
        val sizes = arrayListOf(SizeF(360f, 400f), SizeF(700f, 230f), SizeF(400f, 420f), SizeF(760f, 300f), SizeF(360f, 400f))
        val options = Bundle().apply { putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizes) }
        val reported = reportedSizes(options)
        assertEquals(4, reported.size)
        assertEquals(DpSize(360.dp, 400.dp), reported.first())
    }

    @Test
    fun withoutAListTheSizesArePortraitAndLandscape() {
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 600)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 400)
        }
        assertEquals(listOf(DpSize(300.dp, 400.dp), DpSize(600.dp, 200.dp)), reportedSizes(options))
    }

    @Test
    fun nothingReportedMeansNoSizes() {
        assertEquals(emptyList<DpSize>(), reportedSizes(Bundle()))
    }
}
