package com.celander.app.event

import androidx.lifecycle.SavedStateHandle
import com.celander.app.calendar.EditorRequest
import com.celander.app.core.places.PlaceSuggestion
import com.celander.app.testing.TestEnvironment
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Place suggestions in the editor: only from three characters, and a pick ends the search. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaceSuggestionsFlowTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var env: TestEnvironment
    // The editor's pause before asking runs on virtual time, moved on by the test.
    private val main = UnconfinedTestDispatcher()
    private val asked = mutableListOf<String>()
    private val marienplatz = PlaceSuggestion("Marienplatz", "80331 München")

    private val places = object : PlaceSearch {
        override suspend fun suggest(query: String, language: String): List<PlaceSuggestion> {
            asked += query
            return listOf(marienplatz)
        }
    }

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(main)
        env = TestEnvironment(folder.root)
        env.calendars.ensureDefault("Personal")
    }

    @After
    fun tearDown() {
        env.close()
        Dispatchers.resetMain()
    }

    @Test
    fun suggestionsStartAtThreeCharactersAndAPickTakesThePlace() = runBlocking {
        val editor = EditorViewModel(SavedStateHandle(), env.events, env.tasks, env.calendars, env.settings, SeriesEditor(env.events), env.snackbars, env.time, places)
        editor.open(EditorRequest.NewEvent(LocalDate.of(2026, 10, 25).toEpochDay()))
        withTimeout(5_000) { editor.state.first { it.draft != null } }
        val watcher = CoroutineScope(Dispatchers.Unconfined).apply { launch { editor.placeSuggestions.collect { } } }

        editor.setLocation("Ma")
        main.scheduler.advanceTimeBy(1_000)
        assertTrue(asked.isEmpty())

        editor.setLocation("Mar")
        main.scheduler.advanceTimeBy(100)
        editor.setLocation("Mari")
        main.scheduler.advanceTimeBy(1_000)
        assertEquals(listOf(marienplatz), withTimeout(5_000) { editor.placeSuggestions.first { it.isNotEmpty() } })
        // Typing on within the pause asks only once, for the latest text.
        assertEquals(listOf("Mari"), asked)

        editor.pickPlace(marienplatz)
        assertEquals("Marienplatz, 80331 München", editor.state.value.draft!!.location)
        assertTrue(withTimeout(5_000) { editor.placeSuggestions.first { it.isEmpty() } }.isEmpty())
        watcher.cancel()
    }
}
