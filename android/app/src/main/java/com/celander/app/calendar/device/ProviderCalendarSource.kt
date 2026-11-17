package com.celander.app.calendar.device

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import com.celander.app.core.database.DeviceCalendarPrefEntity
import com.celander.app.core.database.DevicePrefsDao
import com.celander.app.core.database.reminderDefaults
import com.celander.app.core.database.withReminderDefaults
import com.celander.app.core.device.CalendarRow
import com.celander.app.core.device.DeviceMapping
import com.celander.app.core.device.EventRow
import com.celander.app.core.device.ExceptionRow
import com.celander.app.core.device.InstanceRow
import com.celander.app.core.device.ReminderRow
import com.celander.app.core.device.TextMatch
import com.celander.app.core.di.ApplicationScope
import com.celander.app.core.di.IoDispatcher
import com.celander.app.core.model.CalendarInfo
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.ReminderDefaults
import com.celander.app.core.model.SourceIds
import com.celander.app.core.recurrence.OccurrenceCalculator
import com.celander.app.ui.CalendarColor
import com.celander.app.event.EventWithExceptions
import com.celander.app.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext

/** [DeviceCalendarSource] on the system calendar provider. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Singleton
class ProviderCalendarSource @Inject constructor(
    @ApplicationContext private val context: Context,
    settings: SettingsRepository,
    access: DeviceCalendarAccess,
    private val prefs: DevicePrefsDao,
    private val pendingDeletes: PendingDeviceDeletes,
    @ApplicationScope scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DeviceCalendarSource {
    private val resolver: ContentResolver get() = context.contentResolver

    /** Untouched, the switch follows the permission; only a switch turned on reports missing access. */
    override val status: Flow<DeviceCalendarStatus> = combine(settings.settings.map { it.deviceCalendarsEnabled }, access.permissions) { choice, p ->
        when {
            choice == false -> DeviceCalendarStatus.OFF
            !p.read -> if (choice == true) DeviceCalendarStatus.NO_ACCESS else DeviceCalendarStatus.OFF
            !p.write -> DeviceCalendarStatus.READ_ONLY
            else -> DeviceCalendarStatus.ACTIVE
        }
    }.distinctUntilChanged()

    /** Provider changes plus deletes held back for undo, which hide events just like a change. */
    override val changes: Flow<Unit> = merge(
        status.flatMapLatest { if (it.readable) providerChanges() else flowOf(Unit) },
        pendingDeletes.pending.drop(1).map { },
    ).shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), replay = 1)

    /** One observer for the whole provider; bursts of a sync are debounced. */
    private fun providerChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val registered = try {
            resolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            true
        } catch (_: SecurityException) {
            false
        }
        awaitClose { if (registered) resolver.unregisterContentObserver(observer) }
    }.debounce(CHANGE_DEBOUNCE_MILLIS).onStart { emit(Unit) }

    /**
     * A failing read counts as off until the next change, which reads again; the outer catch is left
     * for failing inputs (settings, permissions).
     */
    private val rows: Flow<Pair<DeviceCalendarStatus, List<CalendarRow>>> = combine(status, changes) { s, _ -> s }
        .mapLatest { s ->
            if (!s.readable) {
                s to emptyList<CalendarRow>()
            } else {
                readOr(DeviceCalendarStatus.OFF to emptyList<CalendarRow>()) { s to queryCalendars() }
            }
        }
        .catch { emit(DeviceCalendarStatus.OFF to emptyList()) }
        .flowOn(io)
        .shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), replay = 1)

    override val calendars: Flow<List<CalendarInfo>> = combine(rows, prefs.observeAll()) { (s, list), prefList ->
        val byKey = prefList.associateBy { it.stableKey }
        DeviceMapping.sorted(list).map { row ->
            val pref = byKey[DeviceMapping.stableKey(row)]
            DeviceMapping.calendar(row, s == DeviceCalendarStatus.ACTIVE, pref?.visible, pref?.colorKey, ::colorKeyOf)
                .copy(reminderDefaults = pref?.reminderDefaults)
        }
    }.distinctUntilChanged()

    override val googleAccounts: Flow<List<DeviceAccount>> = rows.map { (_, list) ->
        list.filter { it.accountType == GOOGLE_ACCOUNT_TYPE }.mapNotNull { it.accountName }.distinct().map(::DeviceAccount)
    }.distinctUntilChanged()

    override fun calendarSync(accounts: Set<String>): Flow<Map<String, CalendarSync>> =
        syncSettingChanges().map { accounts.associateWith(::calendarSyncOf) }.distinctUntilChanged().flowOn(io)

    private fun syncSettingChanges(): Flow<Unit> = callbackFlow {
        val handle = ContentResolver.addStatusChangeListener(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS) { trySend(Unit) }
        awaitClose { ContentResolver.removeStatusChangeListener(handle) }
    }.debounce(CHANGE_DEBOUNCE_MILLIS).onStart { emit(Unit) }

    /** An account the phone has not set up for calendars yet (syncable unknown) counts by its auto-sync flag. */
    private fun calendarSyncOf(account: String): CalendarSync = try {
        val acc = Account(account, GOOGLE_ACCOUNT_TYPE)
        when {
            !ContentResolver.getMasterSyncAutomatically() -> CalendarSync.PHONE_OFF
            ContentResolver.getIsSyncable(acc, CalendarContract.AUTHORITY) == 0 -> CalendarSync.OFF
            ContentResolver.getSyncAutomatically(acc, CalendarContract.AUTHORITY) -> CalendarSync.ON
            else -> CalendarSync.OFF
        }
    } catch (_: SecurityException) {
        CalendarSync.OFF
    }

    override suspend fun setCalendarSync(account: String, on: Boolean): CalendarSync = withContext(io) {
        try {
            if (!ContentResolver.getMasterSyncAutomatically()) return@withContext CalendarSync.PHONE_OFF
            val acc = Account(account, GOOGLE_ACCOUNT_TYPE)
            if (on) {
                if (ContentResolver.getIsSyncable(acc, CalendarContract.AUTHORITY) == 0) ContentResolver.setIsSyncable(acc, CalendarContract.AUTHORITY, 1)
                ContentResolver.setSyncAutomatically(acc, CalendarContract.AUTHORITY, true)
                ContentResolver.requestSync(acc, CalendarContract.AUTHORITY, Bundle())
            } else {
                ContentResolver.setSyncAutomatically(acc, CalendarContract.AUTHORITY, false)
            }
        } catch (_: SecurityException) {
            // Reported through the state read below.
        }
        calendarSyncOf(account)
    }

    override fun occurrences(start: LocalDate, endExclusive: LocalDate, zone: ZoneId): Flow<List<EventOccurrence>> {
        // Widened by a day like the Room query, so events in other zones or across midnight are found.
        val startUtc = start.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endUtc = endExclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val windowStart = start.atStartOfDay(zone).toInstant()
        val windowEnd = endExclusive.atStartOfDay(zone).toInstant()
        return combine(calendars, changes, pendingDeletes.pending) { list, _, pending -> list to pending }
            .mapLatest { (list, pending) ->
                val visible = list.filter { it.visible }.associateBy { it.id }
                // A failing read shows nothing until the next change instead of ending the flow.
                readOr(emptyList<EventOccurrence>()) {
                    queryInstances(startUtc, endUtc, visible)
                        .filter { OccurrenceCalculator.overlaps(it.timing, windowStart, windowEnd, zone) && !pending.hides(it) }
                }
            }
            .catch { emit(emptyList()) }
            .flowOn(io)
    }

    /**
     * A failing read (or input) keeps the last state until the next change, which reads again: an
     * exception would end the flow and the details with it, and null would close them as deleted.
     */
    override fun observeEvent(id: Long): Flow<EventWithExceptions?> =
        combine(changes, pendingDeletes.pending) { _, _ -> }
            .transformLatest {
                val event = try {
                    getEvent(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@transformLatest
                }
                // Outside the try, so a failure downstream is not taken for a failed read.
                emit(event)
            }
            .flowOn(io)

    override suspend fun getEvent(id: Long, includePendingDeletes: Boolean): EventWithExceptions? = withContext(io) {
        if (!SourceIds.isDevice(id) || (!includePendingDeletes && pendingDeletes.hidesAll(id)) || !status.first().readable) return@withContext null
        val canWrite = status.first() == DeviceCalendarStatus.ACTIVE
        loadEvent(SourceIds.providerId(id), canWrite)?.let { EventWithExceptions(it.event, it.exceptions, it.access, it.capabilities) }
    }

    override suspend fun search(text: String, now: Instant, zone: ZoneId, allowed: (Long) -> Boolean): List<EventOccurrence> = withContext(io) {
        val query = TextMatch.prepare(text) ?: return@withContext emptyList()
        val visible = calendars.first().filter { it.visible && allowed(it.id) }.associateBy { it.id }
        if (visible.isEmpty()) return@withContext emptyList()
        val masters = searchMasters(query, visible.keys.map(SourceIds::providerId))
        if (masters.isEmpty()) return@withContext emptyList()
        val today = now.atZone(zone).toLocalDate()
        val from = today.minusDays(SEARCH_PAST_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(SEARCH_FUTURE_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        val pending = pendingDeletes.pending.value
        queryInstances(from, to, visible, masters)
            // Each occurrence must match itself: one renamed away does not stand for its series.
            .filter { !pending.hides(it) && query.matches(it.title, it.location, it.description) }
            .groupBy { it.event.id }
            .values
            .map { list ->
                val sorted = list.sortedBy { it.timing.startInstant(zone) }
                sorted.firstOrNull { it.timing.endInstant(zone).isAfter(now) } ?: sorted.last()
            }
    }

    override suspend fun setVisible(id: Long, visible: Boolean) = updatePref(id) { it.copy(visible = visible) }

    override suspend fun setColor(id: Long, colorKey: String) = updatePref(id) { it.copy(colorKey = colorKey) }

    override suspend fun setReminderDefaults(id: Long, defaults: ReminderDefaults?) = updatePref(id) { it.withReminderDefaults(defaults) }

    private suspend fun updatePref(id: Long, change: (DeviceCalendarPrefEntity) -> DeviceCalendarPrefEntity) {
        val row = rows.first().second.firstOrNull { SourceIds.device(it.id) == id } ?: return
        val key = DeviceMapping.stableKey(row)
        prefs.upsert(change(prefs.get(key) ?: DeviceCalendarPrefEntity(key, visible = null, colorKey = null)))
    }

    private fun queryCalendars(): List<CalendarRow> = try {
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            CALENDAR_PROJECTION,
            "${CalendarContract.Calendars.SYNC_EVENTS} = 1",
            null,
            null,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        CalendarRow(
                            id = c.getLong(0),
                            accountName = c.str(1),
                            accountType = c.str(2),
                            syncId = c.str(3),
                            name = c.str(4),
                            displayName = c.str(5),
                            color = c.int(6),
                            accessLevel = c.int(7),
                            ownerAccount = c.str(8),
                            visible = c.int(9),
                            isPrimary = c.int(10),
                            maxReminders = c.int(11),
                            allowedReminders = c.str(12),
                        ),
                    )
                }
            }
        }.orEmpty()
    } catch (_: SecurityException) {
        emptyList()
    }

    /** Instances in [startUtc, endUtc) of [calendars]; with [masters], only those series. */
    private fun queryInstances(startUtc: Long, endUtc: Long, calendars: Map<Long, CalendarInfo>, masters: Set<Long>? = null): List<EventOccurrence> {
        if (calendars.isEmpty()) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, startUtc)
            ContentUris.appendId(it, endUtc)
        }.build()
        val ids = calendars.keys.joinToString(",") { SourceIds.providerId(it).toString() }
        val seriesFilter = masters?.joinToString(",")?.let { " AND (${CalendarContract.Instances.EVENT_ID} IN ($it) OR ${CalendarContract.Instances.ORIGINAL_ID} IN ($it))" }.orEmpty()
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN ($ids)" +
            " AND (${CalendarContract.Instances.STATUS} IS NULL OR ${CalendarContract.Instances.STATUS} != ${CalendarContract.Events.STATUS_CANCELED})" +
            " AND (${CalendarContract.Instances.SELF_ATTENDEE_STATUS} IS NULL OR " +
            "${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED})" + seriesFilter
        val rows = try {
            resolver.query(uri, INSTANCE_PROJECTION, selection, null, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            InstanceRow(
                                eventId = c.long(0),
                                begin = c.long(1),
                                end = c.long(2),
                                allDay = c.int(3),
                                title = c.str(4),
                                location = c.str(5),
                                description = c.str(6),
                                eventTimezone = c.str(7),
                                calendarId = c.long(8),
                                rrule = c.str(9),
                                originalId = c.long(10),
                                originalInstanceTime = c.long(11),
                                originalAllDay = c.int(12),
                                displayColor = c.int(13),
                                calendarColor = c.int(14),
                            ),
                        )
                    }
                }
            }.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
        val masterRrules = masterRrules(rows.mapNotNull { it.originalId }.toSet())
        return DeviceMapping.occurrences(rows, calendars, masterRrules, ::colorKeyOf)
    }

    /** RRULEs of the masters of modified instances, whose own rows have none. */
    private fun masterRrules(ids: Set<Long>): Map<Long, String?> {
        if (ids.isEmpty()) return emptyMap()
        return try {
            resolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID, CalendarContract.Events.RRULE),
                "${CalendarContract.Events._ID} IN (${ids.joinToString(",")})",
                null,
                null,
            )?.use { c -> buildMap { while (c.moveToNext()) put(c.getLong(0), c.str(1)) } }.orEmpty()
        } catch (_: SecurityException) {
            emptyMap()
        }
    }

    /** Provider ids of the masters whose master or exception rows match [query]. */
    private fun searchMasters(query: TextMatch.Query, calendarIds: List<Long>): Set<Long> = try {
        resolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.ORIGINAL_ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DESCRIPTION,
            ),
            "${CalendarContract.Events.DELETED} = 0 AND ${CalendarContract.Events.CALENDAR_ID} IN (${calendarIds.joinToString(",")})",
            null,
            null,
        )?.use { c ->
            buildSet {
                while (c.moveToNext()) {
                    if (query.matches(c.str(2).orEmpty(), c.str(3).orEmpty(), c.str(4).orEmpty())) add(c.long(1) ?: c.getLong(0))
                }
            }
        }.orEmpty()
    } catch (_: SecurityException) {
        emptySet()
    }

    private fun loadEvent(providerId: Long, canWrite: Boolean) = try {
        val row = resolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, providerId),
            EVENT_PROJECTION,
            null,
            null,
            null,
        )?.use { c -> if (c.moveToFirst()) eventRow(c) else null }
        row?.let {
            val exceptions = resolver.query(
                CalendarContract.Events.CONTENT_URI,
                EXCEPTION_PROJECTION,
                "${CalendarContract.Events.ORIGINAL_ID} = ? AND ${CalendarContract.Events.DELETED} = 0",
                arrayOf(providerId.toString()),
                null,
            )?.use { c -> buildList { while (c.moveToNext()) add(exceptionRow(c)) } }.orEmpty()
            val reminders = resolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
                "${CalendarContract.Reminders.EVENT_ID} = ?",
                arrayOf(providerId.toString()),
                null,
            )?.use { c -> buildList { while (c.moveToNext()) add(ReminderRow(c.long(0), c.int(1), c.int(2))) } }.orEmpty()
            DeviceMapping.event(it, exceptions, reminders, canWrite, ::colorKeyOf)
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun eventRow(c: Cursor) = EventRow(
        id = c.getLong(0),
        calendarId = c.long(1),
        title = c.str(2),
        location = c.str(3),
        description = c.str(4),
        dtStart = c.long(5),
        dtEnd = c.long(6),
        duration = c.str(7),
        allDay = c.int(8),
        eventTimezone = c.str(9),
        rrule = c.str(10),
        rdate = c.str(11),
        exrule = c.str(12),
        exdate = c.str(13),
        uid = c.str(14),
        displayColor = c.int(15),
        calendarColor = c.int(16),
        organizer = c.str(17),
        isOrganizer = c.str(18)?.let { if (it == "1" || it.equals("true", ignoreCase = true)) 1 else 0 },
        guestsCanModify = c.int(19),
        hasAttendeeData = c.int(20),
        ownerAccount = c.str(21),
        accessLevel = c.int(22),
        deleted = c.int(23),
    )

    private fun exceptionRow(c: Cursor) = ExceptionRow(
        id = c.getLong(0),
        originalInstanceTime = c.long(1),
        originalAllDay = c.int(2),
        dtStart = c.long(3),
        dtEnd = c.long(4),
        duration = c.str(5),
        allDay = c.int(6),
        eventTimezone = c.str(7),
        title = c.str(8),
        location = c.str(9),
        description = c.str(10),
        status = c.int(11),
        deleted = c.int(12),
    )

    /**
     * One read of the provider, or [fallback] if it fails (a provider that crashed or is being
     * updated). Caught here rather than around the flow, so the flow keeps reacting to changes.
     */
    private inline fun <T> readOr(fallback: T, read: () -> T): T = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (_: RuntimeException) {
        fallback
    }

    private fun colorKeyOf(argb: Int): String = CalendarColor.nearest(argb).key

    private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)

    private fun Cursor.int(i: Int): Int? = if (isNull(i)) null else getInt(i)

    private fun Cursor.long(i: Int): Long? = if (isNull(i)) null else getLong(i)

    companion object {
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val CHANGE_DEBOUNCE_MILLIS = 300L
        private const val SEARCH_PAST_DAYS = 400L
        private const val SEARCH_FUTURE_DAYS = 800L

        private val CALENDAR_PROJECTION = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars._SYNC_ID,
            CalendarContract.Calendars.NAME,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.MAX_REMINDERS,
            CalendarContract.Calendars.ALLOWED_REMINDERS,
        )

        private val INSTANCE_PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_TIMEZONE,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.RRULE,
            CalendarContract.Instances.ORIGINAL_ID,
            CalendarContract.Instances.ORIGINAL_INSTANCE_TIME,
            CalendarContract.Instances.ORIGINAL_ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.CALENDAR_COLOR,
        )

        private val EVENT_PROJECTION = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DURATION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.RDATE,
            CalendarContract.Events.EXRULE,
            CalendarContract.Events.EXDATE,
            CalendarContract.Events.UID_2445,
            CalendarContract.Events.DISPLAY_COLOR,
            CalendarContract.Events.CALENDAR_COLOR,
            CalendarContract.Events.ORGANIZER,
            CalendarContract.Events.IS_ORGANIZER,
            CalendarContract.Events.GUESTS_CAN_MODIFY,
            CalendarContract.Events.HAS_ATTENDEE_DATA,
            CalendarContract.Events.OWNER_ACCOUNT,
            CalendarContract.Events.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Events.DELETED,
        )

        private val EXCEPTION_PROJECTION = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
            CalendarContract.Events.ORIGINAL_ALL_DAY,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DURATION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.STATUS,
            CalendarContract.Events.DELETED,
        )
    }
}
