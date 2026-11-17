package com.celander.app.calendar.device

import android.Manifest
import android.app.Application
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import com.celander.app.testing.BERLIN
import com.celander.app.testing.TestEnvironment
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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

/** Search in the phone's calendars lists a series at an occurrence that matches itself. */
@RunWith(RobolectricTestRunner::class)
class ProviderCalendarSearchTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var env: TestEnvironment
    private lateinit var provider: FakeCalendarProvider
    private lateinit var source: ProviderCalendarSource
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val now = LocalDateTime.of(2026, 10, 23, 10, 10).atZone(BERLIN).toInstant()

    private fun millis(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0).atZone(BERLIN).toInstant().toEpochMilli()

    @Before
    fun setUp() = runBlocking {
        env = TestEnvironment(folder.root)
        provider = Robolectric.buildContentProvider(FakeCalendarProvider::class.java).create(CalendarContract.AUTHORITY).get()
        provider.calendars += mutableMapOf<String, Any?>(
            "_id" to 1L, "account_name" to "me@gmail.com", "account_type" to "com.google", "_sync_id" to "sync1", "name" to "Work",
            "calendar_displayName" to "Work", "calendar_color" to 0xFF3F51B5.toInt(), "calendar_access_level" to 700,
            "ownerAccount" to "me@gmail.com", "visible" to 1, "isPrimary" to 1, "maxReminders" to 5, "allowedReminders" to "0,1,2", "sync_events" to 1,
        )
        // The fake lists every row as one instance: yesterday's of the series and tomorrow's, renamed.
        provider.events += row(20, "Stand-up", millis(22, 9)).apply { put("rrule", "FREQ=DAILY") }
        provider.events += row(21, "Retro", millis(24, 9)).apply {
            put("original_id", 20L)
            put("originalInstanceTime", millis(24, 9))
            put("originalAllDay", 0)
        }
        val access = DeviceCalendarAccess(ApplicationProvider.getApplicationContext())
        source = ProviderCalendarSource(ApplicationProvider.getApplicationContext(), env.settings, access, env.db.devicePrefsDao(), PendingDeviceDeletes(), scope, Dispatchers.Unconfined)
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        access.refresh()
        env.settings.update { it.copy(deviceCalendarsEnabled = true) }
        withTimeout(5_000) { source.calendars.first { it.isNotEmpty() } }
        Unit
    }

    @After
    fun tearDown() {
        scope.cancel()
        env.close()
    }

    private fun row(id: Long, title: String, start: Long) = mutableMapOf<String, Any?>(
        "_id" to id, "calendar_id" to 1L, "title" to title, "eventLocation" to "", "description" to "",
        "dtstart" to start, "dtend" to start + HOUR, "allDay" to 0, "eventTimezone" to "Europe/Berlin",
        "isOrganizer" to 1, "hasAttendeeData" to 0, "deleted" to 0,
    )

    @Test
    fun seriesHitSkipsAnOccurrenceRenamedAway() = runBlocking {
        val hit = source.search("stand", now, BERLIN) { true }.single()
        assertEquals("Stand-up", hit.title)
        assertEquals(LocalDate.of(2026, 10, 22), hit.timing.startDate(BERLIN))
    }

    @Test
    fun renamedOccurrenceIsFoundByItsOwnTitle() = runBlocking {
        val hit = source.search("retro", now, BERLIN) { true }.single()
        assertEquals("Retro", hit.title)
        assertEquals(LocalDate.of(2026, 10, 24), hit.timing.startDate(BERLIN))
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
