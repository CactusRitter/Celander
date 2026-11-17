package com.celander.app

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.celander.app.calendar.data.CalendarsRepository
import com.celander.app.core.di.TimeModule
import com.celander.app.core.time.TimeSource
import com.celander.app.settings.SettingsRepository
import com.celander.app.testing.BERLIN
import com.celander.app.testing.FixedTimeSource
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import javax.inject.Inject

/** The main flows through the real app: create, edit, delete with undo, switch views, search. */
@HiltAndroidTest
@UninstallModules(TimeModule::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h891dp")
@RunWith(RobolectricTestRunner::class)
class AppFlowTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    /** Friday, 23 October 2026, 10:10 in Berlin. */
    @BindValue @JvmField
    val time: TimeSource = FixedTimeSource(LocalDateTime.of(2026, 10, 23, 10, 10).atZone(BERLIN).toInstant())

    @Inject lateinit var calendars: CalendarsRepository

    @Inject lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking {
            // The test application does not run CelanderApp's first-start setup.
            calendars.ensureDefault("Personal")
            settings.update { it.copy(transparency = 0, reduceMotion = true) }
        }
    }

    private fun waitFor(description: String) {
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasContentDescription(description, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun createEvent(title: String) {
        waitFor("New event or task")
        compose.onNodeWithContentDescription("New event or task").performClick()
        waitFor("Title")
        compose.onNodeWithContentDescription("Title").performTextInput(title)
        compose.onNodeWithText("Save").performClick()
        // The month cell of the selected day merges its entries into one description.
        waitFor("Friday, October 23, Today, 1 entry")
    }

    private fun openEvent(title: String) {
        compose.onNodeWithContentDescription("Friday, October 23", substring = true).performClick()
        // The event starts at the next half hour after 10:10.
        waitFor("$title, 10:30")
        compose.onNodeWithContentDescription("$title, 10:30", substring = true).performClick()
        waitForText("Delete")
    }

    @Test
    fun createEditDeleteAndUndo() {
        createEvent("Dentist")

        openEvent("Dentist")
        compose.onNodeWithText("Edit").performClick()
        waitFor("Title")
        compose.onNodeWithContentDescription("Title").performTextClearance()
        compose.onNodeWithContentDescription("Title").performTextInput("Dentist checkup")
        compose.onNodeWithText("Save").performClick()
        waitFor("Dentist checkup, 10:30")

        compose.onNodeWithContentDescription("Dentist checkup, 10:30", substring = true).performClick()
        waitForText("Delete")
        compose.onNodeWithText("Delete").performClick()
        waitForText("Undo")
        compose.onNodeWithText("Undo").performClick()
        waitFor("Dentist checkup, 10:30")
    }

    @Test
    fun switchesBetweenAllViews() {
        waitFor("Year")
        compose.onNodeWithContentDescription("Year").performClick()
        waitForText("January")
        compose.onNodeWithContentDescription("Agenda").performClick()
        waitForText("Today")
        compose.onNodeWithContentDescription("Tasks").performClick()
        waitFor("Add a task")
        compose.onNodeWithContentDescription("Day").performClick()
        // The heading names the date; the pill shows icons only.
        waitForText("October 23, 2026")
    }

    @Test
    fun searchFindsAnEventWhileTyping() {
        createEvent("Lunch with Anna")
        compose.onNodeWithContentDescription("Search").performClick()
        waitFor("Search events and tasks")
        compose.onNodeWithContentDescription("Search events and tasks").performTextInput("anna")
        waitForText("1 result")
        waitFor("Lunch with Anna, 10:30")
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
