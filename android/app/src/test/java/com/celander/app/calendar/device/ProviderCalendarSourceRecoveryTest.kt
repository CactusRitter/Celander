package com.celander.app.calendar.device

import android.Manifest
import android.app.Application
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import com.celander.app.core.model.SourceIds
import com.celander.app.core.recurrence.SeriesScope
import com.celander.app.testing.BERLIN
import com.celander.app.testing.TestEnvironment
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** A provider query that fails once leaves the phone's calendars empty only until the next change. */
@RunWith(RobolectricTestRunner::class)
class ProviderCalendarSourceRecoveryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var env: TestEnvironment
    private lateinit var provider: FakeCalendarProvider
    private lateinit var source: ProviderCalendarSource
    private val pending = PendingDeviceDeletes()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val start = LocalDateTime.of(2026, 10, 23, 9, 0).atZone(BERLIN).toInstant().toEpochMilli()

    /** Hides an event that does not exist, which only counts as a change. */
    private val unrelatedDelete = PendingDelete(SourceIds.device(99), 0, SeriesScope.ALL)

    @Before
    fun setUp() = runBlocking {
        env = TestEnvironment(folder.root)
        provider = Robolectric.buildContentProvider(FakeCalendarProvider::class.java).create(CalendarContract.AUTHORITY).get()
        provider.calendars += mutableMapOf<String, Any?>(
            "_id" to 1L, "account_name" to "me@gmail.com", "account_type" to "com.google", "_sync_id" to "sync1", "name" to "Work",
            "calendar_displayName" to "Work", "calendar_color" to 0xFF3F51B5.toInt(), "calendar_access_level" to 700,
            "ownerAccount" to "me@gmail.com", "visible" to 1, "isPrimary" to 1, "maxReminders" to 5, "allowedReminders" to "0,1,2", "sync_events" to 1,
        )
        provider.events += mutableMapOf<String, Any?>(
            "_id" to 10L, "calendar_id" to 1L, "title" to "Planning", "eventLocation" to "", "description" to "",
            "dtstart" to start, "dtend" to start + HOUR, "allDay" to 0, "eventTimezone" to "Europe/Berlin",
            "isOrganizer" to 1, "hasAttendeeData" to 0, "deleted" to 0,
        )
        val access = DeviceCalendarAccess(ApplicationProvider.getApplicationContext())
        source = ProviderCalendarSource(ApplicationProvider.getApplicationContext(), env.settings, access, env.db.devicePrefsDao(), pending, scope, Dispatchers.Unconfined)
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        access.refresh()
        env.settings.update { it.copy(deviceCalendarsEnabled = true) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        env.close()
    }

    private suspend fun awaitFailedQuery() = withTimeout(5_000) {
        while (provider.failingQueries.get() > 0) delay(10)
    }

    @Test
    fun calendarsComeBackAfterAFailedRead() = runBlocking {
        provider.failingQueries.set(1)
        val calendars = async { withTimeout(5_000) { source.calendars.first { it.isNotEmpty() } } }
        awaitFailedQuery()

        pending.add(unrelatedDelete)

        assertEquals(listOf("Work"), calendars.await().map { it.name })
    }

    @Test
    fun occurrencesComeBackAfterAFailedRead() = runBlocking {
        withTimeout(5_000) { source.calendars.first { it.isNotEmpty() } }
        provider.failingQueries.set(1)
        val window = async {
            withTimeout(5_000) { source.occurrences(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26), BERLIN).first { it.isNotEmpty() } }
        }
        awaitFailedQuery()

        pending.add(unrelatedDelete)

        assertEquals(listOf("Planning"), window.await().map { it.title })
    }

    @Test
    fun openDetailsSurviveAFailedRead() = runBlocking {
        withTimeout(5_000) { source.calendars.first { it.isNotEmpty() } }
        provider.failingQueries.set(1)
        val details = async { withTimeout(5_000) { source.observeEvent(SourceIds.device(10)).first { it != null } } }
        awaitFailedQuery()

        pending.add(unrelatedDelete)

        // The failed read neither ended the flow (which crashed the details) nor reported the event as gone.
        assertEquals("Planning", details.await()!!.event.title)
    }

    @Test
    fun aFailedReadDoesNotReportTheEventAsGone() = runBlocking {
        withTimeout(5_000) { source.calendars.first { it.isNotEmpty() } }
        val seen = Collections.synchronizedList(mutableListOf<String?>())
        val collector = launch(Dispatchers.Unconfined) { source.observeEvent(SourceIds.device(10)).collect { seen += it?.event?.title } }
        withTimeout(5_000) { while (seen.isEmpty()) delay(10) }
        provider.failingQueries.set(1)

        pending.add(unrelatedDelete)
        awaitFailedQuery()
        pending.add(unrelatedDelete.copy(eventId = SourceIds.device(98)))
        withTimeout(5_000) { while (seen.size < 2) delay(10) }
        collector.cancel()

        // Read before and after the failure; nothing in between, in particular no null.
        assertEquals(listOf("Planning"), seen.distinct())
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
