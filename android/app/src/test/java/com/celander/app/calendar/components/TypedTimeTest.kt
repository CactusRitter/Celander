package com.celander.app.calendar.components

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypedTimeTest {
    private val morning = LocalTime.of(9, 0)
    private val afternoon = LocalTime.of(14, 30)

    @Test
    fun anyMinuteCanBeTyped() {
        assertEquals(LocalTime.of(14, 33), parseTypedTime("1433", use24h = true, morning))
        assertEquals(LocalTime.of(14, 33), parseTypedTime("14:33", use24h = true, morning))
        assertEquals(LocalTime.of(9, 7), parseTypedTime("9:07", use24h = true, afternoon))
        assertEquals(LocalTime.of(0, 1), parseTypedTime("00:01", use24h = true, afternoon))
    }

    @Test
    fun threeDigitsWaitWhileATwoDigitHourCouldFollow() {
        assertNull(parseTypedTime("213", use24h = true, morning))
        assertEquals(LocalTime.of(2, 13), parseTypedTime("213", use24h = true, morning, finished = true))
        assertEquals(LocalTime.of(9, 33), parseTypedTime("933", use24h = true, morning))
    }

    @Test
    fun incompleteOrImpossibleEntriesPickNothing() {
        for (text in listOf("", "1", "14", "14:3", "2460", "1490", "12345", ":30")) {
            assertNull(text, parseTypedTime(text, use24h = true, morning, finished = true))
        }
    }

    @Test
    fun twelveHourEntriesKeepTheHalfOfTheDay() {
        assertEquals(LocalTime.of(14, 33), parseTypedTime("2:33", use24h = false, afternoon))
        assertEquals(LocalTime.of(2, 33), parseTypedTime("2:33", use24h = false, morning))
        assertEquals(LocalTime.of(12, 5), parseTypedTime("12:05", use24h = false, afternoon))
        assertEquals(LocalTime.of(0, 5), parseTypedTime("12:05", use24h = false, morning))
        assertNull(parseTypedTime("13:05", use24h = false, afternoon))
    }
}
