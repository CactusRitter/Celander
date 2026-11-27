package com.celander.app.intake

import com.celander.app.calendar.EditorRequest
import com.celander.app.core.ical.ICalEvent
import com.celander.app.core.ical.IcsParser
import com.celander.app.core.recurrence.RRule

/**
 * What other apps hand to Celander as "the calendar app": Android's insert intent and .ics files.
 * None of it is trusted: sizes are limited and anything unreadable is left out.
 */
object Incoming {
    /** The full text of an .ics sent along with an insert intent (Cemail); it wins over the standard extras. */
    const val EXTRA_ICS = "com.celander.app.extra.ICS"

    /** The most an .ics may weigh, and the most entries of one file that are offered. */
    const val MAX_ICS_BYTES = 1_000_000
    const val MAX_ENTRIES = 100

    private const val MAX_LINE = 500
    private const val MAX_TEXT = 10_000
    private const val MAX_EXCEPTIONS = 500
    private const val MAX_SPAN_MS = 3_660L * 86_400_000L

    /** The latest moment taken seriously (year 3000); also keeps date arithmetic away from overflows. */
    private const val MAX_MILLIS = 32_503_680_000_000L

    private val REPLIES = setOf("REPLY", "REFRESH", "COUNTER", "DECLINECOUNTER")

    /**
     * The standard extras of an insert intent (`CalendarContract`), read leniently: apps send times
     * as numbers or text, guests as text or array. [guestsLine] writes the guests into the notes,
     * as Celander has no guest list of its own. Null if there is nothing to take over.
     */
    fun fromExtras(extras: Map<String, Any?>, guestsLine: (String) -> String): EditorRequest.InsertEvent? {
        fun text(key: String, max: Int) = (extras[key] as? CharSequence)?.toString()?.trim()?.take(max).orEmpty()
        fun millis(key: String) = when (val value = extras[key]) {
            is Number -> value.toLong()
            is CharSequence -> value.toString().trim().toLongOrNull()
            else -> null
        }?.takeIf { it in -MAX_MILLIS..MAX_MILLIS }

        val guests = when (val value = extras["android.intent.extra.EMAIL"]) {
            is Array<*> -> value.filterIsInstance<CharSequence>().map { it.toString() }
            is Iterable<*> -> value.filterIsInstance<CharSequence>().map { it.toString() }
            is CharSequence -> value.toString().split(',', ';')
            else -> emptyList()
        }.map { it.trim() }.filter { it.isNotEmpty() }.take(100)

        val allDay = when (val value = extras["allDay"]) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is CharSequence -> value.toString().equals("true", ignoreCase = true) || value.toString() == "1"
            else -> false
        }
        val begin = millis("beginTime")
        val end = millis("endTime")?.takeIf { begin == null || it - begin <= MAX_SPAN_MS }
        val notes = listOf(
            text("description", MAX_TEXT),
            if (guests.isEmpty()) "" else guestsLine(guests.joinToString(", ")).take(MAX_TEXT),
        ).filter { it.isNotEmpty() }.joinToString("\n\n")
        val request = EditorRequest.InsertEvent(
            beginMillis = begin,
            endMillis = end,
            allDay = allDay,
            title = text("title", MAX_LINE),
            location = text("eventLocation", MAX_LINE),
            description = notes,
            rrule = text("rrule", MAX_LINE).removePrefix("RRULE:").takeIf { it.isNotEmpty() },
        )
        return request.takeIf { it != EditorRequest.InsertEvent() }
    }

    /** Reads the text of an .ics file; never throws. */
    fun readIcs(text: String): IcsContent {
        if (text.length > MAX_ICS_BYTES) return IcsContent.TooLarge
        val calendar = runCatching { IcsParser.parseCalendar(text.removePrefix(BOM)) }.getOrNull() ?: return IcsContent.Unreadable
        if (calendar.method in REPLIES) return IcsContent.Reply
        val usable = calendar.events.mapNotNull(::sanitize)
        val entries = usable.groupBy { it.uid }.values.map { items ->
            val master = items.filter { it.recurrenceId == null }.maxByOrNull { it.sequence }
            val overrides = items.filter { it.recurrenceId != null }.take(MAX_EXCEPTIONS)
            IncomingEvent(
                uid = items.first().uid,
                master = master,
                overrides = overrides,
                cancel = calendar.method == "CANCEL" || (master?.cancelled ?: overrides.all { it.cancelled }),
                sequence = (listOfNotNull(master) + overrides).maxOf { it.sequence },
            )
        }
        if (entries.isEmpty()) return IcsContent.Unreadable
        return IcsContent.Events(entries.take(MAX_ENTRIES), truncated = entries.size > MAX_ENTRIES)
    }

    private val BOM = 0xFEFF.toChar().toString()

    private fun sanitize(event: ICalEvent): ICalEvent? {
        if (event.start !in -MAX_MILLIS..MAX_MILLIS || event.end !in -MAX_MILLIS..MAX_MILLIS) return null
        if (event.end - event.start > MAX_SPAN_MS) return null
        return event.copy(
            uid = event.uid.take(MAX_LINE),
            summary = event.summary.trim().take(MAX_LINE),
            location = event.location.trim().take(MAX_LINE),
            description = event.description.trim().take(MAX_TEXT),
            rrule = event.rrule?.take(MAX_LINE)?.takeIf { rule -> runCatching { RRule.parse(rule) != null }.getOrDefault(false) },
            exdates = event.exdates.filter { it in -MAX_MILLIS..MAX_MILLIS }.take(MAX_EXCEPTIONS),
            recurrenceId = event.recurrenceId?.takeIf { it in -MAX_MILLIS..MAX_MILLIS },
            extra = null,
        )
    }
}

sealed interface IcsContent {
    data class Events(val entries: List<IncomingEvent>, val truncated: Boolean = false) : IcsContent

    /** An answer to an invitation (METHOD:REPLY and the like): nothing for the calendar. */
    data object Reply : IcsContent

    data object Unreadable : IcsContent

    data object TooLarge : IcsContent
}

/** All components of one UID: the event or series and its changed occurrences. */
data class IncomingEvent(
    val uid: String,
    val master: ICalEvent?,
    val overrides: List<ICalEvent>,
    val cancel: Boolean,
    val sequence: Int,
) {
    /** The component that stands for the entry when it is shown. */
    val shown: ICalEvent get() = master ?: overrides.first()

    /** The UID under which a single occurrence without its series is kept (see `IcsMapper.fromIcal`). */
    val orphanUid: String? get() = if (master == null) overrides.singleOrNull()?.let { "$uid-${it.recurrenceId}" } else null

    /** Whether the editor can show everything of it, so "edit before adding" loses nothing. */
    val editable: Boolean get() = !cancel && master != null && overrides.isEmpty() && master.exdates.isEmpty()

    fun toInsertRequest() = EditorRequest.InsertEvent(
        beginMillis = shown.start,
        endMillis = shown.end,
        allDay = shown.allDay,
        title = shown.summary,
        location = shown.location,
        description = shown.description,
        rrule = shown.rrule,
        uid = uid,
    )
}
