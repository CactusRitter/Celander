package com.celander.app.event

import com.celander.app.core.model.EntryKind
import com.celander.app.core.model.EventTiming
import com.celander.app.testing.BERLIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class EditorDraftTest {
    private fun draft(allDay: Boolean, start: LocalDateTime, end: LocalDateTime, zone: String = BERLIN.id) = EditorDraft(
        requestKey = "test",
        kind = EntryKind.EVENT,
        allDay = allDay,
        startDay = start.toLocalDate().toEpochDay(),
        startMinute = start.hour * 60 + start.minute,
        endDay = end.toLocalDate().toEpochDay(),
        endMinute = end.hour * 60 + end.minute,
        timeZone = zone,
    )

    @Test
    fun timedDraftIsReadInTheDeviceZoneAndKeepsItsOwnZone() {
        val newYork = ZoneId.of("America/New_York")
        val timing = draft(false, LocalDateTime.of(2026, 10, 23, 9, 0), LocalDateTime.of(2026, 10, 23, 10, 30), newYork.id)
            .timing(BERLIN) as EventTiming.Timed
        assertEquals(LocalDateTime.of(2026, 10, 23, 9, 0).atZone(BERLIN).toInstant(), timing.start)
        assertEquals(90L, java.time.Duration.between(timing.start, timing.end).toMinutes())
        assertEquals(newYork, timing.zone)
    }

    @Test
    fun anEventMustLastAtLeastAMinute() {
        val start = LocalDateTime.of(2026, 10, 25, 14, 33)
        assertTrue(draft(false, start, start).endsTooEarly)
        assertTrue(draft(false, start, start.minusMinutes(5)).endsTooEarly)
        assertFalse(draft(false, start, start.plusMinutes(1)).endsTooEarly)
        assertFalse(draft(true, start, start).endsTooEarly)
        assertTrue(draft(true, start, start.minusDays(1)).endsTooEarly)
        assertFalse(draft(false, start, start).copy(kind = EntryKind.TASK).endsTooEarly)
    }

    @Test
    fun allDayDraftNeverEndsBeforeItStarts() {
        val timing = draft(true, LocalDateTime.of(2026, 10, 23, 9, 0), LocalDateTime.of(2026, 10, 21, 10, 0)).timing(BERLIN)
        assertEquals(EventTiming.AllDay(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 23)), timing)
    }

    @Test
    fun unknownZoneFallsBackToDeviceZone() {
        val timing = draft(false, LocalDateTime.of(2026, 10, 23, 9, 0), LocalDateTime.of(2026, 10, 23, 10, 0), "Not/AZone")
            .timing(BERLIN) as EventTiming.Timed
        assertEquals(BERLIN, timing.zone)
    }
}
