package com.celander.app.core.googletasks

import com.celander.app.core.model.Task
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The task fields that are synced with Google. Used as the local state, the remote state and the
 * base snapshot of the last agreed state. [completedAt] only counts while [done] is set.
 */
data class TaskFields(
    val title: String,
    val note: String,
    val dueDay: LocalDate?,
    val done: Boolean,
    val completedAt: Long?,
) {
    /** Done flag and completion time compared as one unit. */
    fun sameCompletion(other: TaskFields): Boolean =
        done == other.done && (!done || completedAt == other.completedAt)
}

/** Converts between Google's task JSON and Celander's task fields. */
object GoogleTaskMapper {
    const val STATUS_DONE = "completed"
    const val STATUS_OPEN = "needsAction"
    const val MAX_TITLE = 1024
    const val MAX_NOTES = 8192

    private val RFC_3339: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

    fun fields(task: Task): TaskFields = TaskFields(
        title = task.title,
        note = task.note,
        dueDay = task.dueDate,
        done = task.done,
        completedAt = if (task.done) task.completedAt else null,
    )

    /**
     * The remote task's fields. The due date is read from the date part only, never shifted into a
     * time zone; a completed task without a completion time falls back to its last update.
     */
    fun fields(task: GTask): TaskFields {
        val done = task.status == STATUS_DONE
        return TaskFields(
            title = task.title.orEmpty(),
            note = task.notes.orEmpty(),
            dueDay = task.due?.let(::parseDue),
            done = done,
            completedAt = if (done) (task.completed ?: task.updated)?.let(::parseTime) else null,
        )
    }

    /** Milliseconds of an RFC 3339 timestamp, or null when it cannot be read. */
    fun parseTime(text: String): Long? = try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    fun formatTime(millis: Long): String = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).format(RFC_3339)

    fun parseDue(text: String): LocalDate? = try {
        LocalDate.parse(text.take(10))
    } catch (_: DateTimeParseException) {
        null
    }

    fun formatDue(day: LocalDate): String = "${day}T00:00:00.000Z"

    /** Body of an insert with every synced field; an unset date or open completion is left out. */
    fun insertBody(fields: TaskFields): JsonObject = buildJsonObject {
        put("title", fields.title.take(MAX_TITLE))
        put("notes", fields.note.take(MAX_NOTES))
        fields.dueDay?.let { put("due", formatDue(it)) }
        putCompletion(fields)
    }

    /**
     * Body of a PATCH with only the fields of [current] that differ from [base]. A cleared date is
     * sent as `"due": null`, reopening as `"status": "needsAction", "completed": null`. Empty when
     * nothing differs.
     */
    fun patchBody(base: TaskFields, current: TaskFields): JsonObject = buildJsonObject {
        if (current.title != base.title) put("title", current.title.take(MAX_TITLE))
        if (current.note != base.note) put("notes", current.note.take(MAX_NOTES))
        if (current.dueDay != base.dueDay) put("due", current.dueDay?.let(::formatDue))
        if (!current.sameCompletion(base)) putCompletion(current)
    }

    private fun JsonObjectBuilder.putCompletion(fields: TaskFields) {
        if (fields.done) {
            put("status", STATUS_DONE)
            fields.completedAt?.let { put("completed", formatTime(it)) }
        } else {
            put("status", STATUS_OPEN)
            put("completed", null as String?)
        }
    }
}
