package com.celander.app.intake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun vevent(
    uid: String = "a@example",
    summary: String = "Dinner",
    start: String = "20261103T180000Z",
    end: String = "20261103T200000Z",
    vararg more: String,
) = listOf("BEGIN:VEVENT", "UID:$uid", "SUMMARY:$summary", "DTSTART:$start", "DTEND:$end", *more, "END:VEVENT").joinToString("\r\n")

internal fun ics(vararg parts: String, method: String? = null) =
    listOfNotNull("BEGIN:VCALENDAR", "VERSION:2.0", method?.let { "METHOD:$it" }, *parts, "END:VCALENDAR").joinToString("\r\n")

internal fun entry(text: String): IncomingEvent = (Incoming.readIcs(text) as IcsContent.Events).entries.single()

class IncomingTest {
    private val guests: (String) -> String = { "Guests: $it" }

    @Test
    fun standardExtrasAreTakenOver() {
        val request = Incoming.fromExtras(
            mapOf(
                "title" to "Lunch", "eventLocation" to "Kantine", "description" to "Menu", "beginTime" to 1_000L,
                "endTime" to "2000", "allDay" to false, "rrule" to "RRULE:FREQ=WEEKLY",
                "android.intent.extra.EMAIL" to arrayOf("a@x.test", "b@x.test"),
            ),
            guests,
        )!!
        assertEquals("Lunch", request.title)
        assertEquals("Kantine", request.location)
        assertEquals("Menu\n\nGuests: a@x.test, b@x.test", request.description)
        assertEquals(1_000L, request.beginMillis)
        assertEquals(2_000L, request.endMillis)
        assertEquals("FREQ=WEEKLY", request.rrule)
    }

    @Test
    fun extrasOfTheWrongKindAreIgnored() {
        assertNull(Incoming.fromExtras(emptyMap(), guests))
        assertNull(Incoming.fromExtras(mapOf("title" to 7, "beginTime" to "soon", "endTime" to Long.MAX_VALUE), guests))
    }

    @Test
    fun uidSequenceAndMethodAreRead() {
        val entry = entry(ics(vevent(more = arrayOf("SEQUENCE:3")), method = "REQUEST"))
        assertEquals("a@example", entry.uid)
        assertEquals(3, entry.sequence)
        assertFalse(entry.cancel)
        assertTrue(entry.editable)
    }

    @Test
    fun aCancellationIsRecognisedByMethodOrStatus() {
        assertTrue(entry(ics(vevent(), method = "CANCEL")).cancel)
        assertTrue(entry(ics(vevent(more = arrayOf("STATUS:CANCELLED")))).cancel)
    }

    @Test
    fun aReplyIsNothingToAdd() {
        assertEquals(IcsContent.Reply, Incoming.readIcs(ics(vevent(), method = "REPLY")))
    }

    @Test
    fun componentsOfOneUidBelongTogether() {
        val series = vevent(more = arrayOf("RRULE:FREQ=WEEKLY"))
        val moved = vevent(start = "20261110T190000Z", end = "20261110T200000Z", more = arrayOf("RECURRENCE-ID:20261110T180000Z"))
        val content = Incoming.readIcs(ics(series, moved, vevent(uid = "b@example"))) as IcsContent.Events
        assertEquals(listOf("a@example", "b@example"), content.entries.map { it.uid })
        assertEquals(1, content.entries.first().overrides.size)
        assertFalse(content.entries.first().editable)
    }

    @Test
    fun brokenAndOversizedFilesDoNotFail() {
        assertEquals(IcsContent.Unreadable, Incoming.readIcs(""))
        assertEquals(IcsContent.Unreadable, Incoming.readIcs("not a calendar\nBEGIN:VEVENT\nDTSTART:never\n"))
        assertEquals(IcsContent.TooLarge, Incoming.readIcs("x".repeat(Incoming.MAX_ICS_BYTES + 1)))
        // The absurd entry is left out, the sound one stays.
        val absurd = listOf("BEGIN:VEVENT", "UID:c", "DTSTART:20261103T180000Z", "DURATION:P999999999999999999W", "END:VEVENT").joinToString("\r\n")
        assertEquals("a@example", entry(ics(absurd, vevent())).uid)
    }

    @Test
    fun manyEntriesAreCapped() {
        val parts = Array(Incoming.MAX_ENTRIES + 5) { vevent(uid = "u$it") }
        val content = Incoming.readIcs(ics(*parts)) as IcsContent.Events
        assertEquals(Incoming.MAX_ENTRIES, content.entries.size)
        assertTrue(content.truncated)
    }

    @Test
    fun longTextsAreCutAndBadRulesDropped() {
        val entry = entry(ics(vevent(summary = "x".repeat(5_000), more = arrayOf("RRULE:nonsense"))))
        assertEquals(500, entry.shown.summary.length)
        assertNull(entry.shown.rrule)
    }
}
