package com.celander.app.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The limits that keep widget updates within what the system accepts. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetLimitsTest {
    private val density = 2.625f

    /**
     * Bytes of all pictures a month widget sends: one per reported size, a color layer and two masks
     * each, plus the glass of its card.
     */
    private fun monthBytes(memory: PictureMemory): Long = memory.sizes.sumOf { size ->
        val canvas = gridCanvas(size.width - 20.dp, size.height - 60.dp, density, memory, withAccent = false)
        val glass = WidgetGlass.render(size, density, memory)
        canvas.width.toLong() * canvas.height * 6 + glass.rim.width.toLong() * glass.rim.height + glass.shadow.width.toLong() * glass.shadow.height
    }

    @Test
    fun aFoldableFitsAllFourSizesIntoTheMemoryOfItsOuterScreen() {
        // Unfolded 2208 x 1840; the system may have measured the outer screen (1080 x 2092) at boot.
        val sizes = listOf(DpSize(360.dp, 400.dp), DpSize(700.dp, 230.dp), DpSize(400.dp, 420.dp), DpSize(760.dp, 300.dp))
        val memory = PictureMemory(limitScreenPixels(2208L * 1840, foldable = true), sizes)
        assertTrue(monthBytes(memory) <= 1080L * 2092 * 6)
        assertTrue(gridCanvas(400.dp, 360.dp, density, memory, withAccent = false).density < density)
    }

    @Test
    fun phonesKeepScreenDensityForPortraitAndLandscape() {
        val memory = PictureMemory(1080L * 2400, listOf(DpSize(340.dp, 330.dp), DpSize(680.dp, 200.dp)))
        val canvas = gridCanvas(320.dp, 270.dp, density, memory, withAccent = false)
        assertEquals(density, canvas.density)
        assertTrue(monthBytes(memory) <= 1080L * 2400 * 6)
    }

    @Test
    fun theMemoryIsSharedByAsManyPicturesAsSizesAreReported() {
        val screen = 2560L * 1600
        val one = pictureScale(1280f * 800 * 4, 2f, PictureMemory(screen, listOf(DpSize(1280.dp, 800.dp))), withAccent = false)
        val two = pictureScale(1280f * 800 * 4, 2f, PictureMemory(screen, listOf(DpSize(1280.dp, 800.dp), DpSize(800.dp, 1280.dp))), withAccent = false)
        val unknown = pictureScale(1280f * 800 * 4, 2f, PictureMemory(screen, emptyList()), withAccent = false)
        assertTrue("one $one, two $two", one < 1f && two < one)
        // Without reported sizes, portrait and landscape are assumed.
        assertEquals(two, unknown, 0.001f)
    }

    @Test
    fun onlyFoldablesCountWithASmallerScreen() {
        assertEquals(1_800_000L, limitScreenPixels(2208L * 1840, foldable = true))
        assertEquals(1_000_000L, limitScreenPixels(1_000_000L, foldable = true))
        assertEquals(1080L * 2400, limitScreenPixels(1080L * 2400, foldable = false))
    }

    @Test
    fun longListsAreCutAndCountOnlyEntriesAsHidden() {
        val rows = listOf("Monday", "a", "b", "Tuesday", "c", "d", "Wednesday", "e")
        val isEntry = { row: String -> row.length == 1 }
        // A heading at the cut goes too, without its entries.
        assertEquals(CappedRows(listOf("Monday", "a", "b"), hidden = 3), capRows(rows, 4, isEntry))
        assertEquals(CappedRows(listOf("Monday", "a", "b", "Tuesday", "c"), hidden = 2), capRows(rows, 5, isEntry))
        assertEquals(CappedRows(rows, hidden = 0), capRows(rows, rows.size, isEntry))
    }

    @Test
    fun everyReportedSizeGetsItsShareOfTheRows() {
        assertEquals(MAX_LIST_ROWS, listRowLimit(0))
        assertEquals(MAX_LIST_ROWS, listRowLimit(1))
        assertEquals(MAX_LIST_ROWS / 2, listRowLimit(2))
        assertEquals(MAX_LIST_ROWS / 4, listRowLimit(4))
        assertEquals(10, listRowLimit(12))
    }
}
