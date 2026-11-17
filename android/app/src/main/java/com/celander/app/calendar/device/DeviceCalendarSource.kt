package com.celander.app.calendar.device

import com.celander.app.core.model.CalendarInfo
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.ReminderDefaults
import com.celander.app.event.EventWithExceptions
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Whether Celander shows the phone's calendars, and how far it may use them. */
enum class DeviceCalendarStatus {
    /** Turned off in the settings. */
    OFF,

    /** Turned on, but the calendar permission is missing. */
    NO_ACCESS,

    /** Reading allowed, writing not. */
    READ_ONLY,
    ACTIVE,
    ;

    val readable: Boolean get() = this == READ_ONLY || this == ACTIVE
}

/** A Google account that has calendars on this phone. */
data class DeviceAccount(val name: String)

/** Whether the phone syncs the calendars of a Google account. */
enum class CalendarSync {
    ON,
    OFF,

    /** Auto-sync is off for the whole phone; only the system sync settings can turn it on again. */
    PHONE_OFF,
}

/**
 * The calendars the phone syncs (Google Calendar and others) through the system calendar provider.
 * Nothing is copied into Celander's database; ids are in the [com.celander.app.core.model.SourceIds]
 * device range. Everything is empty while [status] is not readable.
 */
interface DeviceCalendarSource {
    val status: Flow<DeviceCalendarStatus>

    /** Emits once at start and after every change in the provider. */
    val changes: Flow<Unit>

    val calendars: Flow<List<CalendarInfo>>

    /** Google accounts that have calendars on this phone. */
    val googleAccounts: Flow<List<DeviceAccount>>

    fun occurrences(start: LocalDate, endExclusive: LocalDate, zone: ZoneId): Flow<List<EventOccurrence>>

    fun observeEvent(id: Long): Flow<EventWithExceptions?>

    /** The event, or null if it is gone or (unless [includePendingDeletes]) waiting to be deleted. */
    suspend fun getEvent(id: Long, includePendingDeletes: Boolean = false): EventWithExceptions?

    /**
     * Events matching [text] in visible calendars allowed by [allowed], one occurrence per series:
     * the next or ongoing one, else the last.
     */
    suspend fun search(text: String, now: Instant, zone: ZoneId, allowed: (Long) -> Boolean): List<EventOccurrence>

    /** Celander-only visibility and color; the provider and the calendar app are not changed. */
    suspend fun setVisible(id: Long, visible: Boolean)

    suspend fun setColor(id: Long, colorKey: String)

    /** The calendar's own default reminders in Celander; null goes back to the app-wide ones. */
    suspend fun setReminderDefaults(id: Long, defaults: ReminderDefaults?) = Unit

    /**
     * Calendar sync of each Google account in [accounts] as the phone's sync settings have it, read
     * again whenever they change. Needs no calendar permission.
     */
    fun calendarSync(accounts: Set<String>): Flow<Map<String, CalendarSync>> = flowOf(emptyMap())

    /**
     * Turns the phone's calendar sync of a Google [account] on or off (on also makes the account
     * syncable for calendars and starts a sync) and returns the state afterwards. The phone-wide
     * auto-sync is never changed: while it is off, this returns [CalendarSync.PHONE_OFF].
     */
    suspend fun setCalendarSync(account: String, on: Boolean): CalendarSync = CalendarSync.OFF

    /** For tests and builds without device calendars. */
    object None : DeviceCalendarSource {
        override val status: Flow<DeviceCalendarStatus> = flowOf(DeviceCalendarStatus.OFF)
        override val changes: Flow<Unit> = flowOf(Unit)
        override val calendars: Flow<List<CalendarInfo>> = flowOf(emptyList())
        override val googleAccounts: Flow<List<DeviceAccount>> = flowOf(emptyList())
        override fun occurrences(start: LocalDate, endExclusive: LocalDate, zone: ZoneId): Flow<List<EventOccurrence>> = flowOf(emptyList())
        override fun observeEvent(id: Long): Flow<EventWithExceptions?> = flowOf(null)
        override suspend fun getEvent(id: Long, includePendingDeletes: Boolean): EventWithExceptions? = null
        override suspend fun search(text: String, now: Instant, zone: ZoneId, allowed: (Long) -> Boolean): List<EventOccurrence> = emptyList()
        override suspend fun setVisible(id: Long, visible: Boolean) = Unit
        override suspend fun setColor(id: Long, colorKey: String) = Unit
    }
}
