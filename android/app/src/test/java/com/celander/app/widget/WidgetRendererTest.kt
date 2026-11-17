package com.celander.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.compose.ui.unit.dp
import com.celander.app.calendar.CalendarWindow
import com.celander.app.core.model.TaskItem
import com.celander.app.testing.BERLIN
import com.celander.app.testing.allDayEvent
import com.celander.app.testing.occurrence
import com.celander.app.testing.taskItem
import com.celander.app.testing.timedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * Renders the drawn widgets with real graphics. Besides the checks, the pictures land in
 * build/widget-renders for a look (dark and light card, as the launcher would tint them).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetRendererTest {
    private val today = LocalDate.of(2026, 10, 23)
    private val density = 2.625f
    private val paints = WidgetPaints(density, fontScale = 1f)

    private fun window(start: LocalDate, end: LocalDate): CalendarWindow {
        val events = listOf(
            allDayEvent(1, LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 24)).copy(title = "Conference"),
            allDayEvent(2, LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 1)).copy(title = "Weekend at the coast"),
        ) + (1..6).map { timedEvent(10L + it, LocalDateTime.of(2026, 10, 23, 7 + it, 0), 60).copy(title = "Meeting $it") } +
            (12..16).map { timedEvent(30L + it, LocalDateTime.of(2026, 10, it, 9, 30), 15).copy(title = "Team stand-up") }
        val tasks: List<TaskItem> = listOf(taskItem(100, today), taskItem(101, LocalDate.of(2026, 10, 26)))
        return CalendarWindow(start, end, events.map { it.occurrence() }, tasks, BERLIN)
    }

    private fun monthSpec(): MonthGridSpec {
        val start = LocalDate.of(2026, 9, 28)
        return MonthGridSpec(
            month = YearMonth.of(2026, 10),
            firstDay = DayOfWeek.MONDAY,
            showWeekNumbers = true,
            today = today,
            window = window(start, start.plusWeeks(5)),
            zone = BERLIN,
            weekdayLabels = listOf("MO", "DI", "MI", "DO", "FR", "SA", "SO"),
            untitled = "Untitled",
        )
    }

    @Test
    fun monthGridRendersAtEverySize() {
        for ((w, h) in listOf(280 to 220, 300 to 285, 360 to 420, 180 to 180, 120 to 100)) {
            val layers = MonthGridRenderer.render(monthSpec(), (w * density).toInt(), (h * density).toInt(), paints)
            save(layers, "month-${w}x$h")
            assertTrue("ink drawn at $w x $h", layers.ink.hasAlpha() && alphaSum(layers.ink) > 0)
        }
    }

    @Test
    fun todayHasAnInvertedBadge() {
        val w = (300 * density).toInt()
        val h = (285 * density).toInt()
        val layers = MonthGridRenderer.render(monthSpec(), w, h, paints)
        // Week 4 (Oct 19-25), Friday column: the badge fills most of a small square above the number.
        val headerH = MonthGridRenderer.HEADER_DP * density
        val weekColW = MonthGridRenderer.WEEK_COLUMN_DP * density
        val rowH = (h - headerH) / MonthGridRenderer.weeks(YearMonth.of(2026, 10), DayOfWeek.MONDAY)
        val colW = (w - weekColW) / 7f
        val cellLeft = (weekColW + 4 * colW).toInt()
        val top = (headerH + 3 * rowH).toInt()
        val inked = (cellLeft until (cellLeft + colW).toInt()).sumOf { x -> (top until top + (rowH / 3).toInt()).count { y -> Color.alpha(layers.ink.getPixel(x, y)) > 200 } }
        assertTrue("badge pixels: $inked", inked > (colW * rowH / 3) * 0.15)
    }

    @Test
    fun yearGridRendersBothShapes() {
        val start = LocalDate.of(2026, 1, 1)
        val spec = YearGridSpec(2026, today, DayOfWeek.MONDAY, window(start, start.plusYears(1)), listOf("Jan", "Feb", "Mär", "Apr", "Mai", "Jun", "Jul", "Aug", "Sep", "Okt", "Nov", "Dez"))
        for ((w, h) in listOf(300 to 360, 400 to 260, 180 to 180)) {
            val layers = YearGridRenderer.render(spec, (w * density).toInt(), (h * density).toInt(), paints)
            save(layers, "year-${w}x$h")
            assertTrue(alphaSum(layers.accent!!) > 0)
        }
        assertEquals(3, YearGridRenderer.columns(300, 360))
        assertTrue(MonthGridRenderer.showsWeekNumbers(enabled = true, widthDp = 300f))
        assertTrue(!MonthGridRenderer.showsWeekNumbers(enabled = true, widthDp = 180f))
        assertEquals(4, YearGridRenderer.columns(400, 260))
    }

    private fun alphaSum(bitmap: Bitmap): Long {
        var sum = 0L
        for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) sum += Color.alpha(bitmap.getPixel(x, y))
        return sum
    }

    /** Composes the layers like the launcher does, on a dark and a light card. */
    private fun save(layers: WidgetLayers, name: String) {
        val dir = File("build/widget-renders").apply { mkdirs() }
        for ((suffix, card, ink) in listOf(Triple("dark", 0xFF1B1D24.toInt(), Color.WHITE), Triple("light", 0xFFF4F4F8.toInt(), 0xFF15171C.toInt()))) {
            val out = Bitmap.createBitmap(layers.width, layers.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(card)
            canvas.drawBitmap(layers.color, 0f, 0f, null)
            canvas.drawBitmap(layers.ink, 0f, 0f, Paint().apply { colorFilter = PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN) })
            canvas.drawBitmap(layers.sunday, 0f, 0f, Paint().apply { colorFilter = PorterDuffColorFilter(0xFFE5534B.toInt(), PorterDuff.Mode.SRC_IN) })
            layers.accent?.let { canvas.drawBitmap(it, 0f, 0f, Paint().apply { colorFilter = PorterDuffColorFilter(0xFF9BA3FF.toInt(), PorterDuff.Mode.SRC_IN) }) }
            File(dir, "$name-$suffix.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun drawnTasksGetTapAreasInTheirCell() {
        val spec = monthSpec()
        val h = (285 * density).toInt()
        val layers = MonthGridRenderer.render(spec, (300 * density).toInt(), h, paints)
        // Task 101 is due on Monday, October 26: week row 4, column 0.
        val target = layers.tasks.single { it.taskId == 101L }
        assertEquals(4, target.row)
        assertEquals(0, target.col)
        val rowH = (h - MonthGridRenderer.HEADER_DP * density) / MonthGridRenderer.weeks(YearMonth.of(2026, 10), DayOfWeek.MONDAY)
        assertTrue("inside the cell: $target", target.top > 0f && target.top + target.height <= rowH)
    }

    @Test
    fun gridsDrawAtScreenDensityOnPhonesAndShrinkOnlyWhenMemoryRunsOut() {
        val phone = PictureMemory(screenPixels = 1080L * 2400, sizes = emptyList())
        val month = gridCanvas(340.dp, 330.dp, 2.625f, phone, withAccent = false)
        assertEquals(2.625f, month.density)
        assertEquals((340 * 2.625f).toInt(), month.width)
        // A whole-screen widget on a tablet: scaled, and two pictures of all layers stay under 1.5 screens of ARGB.
        val tablet = PictureMemory(screenPixels = 2560L * 1600, sizes = emptyList())
        val year = gridCanvas(1280.dp, 800.dp, 2f, tablet, withAccent = true)
        assertTrue(year.density < 2f)
        val bytes = 2L * year.width * year.height * 7
        assertTrue(bytes <= 2560L * 1600 * 6)
    }
}
