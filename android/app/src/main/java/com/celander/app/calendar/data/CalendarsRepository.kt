package com.celander.app.calendar.data

import androidx.room.withTransaction
import com.celander.app.core.database.CalendarDao
import com.celander.app.core.database.CalendarEntity
import com.celander.app.core.database.CelanderDatabase
import com.celander.app.core.database.EventDao
import com.celander.app.core.database.EventEntity
import com.celander.app.core.database.EventExceptionEntity
import com.celander.app.core.database.ExceptionDao
import com.celander.app.core.database.TaskDao
import com.celander.app.core.database.TaskEntity
import com.celander.app.core.database.toModel
import com.celander.app.core.database.withReminderDefaults
import com.celander.app.core.model.CalendarInfo
import com.celander.app.core.model.CalendarSource
import com.celander.app.core.model.EntryKind
import com.celander.app.core.model.ReminderDefaults
import com.celander.app.core.time.TimeSource
import com.celander.app.ui.CalendarColor
import com.celander.app.settings.AppSettings
import com.celander.app.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Everything needed to bring a deleted calendar back with its entries. */
data class CalendarSnapshot(
    val calendar: CalendarEntity,
    val events: List<EventEntity>,
    val exceptions: List<EventExceptionEntity>,
    val tasks: List<TaskEntity>,
    val wasDefault: Boolean,
)

/** Local calendars (name, color, visibility) and the default calendar for new entries. */
@Singleton
class CalendarsRepository @Inject constructor(
    private val db: CelanderDatabase,
    private val dao: CalendarDao,
    private val eventDao: EventDao,
    private val exceptionDao: ExceptionDao,
    private val taskDao: TaskDao,
    private val settings: SettingsRepository,
    private val time: TimeSource,
) {
    /** Own calendars, then those of the connected accounts, then the Google Tasks lists. */
    val calendars: Flow<List<CalendarInfo>> = combine(
        dao.observeAll(),
        db.accountDao().observeAccounts(),
        db.accountDao().observeRemoteCalendars(),
    ) { rows, accounts, remotes ->
        val room = rows.map { it.toModel() }
        val accountById = accounts.associateBy { it.id }
        val remoteByCalendar = remotes.associateBy { it.calendarId }
        val fromAccounts = room.filter { it.source == CalendarSource.ACCOUNT }.map { info ->
            val account = remoteByCalendar[info.id]?.let { accountById[it.accountId] }
            // Writable where the account lets the user write; changes are sent at the next sync.
            info.copy(
                account = account?.identifier,
                accountType = account?.type,
                writable = remoteByCalendar[info.id]?.let { runCatching { com.celander.app.core.model.AccessRole.valueOf(it.accessRole).canWrite }.getOrDefault(false) } ?: false,
            )
        }
        room.filter { it.source == CalendarSource.LOCAL } + fromAccounts + room.filter { it.source == CalendarSource.GOOGLE_TASKS }
    }

    /** The configured default for events, falling back to the first calendar that holds events. */
    val defaultCalendarId: Flow<Long?> = combine(calendars, settings.settings) { list, s -> defaultFor(EntryKind.EVENT, list, s) }

    /** The configured default for tasks: the task default, else the event default if it holds tasks, else the first local calendar. */
    val defaultTaskCalendarId: Flow<Long?> = combine(calendars, settings.settings) { list, s -> defaultFor(EntryKind.TASK, list, s) }

    private fun defaultFor(kind: EntryKind, list: List<CalendarInfo>, s: AppSettings): Long? {
        // Hidden calendars are not offered; only if every candidate is hidden, one of them still takes the entry.
        val holding = list.filter { it.offers(kind) }.ifEmpty { list.filter { it.canHold(kind) } }
        val configured = if (kind == EntryKind.EVENT) listOf(s.defaultCalendarId) else listOf(s.defaultTaskCalendarId, s.defaultCalendarId)
        return configured.firstOrNull { id -> holding.any { it.id == id } }
            ?: holding.firstOrNull { it.source == CalendarSource.LOCAL }?.id
            ?: holding.firstOrNull()?.id
    }

    /** Calendar for a new entry of [kind]: the last one used for it, else its default, else the first that can hold it. */
    suspend fun preferredId(kind: EntryKind): Long? {
        val list = first()
        val s = settings.current()
        val lastUsed = if (kind == EntryKind.EVENT) s.lastUsedCalendarId else s.lastUsedTaskCalendarId
        return lastUsed?.takeIf { id -> list.any { it.id == id && it.offers(kind) } } ?: defaultFor(kind, list, s)
    }

    /** Creates the initial calendar on first start (Google lists do not count). */
    suspend fun ensureDefault(name: String) {
        if (dao.countLocal() > 0) return
        val id = dao.insert(CalendarEntity(name = name, colorKey = CalendarColor.DEFAULT.key, createdAt = time.now().toEpochMilli()))
        settings.update { it.copy(defaultCalendarId = id) }
    }

    suspend fun create(name: String, colorKey: String, isSample: Boolean = false): Long {
        val order = dao.maxSortOrder() + 1
        return dao.insert(
            CalendarEntity(name = name, colorKey = colorKey, sortOrder = order, isSample = isSample, createdAt = time.now().toEpochMilli()),
        )
    }

    /** Google lists keep the name from Google; only color and visibility change in Celander. */
    suspend fun update(calendar: CalendarInfo) {
        val current = dao.getById(calendar.id) ?: return
        val name = if (current.source == CalendarSource.LOCAL.name) calendar.name else current.name
        dao.update(current.copy(name = name, colorKey = calendar.colorKey, visible = calendar.visible, enabled = calendar.enabled))
    }

    /** The calendar's own default reminders for new entries; null goes back to the app-wide ones. */
    suspend fun setReminderDefaults(id: Long, defaults: ReminderDefaults?) {
        val current = dao.getById(id) ?: return
        dao.update(current.withReminderDefaults(defaults))
    }

    suspend fun setVisible(id: Long, visible: Boolean) = dao.setVisible(id, visible)

    /** The "Manage calendars" switch. */
    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    /** Makes [id] the default for what it holds: a Google list for tasks, any other calendar for events. */
    suspend fun setDefault(id: Long) {
        val calendar = first().firstOrNull { it.id == id } ?: return
        settings.update { s -> if (calendar.source == CalendarSource.GOOGLE_TASKS) s.copy(defaultTaskCalendarId = id) else s.copy(defaultCalendarId = id) }
    }

    /** Sets the default list for tasks (a Google list or a local calendar). */
    suspend fun setDefaultTask(id: Long) = settings.update { it.copy(defaultTaskCalendarId = id) }

    suspend fun entryCount(id: Long): Int = dao.entryCount(id)

    suspend fun calendarCount(): Int = dao.countLocal()

    /** Entries per calendar id. */
    val entryCounts: Flow<Map<Long, Int>> = dao.observeEntryCounts().map { list -> list.associate { it.calendarId to it.entries } }

    /**
     * Deletes a local calendar with all its events and tasks and returns what undo needs; the last
     * local calendar and calendars from accounts cannot be deleted (null).
     */
    suspend fun delete(id: Long): CalendarSnapshot? {
        val all = dao.getBySource(CalendarSource.LOCAL.name)
        if (all.size <= 1) return null
        val calendar = all.firstOrNull { it.id == id } ?: return null
        val wasDefault = settings.current().defaultCalendarId == id
        val snapshot = db.withTransaction {
            val events = eventDao.getForCalendar(id)
            CalendarSnapshot(calendar, events, exceptionDao.getForEvents(events.map { it.id }), taskDao.getForCalendar(id), wasDefault)
                .also { dao.delete(id) }
        }
        if (wasDefault) settings.update { s -> s.copy(defaultCalendarId = all.first { it.id != id }.id) }
        return snapshot
    }

    suspend fun restore(snapshot: CalendarSnapshot) {
        db.withTransaction {
            dao.insert(snapshot.calendar)
            eventDao.restoreAll(snapshot.events)
            exceptionDao.restoreAll(snapshot.exceptions)
            taskDao.restoreAll(snapshot.tasks)
        }
        if (snapshot.wasDefault) setDefault(snapshot.calendar.id)
    }

    suspend fun first(): List<CalendarInfo> = calendars.first()
}
