package com.celander.app.calendar

import androidx.lifecycle.SavedStateHandle
import com.celander.app.calendar.data.CalendarContentRepository
import com.celander.app.core.time.TimeSource
import com.celander.app.testing.BERLIN
import com.celander.app.testing.TestEnvironment
import com.celander.app.testing.timedEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The home screen's clock: today, the time zone and the selected day when the night or a flight passes. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeClockTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var env: TestEnvironment

    // The clock ticks on virtual time, moved on by the test.
    private val main = UnconfinedTestDispatcher()
    private val watcher = CoroutineScope(Dispatchers.Unconfined)

    /** A phone's clock the test sets: the time and the zone it is in. */
    private class PhoneClock(var instant: Instant, var zoneId: ZoneId) : TimeSource {
        override fun now(): Instant = instant
        override fun zone(): ZoneId = zoneId
    }

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(main)
        env = TestEnvironment(folder.root)
        env.calendars.ensureDefault("Personal")
    }

    @After
    fun tearDown() {
        watcher.cancel()
        env.close()
        Dispatchers.resetMain()
    }

    private fun home(clock: TimeSource, launches: LaunchRequests = LaunchRequests(), savedState: SavedStateHandle = SavedStateHandle()): HomeViewModel {
        val content = CalendarContentRepository(env.db.calendarDao(), env.db.eventDao(), env.db.exceptionDao(), env.db.taskDao(), Dispatchers.Default)
        return HomeViewModel(savedState, env.settings, content, env.tasks, env.calendars, env.samples, env.snackbars, launches, clock).also { watch(it) }
    }

    /** The screen watches the state while it is in the foreground. */
    private fun watch(home: HomeViewModel) {
        watcher.launch { home.uiState.collect { } }
    }

    /** The app goes to the background: the screen stops watching, and five seconds later the clock stops. */
    private fun background() {
        watcher.coroutineContext.cancelChildren()
        main.scheduler.advanceTimeBy(STOP_MILLIS)
        main.scheduler.runCurrent()
    }

    private suspend fun HomeViewModel.awaitState(condition: (HomeUiState) -> Boolean): HomeUiState =
        withTimeout(TIMEOUT) { uiState.filterNotNull().first { condition(it) } }

    /** The next tick of the clock, which reads the time and zone again. */
    private fun tick() {
        main.scheduler.advanceTimeBy(TICK_MILLIS)
        main.scheduler.runCurrent()
    }

    @Test
    fun atMidnightTheSelectionOnTodayMovesOn() = runBlocking {
        val clock = PhoneClock(LocalDateTime.of(2026, 10, 25, 23, 59).atZone(BERLIN).toInstant(), BERLIN)
        val home = home(clock)
        assertEquals(LocalDate.of(2026, 10, 25), home.awaitState { it.today == LocalDate.of(2026, 10, 25) }.selectedDate)

        clock.instant = LocalDateTime.of(2026, 10, 26, 0, 1).atZone(BERLIN).toInstant()
        tick()
        home.awaitState { it.today == LocalDate.of(2026, 10, 26) }
        assertEquals(LocalDate.of(2026, 10, 26), home.awaitState { it.selectedDate == LocalDate.of(2026, 10, 26) }.today)
    }

    @Test
    fun aDayPickedByTheUserStaysAtMidnight() = runBlocking {
        val clock = PhoneClock(LocalDateTime.of(2026, 10, 25, 23, 59).atZone(BERLIN).toInstant(), BERLIN)
        val home = home(clock)
        home.awaitState { it.today == LocalDate.of(2026, 10, 25) }
        home.selectDate(LocalDate.of(2026, 10, 20))
        home.awaitState { it.selectedDate == LocalDate.of(2026, 10, 20) }

        clock.instant = LocalDateTime.of(2026, 10, 26, 0, 1).atZone(BERLIN).toInstant()
        tick()
        assertEquals(LocalDate.of(2026, 10, 20), home.awaitState { it.today == LocalDate.of(2026, 10, 26) }.selectedDate)
    }

    @Test
    fun aNewTimeZoneMovesTodayAndTheWindows() = runBlocking {
        // 01:30 on Wednesday in Berlin is 19:30 on Tuesday in New York.
        val calendarId = env.calendars.first().single().id
        val id = env.events.create(timedEvent(0, LocalDateTime.of(2026, 10, 21, 1, 30), 30).copy(calendarId = calendarId))
        // 03:00 on Monday in Berlin is 22:00 on Sunday in New York.
        val clock = PhoneClock(Instant.parse("2026-10-26T02:00:00Z"), BERLIN)
        val home = home(clock)
        val before = home.awaitState { it.zone == BERLIN }
        assertEquals(LocalDate.of(2026, 10, 26), before.today)

        // The screen has shown last week in Berlin, so that window is cached.
        val start = LocalDate.of(2026, 10, 19)
        val berlinWeek = home.window(start, start.plusDays(7), before.zone)
        val inBerlin = withTimeout(TIMEOUT) { berlinWeek.filterNotNull().first() }
        assertEquals(listOf(id), inBerlin.day(LocalDate.of(2026, 10, 21)).timed.map { it.event.id })

        clock.zoneId = NEW_YORK
        tick()
        val after = home.awaitState { it.zone == NEW_YORK }
        assertEquals(LocalDate.of(2026, 10, 25), after.today)
        // The selection was on today and goes along.
        home.awaitState { it.selectedDate == LocalDate.of(2026, 10, 25) }

        val newYorkWeek = home.window(start, start.plusDays(7), after.zone)
        assertNotSame("a new zone fetches the week anew", berlinWeek, newYorkWeek)
        val window = withTimeout(TIMEOUT) { newYorkWeek.filterNotNull().first() }
        assertEquals("days are read in the new zone", NEW_YORK, window.zone)
        assertEquals(listOf(id), window.day(LocalDate.of(2026, 10, 20)).timed.map { it.event.id })
    }

    @Test
    fun aDayAskedForFromOutsideStaysAfterANightInTheBackground() = runBlocking {
        val monday = LocalDate.of(2026, 10, 26)
        val clock = PhoneClock(LocalDateTime.of(2026, 10, 26, 22, 0).atZone(BERLIN).toInstant(), BERLIN)
        val launches = LaunchRequests()
        val savedState = SavedStateHandle()
        val home = home(clock, launches, savedState)
        home.awaitState { it.today == monday && it.selectedDate == monday }

        // Overnight the app is in the background, so its clock does not see the new day.
        background()
        clock.instant = LocalDateTime.of(2026, 10, 27, 8, 0).atZone(BERLIN).toInstant()

        // In the morning Monday is tapped in the month widget; the request arrives before the screen watches again.
        launches.show(ViewRequest(HomeDestination.DAY, monday.toEpochDay()))
        main.scheduler.runCurrent()
        assertNull(launches.view.value)
        watch(home)

        assertEquals(HomeDestination.DAY, home.awaitState { it.today == monday.plusDays(1) }.destination)
        // Read where the selection is kept: a later state could still be on its way to the screen.
        assertEquals("the day asked for stays", monday.toEpochDay(), savedState.get<Long>("selected_day"))
    }

    private companion object {
        val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
        const val TICK_MILLIS = 30_000L

        /** A little longer than the screen's state is kept running without watchers. */
        const val STOP_MILLIS = 5_001L
        const val TIMEOUT = 5_000L
    }
}
