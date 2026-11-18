package com.celander.app.core.ical

import androidx.room.withTransaction
import com.celander.app.core.database.CalendarDao
import com.celander.app.core.database.CalendarEntity
import com.celander.app.core.database.CelanderDatabase
import com.celander.app.core.database.EventDao
import com.celander.app.core.database.ExceptionDao
import com.celander.app.core.database.TaskDao
import com.celander.app.core.database.TaskEntity
import com.celander.app.core.database.toEntity
import com.celander.app.core.database.toModel
import com.celander.app.core.model.CalendarSource
import com.celander.app.event.zone
import com.celander.app.ui.CalendarColor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The events and tasks of the calendars on this device (not the ones of an account: those live at their server) as one iCalendar
 * file, and back. Events are VEVENTs (series with their changed and cancelled occurrences), tasks VTODOs. Each item carries the name of
 * its calendar in `X-CELANDER-CALENDAR`, so an import puts it back where it was; items without one go to the first calendar.
 * Importing never overwrites: an event whose UID is already here, or a task with the same title and due moment in the same
 * calendar, is skipped.
 */
@Singleton
class LocalDataIcs @Inject constructor(
    private val db: CelanderDatabase,
    private val calendars: CalendarDao,
    private val events: EventDao,
    private val exceptions: ExceptionDao,
    private val tasks: TaskDao,
) {
    /** What an import did: [skipped] are the items that were already here or that could not be read. */
    data class ImportResult(val events: Int, val tasks: Int, val skipped: Int)

    suspend fun export(): String {
        val local = calendars.getBySource(CalendarSource.LOCAL.name).filter { !it.isSample }
        val icalEvents = mutableListOf<ICalEvent>()
        val todos = mutableListOf<ICalTodo>()
        for (calendar in local) {
            val tag = "$CALENDAR_PROPERTY:${ContentLine.escapeText(calendar.name)}"
            for (entity in events.getForCalendar(calendar.id).filter { !it.isSample }) {
                val event = entity.toModel()
                val changes = exceptions.getForEvent(entity.id).map { it.toModel(event.zone) }
                icalEvents += IcsMapper.toIcal(event, changes).map { it.copy(extra = tag) }
            }
            for (task in tasks.getForCalendar(calendar.id).filter { !it.isSample }) {
                val model = task.toModel()
                todos += ICalTodo(
                    uid = "task-${task.id}-${task.createdAt}@celander",
                    summary = model.title,
                    description = model.note,
                    due = model.dueDate,
                    dueTime = model.dueTime,
                    completed = model.done,
                    completedAt = model.completedAt,
                    reminders = model.reminders,
                    calendar = calendar.name,
                )
            }
        }
        return IcsWriter.calendar(icalEvents, todos = todos)
    }

    suspend fun import(text: String, now: Long = System.currentTimeMillis()): ImportResult = db.withTransaction {
        val parsed = IcsParser.parseCalendar(text)
        val known = events.getAll().map { it.uid }.toMutableSet()
        val byName = calendars.getBySource(CalendarSource.LOCAL.name).associateBy { it.name }.toMutableMap()
        var fallback = byName.values.firstOrNull()
        var addedEvents = 0
        var addedTasks = 0
        var skipped = 0

        /** The local calendar called [name]; made when there is none; the first one when the file does not say. */
        suspend fun calendarFor(name: String?): CalendarEntity {
            if (name.isNullOrBlank()) {
                return fallback ?: create(if (byName.isEmpty()) DEFAULT_NAME else "Imported", now).also { fallback = it; byName[it.name] = it }
            }
            return byName[name] ?: create(name, now).also { byName[name] = it; if (fallback == null) fallback = it }
        }

        // Events: a series and its changed occurrences share the UID and go together.
        for ((uid, group) in parsed.events.groupBy { it.uid }) {
            if (uid in known) {
                skipped++
                continue
            }
            val calendar = calendarFor(group.firstNotNullOfOrNull { calendarName(it.extra) })
            val converted = IcsMapper.fromIcal(group.map { it.copy(extra = null) }, calendar.id)
            if (converted.isEmpty()) {
                skipped++
                continue
            }
            for ((event, changes) in converted) {
                val id = events.insert(event.copy(id = 0, createdAt = now, updatedAt = now).toEntity())
                changes.forEach { exceptions.upsert(it.copy(eventId = id).toEntity()) }
                known += event.uid
                addedEvents++
            }
        }

        // Tasks: the same title and due moment in the same calendar is the same task.
        for (todo in parsed.todos) {
            val calendar = calendarFor(todo.calendar)
            val dueDay = todo.due?.toEpochDay()
            val dueMinute = todo.dueTime?.let { it.hour * 60 + it.minute }
            val same = tasks.getForCalendar(calendar.id).any { it.title == todo.summary && it.dueDay == dueDay && it.dueMinute == dueMinute }
            if (same || todo.summary.isBlank()) {
                skipped++
                continue
            }
            tasks.insert(
                TaskEntity(
                    calendarId = calendar.id, title = todo.summary, note = todo.description, dueDay = dueDay, dueMinute = dueMinute,
                    reminders = todo.reminders, done = todo.completed, completedAt = if (todo.completed) todo.completedAt ?: now else null,
                    isSample = false, createdAt = now, updatedAt = now,
                ),
            )
            addedTasks++
        }
        ImportResult(addedEvents, addedTasks, skipped)
    }

    private suspend fun create(name: String, now: Long): CalendarEntity {
        val order = calendars.maxSortOrder() + 1
        val entity = CalendarEntity(name = name, colorKey = CalendarColor.DEFAULT.key, sortOrder = order, createdAt = now)
        return entity.copy(id = calendars.insert(entity))
    }

    /** The calendar name an item carries in its extra lines. */
    private fun calendarName(extra: String?): String? = extra?.lineSequence()
        ?.mapNotNull(ContentLine::parse)?.firstOrNull { it.name == CALENDAR_PROPERTY }
        ?.value?.let(ContentLine::unescapeText)

    private companion object {
        const val CALENDAR_PROPERTY = "X-CELANDER-CALENDAR"
        const val DEFAULT_NAME = "Calendar"
    }
}
