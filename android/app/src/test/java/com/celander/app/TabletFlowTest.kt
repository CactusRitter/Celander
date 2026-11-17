package com.celander.app

import android.view.KeyEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.celander.app.calendar.data.CalendarsRepository
import com.celander.app.core.di.TimeModule
import com.celander.app.core.time.TimeSource
import com.celander.app.event.EventRepository
import com.celander.app.settings.SettingsRepository
import com.celander.app.testing.BERLIN
import com.celander.app.testing.FixedTimeSource
import com.celander.app.testing.allDayEvent
import com.celander.app.testing.timedEvent
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

/** A landscape tablet: panes side by side, the year in two rows, the week in columns, floating sheets, keyboard shortcuts. */
@HiltAndroidTest
@UninstallModules(TimeModule::class)
@Config(application = HiltTestApplication::class, qualifiers = "w1280dp-h800dp-land")
@RunWith(RobolectricTestRunner::class)
class TabletFlowTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    /** Friday, 23 October 2026, 10:10 in Berlin. */
    @BindValue @JvmField
    val time: TimeSource = FixedTimeSource(LocalDateTime.of(2026, 10, 23, 10, 10).atZone(BERLIN).toInstant())

    @Inject lateinit var calendars: CalendarsRepository

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var events: EventRepository

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking {
            calendars.ensureDefault("Personal")
            settings.update { it.copy(glassTransparency = 0f, reduceMotion = true) }
        }
    }

    private fun waitFor(description: String) {
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasContentDescription(description, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Bounds (px) of the node with [text] on the visible page; the pager keeps its neighbours composed, unplaced. */
    private fun onScreen(text: String): Rect {
        val width = compose.activity.window.decorView.width
        return compose.onAllNodes(hasText(text)).fetchSemanticsNodes().map { it.boundsInRoot }
            .single { it.width > 0f && it.left >= 0f && it.right <= width }
    }

    private fun pressCtrl(keyCode: Int) {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
            compose.activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
        }
        compose.waitForIdle()
    }

    @Test
    fun dayListOpensBesideTheMonth() {
        waitFor("Friday, October 23")
        compose.onNodeWithContentDescription("Friday, October 23", substring = true).performClick()
        waitFor("Close day list")
        val cell = compose.onNodeWithContentDescription("Friday, October 23", substring = true).getBoundsInRoot()
        val panel = compose.onNodeWithContentDescription("Close day list").getBoundsInRoot()
        assertTrue("day list right of the grid", panel.left > cell.right)
        // The grid keeps most of the width; the list stops at its readable width.
        assertTrue("grid keeps the larger part", cell.right > 640.dp)
    }

    @Test
    fun yearShowsTwoRowsOfSix() {
        waitFor("Year")
        compose.onNodeWithContentDescription("Year").performClick()
        waitForText("January")
        val january = onScreen("January")
        val june = onScreen("June")
        val july = onScreen("July")
        assertEquals("June in the first row", january.top, june.top)
        assertTrue("July starts the second row", july.top > january.bottom)
    }

    @Test
    fun dayViewShowsTheWeekInColumns() {
        runBlocking {
            val calendar = calendars.first().single().id
            events.create(timedEvent(0, LocalDateTime.of(2026, 10, 19, 11, 0), 60).copy(calendarId = calendar, title = "Planning"))
            events.create(timedEvent(0, LocalDateTime.of(2026, 10, 22, 11, 0), 60).copy(calendarId = calendar, title = "Review"))
            events.create(allDayEvent(0, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 22)).copy(calendarId = calendar, title = "Trip"))
        }
        waitFor("Day")
        compose.onNodeWithContentDescription("Day").performClick()
        waitForText("October 18")
        waitFor("Planning")
        val planning = compose.onNodeWithContentDescription("Planning", substring = true).getBoundsInRoot()
        val review = compose.onNodeWithContentDescription("Review", substring = true).getBoundsInRoot()
        val trip = compose.onNodeWithContentDescription("Trip", substring = true).getBoundsInRoot()
        // Monday and Thursday side by side at the same time; the trip runs across three columns.
        assertEquals("same time, same height", planning.top, review.top)
        assertTrue("Thursday right of Monday", review.left > planning.right)
        assertTrue("trip spans its days", trip.right - trip.left > (planning.right - planning.left) * 2)
        assertTrue("all-day band above the timeline", trip.bottom <= planning.top)

        // A swipe pages to the next week.
        compose.onNodeWithContentDescription("Planning", substring = true).performTouchInput { swipeLeft(startX = right, endX = left - 600f) }
        waitForText("October 25")
    }

    @Test
    fun keyboardShortcutsSwitchViewsAndOpenTheEditor() {
        waitFor("New event or task")
        pressCtrl(KeyEvent.KEYCODE_4)
        waitForText("Today")
        // The day view shows the whole week here, Sunday to Saturday; the arrows page by weeks.
        pressCtrl(KeyEvent.KEYCODE_3)
        waitForText("October 18")
        pressCtrl(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitForText("October 25")

        pressCtrl(KeyEvent.KEYCODE_N)
        waitFor("Title")
        // The editor floats as a card of form width in the middle of the window.
        val title = compose.onNodeWithContentDescription("Title").getBoundsInRoot()
        assertTrue("editor no wider than a form", title.right - title.left <= 640.dp)
        assertTrue("editor centred", title.left > 300.dp)
        // Behind an open sheet, shortcuts leave the keys to the fields.
        pressCtrl(KeyEvent.KEYCODE_F)
        assertTrue(compose.onAllNodes(hasContentDescription("Search events and tasks")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("Title").performTextInput("Review")
        compose.onNodeWithText("Save").performClick()
        waitFor("Review")
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
