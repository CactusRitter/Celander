package com.celander.app.calendar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the editor should open with. Primitive fields so it can be saved across process death. */
@Serializable
sealed interface EditorRequest {
    @Serializable
    @SerialName("new_event")
    data class NewEvent(
        val epochDay: Long,
        val minuteOfDay: Int? = null,
        val allDay: Boolean = false,
        val title: String = "",
        /** Which of two equal times was meant on the day the clocks go back (UTC offset in seconds). */
        val startOffsetSeconds: Int? = null,
    ) : EditorRequest

    @Serializable
    @SerialName("new_task")
    data class NewTask(val epochDay: Long? = null, val title: String = "") : EditorRequest

    @Serializable
    @SerialName("edit_event")
    data class EditEvent(val eventId: Long, val occurrenceStart: Long) : EditorRequest

    @Serializable
    @SerialName("duplicate_event")
    data class DuplicateEvent(val eventId: Long, val occurrenceStart: Long) : EditorRequest

    @Serializable
    @SerialName("edit_task")
    data class EditTask(val taskId: Long) : EditorRequest

    /**
     * A new event another app asked "the calendar app" for, e.g. a date tapped in an e-mail. Times
     * as Android's calendar intents send them: epoch millis; for all-day events midnight UTC, the
     * end exclusive. Anything missing gets the editor's usual defaults.
     */
    @Serializable
    @SerialName("insert_event")
    data class InsertEvent(
        val beginMillis: Long? = null,
        val endMillis: Long? = null,
        val allDay: Boolean = false,
        val title: String = "",
        val location: String = "",
        val description: String = "",
        val rrule: String? = null,
    ) : EditorRequest
}

/** The sheet currently shown above the home screen. */
@Serializable
sealed interface HomeSheet {
    @Serializable
    @SerialName("event_detail")
    data class EventDetail(val eventId: Long, val occurrenceStart: Long) : HomeSheet

    @Serializable
    @SerialName("editor")
    data class Editor(val request: EditorRequest) : HomeSheet

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(sheet: HomeSheet?): String? = sheet?.let { json.encodeToString(serializer(), it) }

        fun decode(value: String?): HomeSheet? = value?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}
