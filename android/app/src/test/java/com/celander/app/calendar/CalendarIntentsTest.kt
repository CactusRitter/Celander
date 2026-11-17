package com.celander.app.calendar

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventTiming
import com.celander.app.testing.BERLIN
import com.celander.app.testing.allDayEvent
import com.celander.app.testing.timedEvent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CalendarIntentsTest {
    private val begin = LocalDateTime.of(2026, 11, 3, 14, 30).atZone(BERLIN).toInstant().toEpochMilli()
    private val end = LocalDateTime.of(2026, 11, 3, 16, 0).atZone(BERLIN).toInstant().toEpochMilli()

    @Test
    fun aDateFromAnEMailBecomesANewEvent() {
        // What Gmail and the text classifier send for "Add to calendar".
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            .putExtra(CalendarContract.Events.TITLE, "  Team-Essen ")
            .putExtra(CalendarContract.Events.EVENT_LOCATION, "Café Luise")
            .putExtra(CalendarContract.Events.DESCRIPTION, "Tisch für 6")

        assertEquals(
            EditorRequest.InsertEvent(begin, end, allDay = false, title = "Team-Essen", location = "Café Luise", description = "Tisch für 6"),
            CalendarIntents.insertRequest(intent),
        )
    }

    @Test
    fun valuesSentAsTextOrNumbersAreUnderstood() {
        val intent = Intent(Intent.ACTION_INSERT)
            .setType("vnd.android.cursor.item/event")
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin.toString())
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, 1)
            .putExtra(CalendarContract.Events.RRULE, "FREQ=WEEKLY")

        val request = CalendarIntents.insertRequest(intent)!!
        assertEquals(begin, request.beginMillis)
        assertEquals(true, request.allDay)
        assertEquals("FREQ=WEEKLY", request.rrule)
        assertEquals("", request.title)
    }

    @Test
    fun otherInsertsAreNotForTheCalendar() {
        assertNull(CalendarIntents.insertRequest(Intent(Intent.ACTION_INSERT).setType("vnd.android.cursor.dir/contact")))
        assertNull(CalendarIntents.insertRequest(Intent(Intent.ACTION_VIEW, CalendarContract.Events.CONTENT_URI)))
    }

    @Test
    fun aCalendarTimeOpensItsDay() {
        val uri = Uri.parse("content://com.android.calendar/time/$begin")
        assertEquals(LocalDate.of(2026, 11, 3).toEpochDay(), CalendarIntents.viewDay(Intent(Intent.ACTION_VIEW, uri), BERLIN))
        assertNull(CalendarIntents.viewDay(Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/events/7")), BERLIN))
        assertNull(CalendarIntents.viewDay(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/time/1")), BERLIN))
    }

    @Test
    fun anEventOfThePhonesCalendarsOpensToShowOrEdit() {
        val uri = Uri.parse("content://com.android.calendar/events/42")
        assertEquals(
            EventLink(42, begin, edit = false),
            CalendarIntents.eventLink(Intent(Intent.ACTION_VIEW, uri).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)),
        )
        // Some apps send the begin time as text, others not at all.
        assertEquals(
            EventLink(42, begin, edit = true),
            CalendarIntents.eventLink(
                Intent(Intent.ACTION_EDIT).setDataAndType(uri, "vnd.android.cursor.item/event")
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin.toString()),
            ),
        )
        assertEquals(EventLink(42, null, edit = false), CalendarIntents.eventLink(Intent(Intent.ACTION_VIEW, uri)))
        // A begin time no calendar can hold is ignored instead of overflowing later.
        assertEquals(
            EventLink(42, null, edit = false),
            CalendarIntents.eventLink(Intent(Intent.ACTION_VIEW, uri).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, Long.MAX_VALUE)),
        )
    }

    @Test
    fun otherLinksAreNotEvents() {
        assertNull(CalendarIntents.eventLink(Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/$begin"))))
        assertNull(CalendarIntents.eventLink(Intent(Intent.ACTION_VIEW, CalendarContract.Events.CONTENT_URI)))
        assertNull(CalendarIntents.eventLink(Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/events/abc"))))
        assertNull(CalendarIntents.eventLink(Intent(Intent.ACTION_VIEW, Uri.parse("content://com.example/events/42"))))
        assertNull(CalendarIntents.eventLink(Intent(Intent.ACTION_INSERT, Uri.parse("content://com.android.calendar/events/42"))))
    }

    @Test
    fun aSingleEventHasOneOccurrence() {
        val event = timedEvent(1, LocalDateTime.of(2026, 11, 3, 14, 30), 90)
        assertEquals(begin, CalendarIntents.occurrenceKey(event, emptyList(), null, now, BERLIN))
        assertEquals(begin, CalendarIntents.occurrenceKey(event, emptyList(), begin + 86_400_000, now, BERLIN))
    }

    @Test
    fun aSeriesOpensTheOccurrenceThatStartsThen() {
        // Weekly on Tuesdays from October 6, 14:30.
        val event = timedEvent(1, LocalDateTime.of(2026, 10, 6, 14, 30), 90, rrule = "FREQ=WEEKLY")
        assertEquals(begin, CalendarIntents.occurrenceKey(event, emptyList(), begin, now, BERLIN))
    }

    @Test
    fun aMovedOccurrenceIsFoundByItsNewStart() {
        val event = timedEvent(1, LocalDateTime.of(2026, 10, 6, 14, 30), 90, rrule = "FREQ=WEEKLY")
        val movedStart = LocalDateTime.of(2026, 11, 4, 9, 0).atZone(BERLIN).toInstant()
        val moved = EventException(
            eventId = 1,
            originalStart = begin,
            timing = EventTiming.Timed(movedStart, movedStart.plusSeconds(3600), BERLIN),
        )
        assertEquals(begin, CalendarIntents.occurrenceKey(event, listOf(moved), movedStart.toEpochMilli(), now, BERLIN))
    }

    @Test
    fun withoutAMatchingStartTheNextOccurrenceOpens() {
        val event = timedEvent(1, LocalDateTime.of(2026, 10, 6, 14, 30), 90, rrule = "FREQ=WEEKLY")
        val next = LocalDateTime.of(2026, 10, 27, 14, 30).atZone(BERLIN).toInstant().toEpochMilli()
        assertEquals(next, CalendarIntents.occurrenceKey(event, emptyList(), null, now, BERLIN))
        // Thursday is no occurrence of this series.
        val thursday = LocalDateTime.of(2026, 11, 5, 14, 30).atZone(BERLIN).toInstant().toEpochMilli()
        assertEquals(next, CalendarIntents.occurrenceKey(event, emptyList(), thursday, now, BERLIN))
        // A cancelled one neither.
        val cancelled = EventException(eventId = 1, originalStart = next, cancelled = true)
        val after = LocalDateTime.of(2026, 11, 3, 14, 30).atZone(BERLIN).toInstant().toEpochMilli()
        assertEquals(after, CalendarIntents.occurrenceKey(event, listOf(cancelled), next, now, BERLIN))
    }

    @Test
    fun anEndedSeriesOpensItsFirstOccurrence() {
        val event = timedEvent(1, LocalDateTime.of(2026, 1, 6, 14, 30), 90, rrule = "FREQ=WEEKLY;COUNT=3")
        assertEquals(event.timing.occurrenceKey, CalendarIntents.occurrenceKey(event, emptyList(), null, now, BERLIN))
    }

    @Test
    fun allDayOccurrencesAcceptUtcAndLocalMidnight() {
        val event = allDayEvent(1, LocalDate.of(2026, 10, 1), rrule = "FREQ=MONTHLY")
        val day = LocalDate.of(2026, 11, 1)
        val utcMidnight = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val localMidnight = day.atStartOfDay(BERLIN).toInstant().toEpochMilli()
        assertEquals(day.toEpochDay(), CalendarIntents.occurrenceKey(event, emptyList(), utcMidnight, now, BERLIN))
        assertEquals(day.toEpochDay(), CalendarIntents.occurrenceKey(event, emptyList(), localMidnight, now, BERLIN))
    }

    private val now = LocalDateTime.of(2026, 10, 23, 10, 10).atZone(BERLIN).toInstant()
}
