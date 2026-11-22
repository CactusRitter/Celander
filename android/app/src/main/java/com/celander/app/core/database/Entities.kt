package com.celander.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "calendars")
data class CalendarEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorKey: String,
    val visible: Boolean = true,
    /** The "Manage calendars" switch: off takes the calendar out of the drawer, views, search and defaults. */
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val isSample: Boolean = false,
    val createdAt: Long,
    /** [com.celander.app.core.model.CalendarSource] name. */
    @ColumnInfo(defaultValue = "LOCAL") val source: String = "LOCAL",
    /**
     * The calendar's own default reminders (minutes before), set together; all null uses the app-wide
     * defaults. An empty list (stored as "") means no reminders.
     */
    val eventReminders: List<Int>? = null,
    val allDayReminders: List<Int>? = null,
    val taskReminders: List<Int>? = null,
)

/** A Google Tasks list mirrored as the calendar [calendarId]. */
@Entity(
    tableName = "google_task_lists",
    foreignKeys = [ForeignKey(entity = CalendarEntity::class, parentColumns = ["id"], childColumns = ["calendarId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["account", "remoteId"], unique = true)],
)
data class GoogleTaskListEntity(
    @PrimaryKey val calendarId: Long,
    val account: String,
    val remoteId: String,
    @ColumnInfo(defaultValue = "0") val isDefault: Boolean = false,
    /** Largest server `updated` seen, in ms; the next pull asks for changes since then. */
    val watermark: Long? = null,
    @ColumnInfo(defaultValue = "0") val lastFullSyncAt: Long = 0,
)

/**
 * Sync state of a task in a Google list. Deliberately without a foreign key: a link whose task is
 * gone is the tombstone that makes the next sync delete the task at Google. The base fields are the
 * last state both sides agreed on; a task differing from them has local changes.
 */
@Entity(
    tableName = "google_task_links",
    indices = [Index(value = ["remoteId"], unique = true), Index("remoteListId")],
)
data class GoogleTaskLinkEntity(
    @PrimaryKey val taskId: Long,
    val account: String,
    /** Null while the first upload is in flight. */
    val remoteId: String?,
    val remoteListId: String,
    val etag: String?,
    @ColumnInfo(defaultValue = "0") val remoteUpdated: Long = 0,
    val baseTitle: String,
    val baseNote: String,
    val baseDueDay: Long?,
    val baseDone: Boolean,
    val baseCompletedAt: Long?,
    val insertStartedAt: Long? = null,
    val deletedAt: Long? = null,
    /** The task's updatedAt when Google rejected it; it waits for the next local edit. */
    val rejectedUpdatedAt: Long? = null,
    val lastError: String? = null,
)

/**
 * A single event or the master of a series. Timed events store UTC instants ([startUtc], [endUtc]);
 * all-day events store epoch days ([startDay], [endDay] inclusive) without a time zone.
 */
@Entity(
    tableName = "events",
    foreignKeys = [ForeignKey(entity = CalendarEntity::class, parentColumns = ["id"], childColumns = ["calendarId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("calendarId"), Index("startUtc"), Index("startDay"), Index("uid")],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    val calendarId: Long,
    val title: String,
    val location: String,
    val description: String,
    val colorKey: String?,
    val allDay: Boolean,
    val startUtc: Long?,
    val endUtc: Long?,
    val startDay: Long?,
    val endDay: Long?,
    val timeZone: String,
    val rrule: String?,
    val reminders: List<Int>,
    val isSample: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Changed or removed occurrence of a series, keyed by the occurrence it replaces. */
@Entity(
    tableName = "event_exceptions",
    foreignKeys = [ForeignKey(entity = EventEntity::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["eventId", "originalStart"], unique = true)],
)
data class EventExceptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long,
    val originalStart: Long,
    val cancelled: Boolean,
    val title: String?,
    val location: String?,
    val description: String?,
    val allDay: Boolean?,
    val startUtc: Long?,
    val endUtc: Long?,
    val startDay: Long?,
    val endDay: Long?,
)

/** Task with an optional local due date and time ("floating", independent of time zones). */
@Entity(
    tableName = "tasks",
    foreignKeys = [ForeignKey(entity = CalendarEntity::class, parentColumns = ["id"], childColumns = ["calendarId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("calendarId"), Index("dueDay"), Index("done")],
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val calendarId: Long,
    val title: String,
    val note: String,
    val dueDay: Long?,
    val dueMinute: Int?,
    val reminders: List<Int>,
    val done: Boolean,
    val completedAt: Long?,
    val isSample: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Fts4(contentEntity = EventEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "events_fts")
data class EventFts(val title: String, val location: String, val description: String)

@Fts4(contentEntity = TaskEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "tasks_fts")
data class TaskFts(val title: String, val note: String)

/** A reminder postponed with "10 minutes later"; persisted so it survives reboots. */
@Entity(tableName = "snoozes")
data class SnoozeEntity(
    @PrimaryKey val key: String,
    val kind: String,
    val refId: Long,
    val occurrenceStart: Long,
    val triggerAt: Long,
)
