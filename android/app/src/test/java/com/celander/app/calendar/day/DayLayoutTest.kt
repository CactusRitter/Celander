package com.celander.app.calendar.day

import org.junit.Assert.assertEquals
import org.junit.Test

class DayLayoutTest {
    private fun item(key: String, start: Long, end: Long) = TimelineItem(key, start, end, key)

    private fun place(vararg items: TimelineItem<String>, minDuration: Long = 0) =
        DayLayout.place(items.toList(), minDuration).associate { it.item.key to (it.left to it.right) }

    private fun assertSpan(expected: Pair<Float, Float>, actual: Pair<Float, Float>?) {
        assertEquals(expected.first, actual!!.first, 0.001f)
        assertEquals(expected.second, actual.second, 0.001f)
    }

    @Test
    fun separateItemsUseTheFullWidth() {
        val placed = place(item("a", 60, 120), item("b", 120, 180))
        assertSpan(0f to 1f, placed["a"])
        assertSpan(0f to 1f, placed["b"])
    }

    @Test
    fun overlappingItemsSitSideBySide() {
        val placed = place(item("a", 60, 180), item("b", 90, 150))
        assertSpan(0f to 0.5f, placed["a"])
        assertSpan(0.5f to 1f, placed["b"])
    }

    @Test
    fun freedSpaceIsReusedNextToALongItem() {
        val placed = place(item("long", 0, 240), item("first", 0, 60), item("second", 60, 120))
        assertSpan(0f to 0.5f, placed["long"])
        assertSpan(0.5f to 1f, placed["first"])
        assertSpan(0.5f to 1f, placed["second"])
    }

    @Test
    fun shortItemsAreTreatedAsMinimumLength() {
        val placed = place(item("a", 60, 65), item("b", 70, 90), minDuration = 30)
        assertSpan(0f to 0.5f, placed["a"])
        assertSpan(0.5f to 1f, placed["b"])
    }

    @Test
    fun aBusyMorningDoesNotNarrowTheAfternoon() {
        // Three parallel items at 9:00, chained by overlaps to two overlapping items in the afternoon.
        val placed = place(
            item("p1", 540, 600), item("p2", 540, 600), item("p3", 540, 600),
            item("bridge", 570, 780),
            item("x", 750, 840), item("y", 810, 900),
        )
        // In the afternoon at most two items overlap, so none is narrower than half.
        listOf("x", "y").forEach { key -> assert(placed.getValue(key).let { it.second - it.first } >= 0.5f - 0.001f) { key } }
        // Parallel items never overlap each other.
        val morning = listOf("p1", "p2", "p3", "bridge").map { placed.getValue(it) }.sortedBy { it.first }
        morning.zipWithNext().forEach { (a, b) -> assert(a.second <= b.first + 0.001f) }
    }

    @Test
    fun aBusyDayNeverDrawsItemsOnTopOfEachOther() {
        // 14 meetings of 90 minutes every 45 minutes from 8:00, six parallel ones at 10:00, one at 12:00.
        val items = (0 until 14).map { item("m$it", 480L + it * 45, 570L + it * 45) } +
            (0 until 6).map { item("p$it", 600, 660) } +
            item("long", 720, 765)
        val placed = DayLayout.place(items, minDurationMinutes = 30)
        for (a in placed) {
            for (b in placed) {
                if (a === b) continue
                val overlapInTime = a.item.startMinute < b.item.endMinute && b.item.startMinute < a.item.endMinute
                val overlapInSpace = a.left < b.right - 0.001f && b.left < a.right - 0.001f
                assert(!(overlapInTime && overlapInSpace)) { "${a.item.key} and ${b.item.key} overlap" }
            }
        }
        assert(placed.all { it.right - it.left > 0.05f })
    }
}
