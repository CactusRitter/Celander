package com.celander.app.widget

import com.celander.app.testing.BERLIN
import com.celander.app.testing.occurrence
import com.celander.app.testing.timedEvent
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The day widget's card: the event on now, else the next one, and when that changes. */
class DayWidgetFocusTest {
    private val standup = timedEvent(1, LocalDateTime.of(2026, 10, 25, 9, 0), 30).copy(title = "Standup").occurrence()
    private val lunch = timedEvent(2, LocalDateTime.of(2026, 10, 25, 12, 0), 60).copy(title = "Lunch").occurrence()
    private val review = timedEvent(3, LocalDateTime.of(2026, 10, 25, 12, 30), 60).copy(title = "Review").occurrence()
    private val day = listOf(review, lunch, standup)

    private fun at(hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, 25, hour, minute).atZone(BERLIN).toInstant()

    @Test
    fun theRunningEventComesBeforeTheNextOne() {
        assertEquals("Standup", nowOrNext(day, at(8))?.title)
        assertEquals("Standup", nowOrNext(day, at(9, 10))?.title)
        assertEquals("Lunch", nowOrNext(day, at(9, 30))?.title)
        assertEquals("Lunch", nowOrNext(day, at(12, 45))?.title)
        assertNull(nowOrNext(day, at(14)))
    }

    @Test
    fun theCardChangesAtTheNextStartOrEnd() {
        assertEquals(at(9), nextChange(day, at(8)))
        assertEquals(at(9, 30), nextChange(day, at(9, 10)))
        assertEquals(at(12, 30), nextChange(day, at(12, 10)))
        assertNull(nextChange(day, at(14)))
    }
}
