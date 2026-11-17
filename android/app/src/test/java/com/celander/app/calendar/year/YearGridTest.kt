package com.celander.app.calendar.year

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class YearGridTest {
    private val gap = 8.dp
    private val minHeight = 156.dp

    @Test
    fun phoneInPortraitShowsThreeColumns() {
        assertEquals(3, YearGrid.columns(387.dp, 699.dp, gap, minHeight))
    }

    @Test
    fun phoneInLandscapeShowsSixColumns() {
        // 6 × 2 scrolls far less than 4 × 3 when the height cannot hold the minimum tile.
        assertEquals(6, YearGrid.columns(867.dp, 219.dp, gap, minHeight))
    }

    @Test
    fun tabletsPickTheFittingShape() {
        assertEquals(3, YearGrid.columns(744.dp, 1064.dp, gap, minHeight))
        assertEquals(6, YearGrid.columns(1224.dp, 584.dp, gap, minHeight))
        // A squarer window, e.g. an unfolded foldable in landscape.
        assertEquals(4, YearGrid.columns(860.dp, 560.dp, gap, minHeight))
    }

    @Test
    fun textGrowsWithTheTileAndStopsAtTheLimit() {
        assertEquals(1f, YearGrid.textScale(124.dp, 168.dp), 0.001f)
        assertEquals(1f, YearGrid.textScale(110.dp, 200.dp), 0.001f)
        assertEquals(1.5f, YearGrid.textScale(186.dp, 300.dp), 0.01f)
        assertEquals(1.6f, YearGrid.textScale(400.dp, 400.dp), 0.001f)
    }
}
