package com.celander.app.calendar.agenda

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import com.celander.app.R
import com.celander.app.calendar.CalendarWindow
import com.celander.app.core.ui.Formats
import com.celander.app.designsystem.CelanderTheme
import com.celander.app.settings.TimeFormat
import com.celander.app.testing.BERLIN
import com.celander.app.testing.occurrence
import com.celander.app.testing.timedEvent
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The agenda grows its range while scrolling; the list stays put and keeps its place meanwhile. */
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
@RunWith(RobolectricTestRunner::class)
class AgendaViewTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 25)

    /** The range the agenda starts with: 45 days back from today. */
    private val firstStart = today.minusDays(45)

    /**
     * One event on 1 August, before the first range; then one every day from 20 September to the
     * end of the first range, so that range is far from its ends at first.
     */
    private val events = listOf(timedEvent(1, LocalDateTime.of(2026, 8, 1, 9, 0), 60).occurrence()) +
        (0L until 155L).map { day -> timedEvent(100 + day, LocalDate.of(2026, 9, 20).plusDays(day).atTime(9, 30), 60).occurrence() }

    /** Whether the days before the first range have loaded; until then their window is null. */
    private var earlierLoaded by mutableStateOf(false)

    private val list = hasScrollToIndexAction()

    private fun scrollPosition(): Float = compose.onNode(list).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test
    fun rowsStayWhileEarlierDaysLoadAndTheListKeepsItsPlace() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val formats = Formats.of(context, TimeFormat.H24)
        // The days before the first event of the first range collapse into this line.
        val gap = context.getString(R.string.agenda_gap_until, formats.capitalize(LocalDate.of(2026, 9, 19).format(formats.dayMonthShort)))
        compose.setContent {
            CelanderTheme(darkTheme = false, accentHue = 240f, glassTransparency = 0f, reduceMotion = false) {
                AgendaView(
                    anchorDate = today,
                    today = today,
                    now = today.atTime(12, 0).atZone(BERLIN).toInstant(),
                    zone = BERLIN,
                    formats = formats,
                    contentPadding = PaddingValues(),
                    jumpRequest = 0,
                    windowFor = { start, end ->
                        val loaded = earlierLoaded || !start.isBefore(firstStart)
                        remember(start, end, loaded) { if (loaded) CalendarWindow(start, end, events, emptyList(), BERLIN) else null }
                    },
                    onFirstVisibleDate = {},
                    onTodayVisible = {},
                    onCreateOn = {},
                    onOpenOccurrence = {},
                    onOpenTask = {},
                    onToggleTask = { _, _ -> },
                    overdueTasks = emptyList(),
                )
            }
        }
        // The rows are built off the main thread.
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(list).fetchSemanticsNodes().isNotEmpty() }

        // At the top the agenda asks for 90 more days, which are still loading …
        compose.onNode(list).performScrollToIndex(0)
        compose.waitForIdle()
        // … meanwhile the rows it has stay on screen.
        val top = compose.onNodeWithText(gap).fetchSemanticsNode().boundsInRoot.top
        assertEquals(0f, scrollPosition(), 0.001f)

        earlierLoaded = true
        // Rows arrive above: the list is no longer at its top, the line seen stays where it was.
        compose.waitUntil(TIMEOUT) { scrollPosition() > 0f }
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasText(gap)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(top, compose.onNodeWithText(gap).fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
