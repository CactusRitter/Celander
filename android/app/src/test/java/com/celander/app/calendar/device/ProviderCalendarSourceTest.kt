package com.celander.app.calendar.device

import android.Manifest
import android.app.Application
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import com.celander.app.core.model.CalendarSource
import com.celander.app.core.model.EventTiming
import com.celander.app.core.model.ReadOnlyReason
import com.celander.app.core.model.SourceIds
import com.celander.app.core.recurrence.SeriesScope
import com.celander.app.testing.BERLIN
import com.celander.app.testing.TestEnvironment
import com.celander.app.testing.timedEvent
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** What Celander asks the calendar provider and how it maps and writes the rows. */
@RunWith(RobolectricTestRunner::class)
class ProviderCalendarSourceTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var env: TestEnvironment
    private lateinit var provider: FakeCalendarProvider
    private lateinit var access: DeviceCalendarAccess
    private lateinit var source: ProviderCalendarSource
    private lateinit var store: ProviderEventStore
    private val pending = PendingDeviceDeletes()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val start = LocalDateTime.of(2026, 10, 23, 9, 0).atZone(BERLIN).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        env = TestEnvironment(folder.root)
        provider = Robolectric.buildContentProvider(FakeCalendarProvider::class.java).create(CalendarContract.AUTHORITY).get()
        provider.calendars += calendar(1, "Work", accessLevel = 700, primary = 1)
        provider.calendars += calendar(2, "Holidays", accessLevel = 200)
        provider.events += event(10, calendarId = 1, title = "Planning")
        provider.events += event(11, calendarId = 2, title = "Holiday").apply { put("allDay", 1); put("dtstart", HOLIDAY * DAY); put("dtend", (HOLIDAY + 1) * DAY); put("eventTimezone", "UTC") }
        provider.events += event(12, calendarId = 1, title = "Lunch with Ann").apply { put("isOrganizer", 0); put("organizer", "ann@example.com"); put("hasAttendeeData", 1) }
        access = DeviceCalendarAccess(ApplicationProvider.getApplicationContext())
        source = ProviderCalendarSource(ApplicationProvider.getApplicationContext(), env.settings, access, env.db.devicePrefsDao(), pending, scope, Dispatchers.Unconfined)
        store = ProviderEventStore(ApplicationProvider.getApplicationContext(), source, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        env.close()
    }

    private fun turnOn() = runBlocking {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        access.refresh()
        env.settings.update { it.copy(deviceCalendarsEnabled = true) }
    }

    private fun calendar(id: Long, name: String, accessLevel: Int, primary: Int = 0) = mutableMapOf<String, Any?>(
        "_id" to id, "account_name" to "me@gmail.com", "account_type" to "com.google", "_sync_id" to "sync$id", "name" to name,
        "calendar_displayName" to name, "calendar_color" to 0xFF3F51B5.toInt(), "calendar_access_level" to accessLevel,
        "ownerAccount" to "me@gmail.com", "visible" to 1, "isPrimary" to primary, "maxReminders" to 5, "allowedReminders" to "0,1,2", "sync_events" to 1,
    )

    private fun event(id: Long, calendarId: Long, title: String) = mutableMapOf<String, Any?>(
        "_id" to id, "calendar_id" to calendarId, "title" to title, "eventLocation" to "", "description" to "",
        "dtstart" to start, "dtend" to start + HOUR, "allDay" to 0, "eventTimezone" to "Europe/Berlin",
        "isOrganizer" to 1, "hasAttendeeData" to 0, "deleted" to 0,
    )

    private suspend fun window() = withTimeout(5_000) {
        source.occurrences(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26), BERLIN).first()
    }

    @Test
    fun turnedOffNothingIsQueried() = runBlocking {
        assertTrue(source.calendars.first().isEmpty())
        assertTrue(window().isEmpty())
        assertTrue(provider.queries.isEmpty())
    }

    @Test
    fun grantedAccessShowsTheCalendarsUntilSwitchedOff() = runBlocking {
        assertEquals(DeviceCalendarStatus.OFF, source.status.first())
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        access.refresh()
        assertEquals(DeviceCalendarStatus.ACTIVE, withTimeout(5_000) { source.status.first { it != DeviceCalendarStatus.OFF } })
        assertTrue(withTimeout(5_000) { source.calendars.first { it.isNotEmpty() } }.isNotEmpty())
        env.settings.update { it.copy(deviceCalendarsEnabled = false) }
        assertEquals(DeviceCalendarStatus.OFF, withTimeout(5_000) { source.status.first { it == DeviceCalendarStatus.OFF } })
    }

    @Test
    fun calendarsAreMappedIntoTheDeviceRange() = runBlocking {
        turnOn()
        val calendars = withTimeout(5_000) { source.calendars.first { it.isNotEmpty() } }
        assertEquals(listOf("Work", "Holidays"), calendars.map { it.name })
        assertEquals(SourceIds.device(1), calendars[0].id)
        assertTrue(calendars.all { it.source == CalendarSource.DEVICE })
        assertTrue(calendars[0].writable)
        assertFalse("access level 200 is read only", calendars[1].writable)
        assertEquals("blue", calendars[0].colorKey)
    }

    @Test
    fun instancesSkipCancelledAndDeclinedAndHiddenCalendars() = runBlocking {
        turnOn()
        withTimeout(5_000) { source.calendars.first { it.isNotEmpty() } }
        val all = window()
        assertEquals(setOf("Planning", "Holiday", "Lunch with Ann"), all.map { it.title }.toSet())
        val holiday = all.single { it.title == "Holiday" }.timing as EventTiming.AllDay
        assertEquals(LocalDate.ofEpochDay(HOLIDAY), holiday.startDate)
        assertEquals(holiday.startDate, holiday.endDate)
        val selection = provider.queries.last { it.first.pathSegments.first() == "instances" }.second!!
        assertTrue(selection, selection.contains("eventStatus IS NULL OR eventStatus != 2"))
        assertTrue(selection, selection.contains("selfAttendeeStatus IS NULL OR selfAttendeeStatus != 2"))

        source.setVisible(SourceIds.device(2), false)
        val visible = withTimeout(5_000) { source.occurrences(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26), BERLIN).first { list -> list.none { it.title == "Holiday" } } }
        assertEquals(setOf("Planning", "Lunch with Ann"), visible.map { it.title }.toSet())
    }

    @Test
    fun pendingDeletesHideTheEvent() = runBlocking {
        turnOn()
        withTimeout(5_000) { source.calendars.first { it.isNotEmpty() } }
        val delete = PendingDelete(SourceIds.device(10), start, SeriesScope.ALL)
        pending.add(delete)
        assertTrue(window().none { it.title == "Planning" })
        assertNull(source.getEvent(SourceIds.device(10)))
        pending.remove(delete)
        assertTrue(window().any { it.title == "Planning" })
    }

    @Test
    fun invitationsAreReadOnlyWithTheOrganizer() = runBlocking {
        turnOn()
        val lunch = source.getEvent(SourceIds.device(12))!!
        assertFalse(lunch.access.canEdit)
        assertFalse(lunch.access.canDelete)
        assertEquals(ReadOnlyReason.GUEST, lunch.access.reason)
        assertEquals("ann@example.com", lunch.access.organizer)
        assertTrue(source.getEvent(SourceIds.device(10))!!.access.canEdit)
    }

    @Test
    fun writesGoToTheProvider() = runBlocking {
        turnOn()
        withTimeout(5_000) { source.calendars.first { it.isNotEmpty() } }
        val created = store.create(
            timedEvent(0, LocalDateTime.of(2026, 10, 24, 10, 0), 30).copy(calendarId = SourceIds.device(1), title = "Dentist", reminders = listOf(-540, 10)),
        )
        val row = provider.events.single { it["_id"] == SourceIds.providerId(created) }
        assertEquals("Dentist", row["title"])
        assertEquals("Europe/Berlin", row["eventTimezone"])
        assertEquals(1L, (row["calendar_id"] as Number).toLong())
        // Negative ("on the day at 9:00") reminders cannot be stored; the method is "alert".
        assertEquals(listOf(10), provider.reminders.filter { it["event_id"] == row["_id"] }.map { (it["minutes"] as Number).toInt() })
        assertEquals(1, (provider.reminders.single()["method"] as Number).toInt())

        provider.writes.clear()
        val loaded = source.getEvent(created)!!
        store.update(loaded.event.copy(title = "Dentist (moved)"))
        assertEquals(1, provider.writes.size)
        assertTrue(provider.writes.single(), provider.writes.single().contains("title=Dentist (moved)"))
        assertFalse("only changed columns", provider.writes.single().contains("dtstart"))

        store.delete(created, 0, SeriesScope.ALL)
        assertEquals(1, row["deleted"])
    }

    @Test
    fun deletingOneOccurrenceCancelsItInTheProvider() = runBlocking {
        turnOn()
        provider.events += event(20, calendarId = 1, title = "Jour fixe").apply { put("rrule", "FREQ=WEEKLY"); put("dtend", null); put("duration", "P3600S") }
        val key = start + 7 * DAY
        store.delete(SourceIds.device(20), key, SeriesScope.THIS)
        val exception = provider.events.single { it["original_id"] == 20L }
        assertEquals(key, (exception["originalInstanceTime"] as Number).toLong())
        assertEquals(2, (exception["eventStatus"] as Number).toInt())
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L
        val HOLIDAY = LocalDate.of(2026, 10, 23).toEpochDay()
    }
}
