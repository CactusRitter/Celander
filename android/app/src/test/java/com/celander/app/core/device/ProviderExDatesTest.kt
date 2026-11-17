package com.celander.app.core.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ProviderExDatesTest {
    private fun at(text: String, zone: ZoneId) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun utcListsAreParsed() {
        val parsed = ProviderExDates.parse("20261023T080000Z,20261030T090000Z")!!
        assertEquals(listOf(at("2026-10-23T08:00", ZoneOffset.UTC), at("2026-10-30T09:00", ZoneOffset.UTC)), parsed.map { it.millis })
        assertTrue(parsed.none { it.allDay })
    }

    @Test
    fun valuesWithoutZoneAndPrefixAreUtc() {
        assertEquals(listOf(at("2026-10-23T08:00", ZoneOffset.UTC)), ProviderExDates.parse("20261023T080000")!!.map { it.millis })
    }

    @Test
    fun tzidPrefixAppliesToEveryValue() {
        val parsed = ProviderExDates.parse("Europe/Berlin;20261023T100000,20261030T100000")!!
        // The second date lies after the October DST change, so the UTC offset differs.
        assertEquals(listOf(at("2026-10-23T10:00", berlin), at("2026-10-30T10:00", berlin)), parsed.map { it.millis })
    }

    @Test
    fun datesAreAllDayUtcMidnights() {
        val parsed = ProviderExDates.parse("20261023,20261024")!!
        assertTrue(parsed.all { it.allDay })
        assertEquals(listOf(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 24)).map { it.toEpochDay() * 86_400_000L }, parsed.map { it.millis })
    }

    @Test
    fun severalLinesAreJoined() {
        val parsed = ProviderExDates.parse("Europe/Berlin;20261023T100000\n20261101T090000Z")!!
        assertEquals(listOf(at("2026-10-23T10:00", berlin), at("2026-11-01T09:00", ZoneOffset.UTC)), parsed.map { it.millis })
    }

    @Test
    fun blankMeansNoExclusions() {
        assertEquals(emptyList<Long>(), ProviderExDates.parse("")!!.map { it.millis })
        assertEquals(emptyList<Long>(), ProviderExDates.parse(" , \n")!!.map { it.millis })
    }

    @Test
    fun unparseableValuesYieldNull() {
        assertNull(ProviderExDates.parse("garbage"))
        assertNull(ProviderExDates.parse("20261023T080000Z,tomorrow"))
        assertNull(ProviderExDates.parse("Mars/Olympus;20261023T100000"))
        assertNull(ProviderExDates.parse("20261340T080000Z"))
        assertNull(ProviderExDates.keys("garbage", allDaySeries = false))
    }

    @Test
    fun keysMatchOccurrenceKeys() {
        val value = "20261023T000000Z,20261024"
        val day = LocalDate.of(2026, 10, 23).toEpochDay()
        assertEquals(listOf(day, day + 1), ProviderExDates.keys(value, allDaySeries = true))
        assertEquals(listOf(day * 86_400_000L, (day + 1) * 86_400_000L), ProviderExDates.keys(value, allDaySeries = false))
    }

    @Test
    fun formatWritesSortedDistinctUtcValues() {
        val a = at("2026-10-30T10:00", berlin)
        val b = at("2026-10-23T10:00", berlin)
        assertEquals("20261023T080000Z,20261030T090000Z", ProviderExDates.format(listOf(a, b, a)))
        assertNull(ProviderExDates.format(emptyList()))
    }

    @Test
    fun formatRoundTrips() {
        val millis = listOf(at("2026-03-29T01:30", ZoneOffset.UTC), at("2026-12-31T23:59:59", ZoneOffset.UTC))
        assertEquals(millis, ProviderExDates.parse(ProviderExDates.format(millis)!!)!!.map { it.millis })
    }
}
