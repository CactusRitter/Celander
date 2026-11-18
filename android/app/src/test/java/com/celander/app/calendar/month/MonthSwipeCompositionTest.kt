package com.celander.app.calendar.month

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.celander.app.calendar.CalendarWindow
import com.celander.app.core.ui.Formats
import com.celander.app.settings.AppSettings
import com.ce.kit.ui.theme.CeTheme
import com.ce.kit.ui.theme.ThemeMode
import com.celander.app.settings.TimeFormat
import com.celander.app.testing.BERLIN
import com.celander.app.testing.occurrence
import com.celander.app.testing.timedEvent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Dragging the month's day list has to keep up with the finger at any frame rate: it may measure
 * and draw again, but must not compose the month (three pages of cells and entries) anew. And
 * however far the finger pulls it, the list's state follows when the finger lifts.
 */
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
@RunWith(RobolectricTestRunner::class)
class MonthSwipeCompositionTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 25)
    private val events = (12..25).map { day -> timedEvent(day.toLong(), LocalDateTime.of(2026, 10, day, 9, 30), 60).copy(title = "Standup").occurrence() }

    private var windowRequests = 0
    private var open by mutableStateOf(true)

    private fun showMonth() {
        compose.setContent {
            CeTheme(AppSettings(themeMode = ThemeMode.LIGHT, transparency = 0)) {
                MonthView(
                    selectedDate = today,
                    today = today,
                    now = today.atTime(10, 0).atZone(BERLIN).toInstant(),
                    zone = BERLIN,
                    firstDay = DayOfWeek.MONDAY,
                    showWeekNumbers = true,
                    panelOpen = open,
                    todayPulse = 0,
                    formats = Formats.of(LocalContext.current, TimeFormat.H24),
                    contentPadding = PaddingValues(),
                    // Every page and the list ask for their window while they compose.
                    windowFor = { start, end ->
                        windowRequests++
                        CalendarWindow(start, end, events, emptyList(), BERLIN)
                    },
                    onMonthChange = {},
                    onDayTap = { open = true },
                    onDayLongPress = {},
                    onClosePanel = { open = false },
                    onOpenPanel = { open = true },
                    onOpenOccurrence = {},
                    onOpenTask = {},
                    onToggleTask = { _, _ -> },
                    modifier = Modifier.testTag("month"),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun listShown(): Boolean = compose.onAllNodes(hasContentDescription("Close day list")).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun draggingTheDayListComposesNothingAnew() {
        showMonth()
        val listTop = compose.onNodeWithContentDescription("Close day list").fetchSemanticsNode().boundsInRoot.top
        val composed = windowRequests

        // Down on the grid and pull the list part of the way closed, without letting go.
        compose.onNodeWithTag("month").performTouchInput {
            down(Offset(width / 2f, height * 0.3f))
            repeat(12) { moveBy(Offset(0f, 12f)) }
        }
        compose.waitForIdle()

        val movedTop = compose.onNodeWithContentDescription("Close day list").fetchSemanticsNode().boundsInRoot.top
        assertTrue("the list follows the finger: $listTop → $movedTop", movedTop > listTop + 50f)
        assertEquals("nothing was composed while dragging", composed, windowRequests)
        compose.onNodeWithTag("month").performTouchInput { up() }
    }

    @Test
    fun pullingTheListShutFromInsideItClosesIt() {
        showMonth()
        val header = compose.onNodeWithContentDescription("Close day list").fetchSemanticsNode().boundsInRoot

        // Down on the list below its header and all the way past closed: the list shrinks to nothing
        // under the finger, but has to stay until the finger lifts to hand on the end of the drag.
        compose.onNodeWithTag("month").performTouchInput {
            down(Offset(width / 2f, header.bottom + 24f))
            repeat(40) { moveBy(Offset(0f, 20f)) }
            up()
        }
        compose.waitForIdle()

        assertFalse("the panel state closed with the list", open)
        assertFalse("the list is gone", listShown())

        // A day tapped afterwards opens the list again.
        compose.onNodeWithContentDescription("Tuesday, October 20", substring = true).performClick()
        compose.waitForIdle()
        assertTrue(open)
        assertTrue("the list is back", listShown())
    }
}
