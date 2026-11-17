package com.celander.app.calendar.day

/** An item on the timeline, in minutes since the start of the day (DST-aware elapsed time). */
data class TimelineItem<T>(val key: String, val startMinute: Long, val endMinute: Long, val payload: T)

/** Horizontal position of an item as fractions of the timeline width, [left] to [right]. */
data class PlacedItem<T>(val item: TimelineItem<T>, val left: Float, val right: Float)

/**
 * Lays out overlapping timeline items next to each other, never on top of each other. Items are
 * treated as at least [minDurationMinutes] long so short entries stay readable.
 *
 * Widths follow each item's own overlaps (the approach of common web calendars): an item reaches
 * to the nearest item that really overlaps it further right. So six parallel meetings in the
 * morning do not squeeze two overlapping meetings in the afternoon into sixths.
 */
object DayLayout {
    private class Segment<T>(val item: TimelineItem<T>, val start: Long, val end: Long) {
        var level = 0
        var forward: List<Segment<T>> = emptyList()
        var pressure = 0
        var forwardCoord: Float? = null
        var backwardCoord = 0f

        fun collides(other: Segment<*>) = start < other.end && other.start < end
    }

    fun <T> place(items: List<TimelineItem<T>>, minDurationMinutes: Long): List<PlacedItem<T>> {
        val segments = items
            .sortedWith(compareBy<TimelineItem<T>>({ it.startMinute }, { -(it.endMinute - it.startMinute) }, { it.key }))
            .map { Segment(it, it.startMinute, maxOf(it.endMinute, it.startMinute + minDurationMinutes)) }

        // Levels: each item goes to the leftmost level where it overlaps nothing.
        val levels = mutableListOf<MutableList<Segment<T>>>()
        for (segment in segments) {
            val level = levels.indexOfFirst { level -> level.none { it.collides(segment) } }
            if (level >= 0) {
                levels[level] += segment
                segment.level = level
            } else {
                levels += mutableListOf(segment)
                segment.level = levels.lastIndex
            }
        }

        // Forward neighbours: overlapping items in the nearest level to the right that has any.
        for ((index, level) in levels.withIndex()) {
            for (segment in level) {
                segment.forward = levels.drop(index + 1).firstNotNullOfOrNull { next -> next.filter { it.collides(segment) }.ifEmpty { null } }.orEmpty()
            }
        }
        // Pressure: how many items still have to fit to the right of this one.
        for (level in levels.asReversed()) {
            for (segment in level) segment.pressure = segment.forward.maxOfOrNull { it.pressure + 1 } ?: 0
        }

        levels.firstOrNull()?.forEach { coordinates(it, seriesBackwardPressure = 0, seriesBackwardCoord = 0f) }
        segments.filter { it.forwardCoord == null }.forEach { coordinates(it, 0, 0f) }

        // In staggered chains an item can reach under a neighbour that was positioned from another
        // series; move its left edge past every overlapping item further left.
        for (level in levels) {
            for (segment in level) {
                val clear = segments.filter { it.level < segment.level && it.collides(segment) }.maxOfOrNull { it.forwardCoord ?: 1f } ?: continue
                if (clear > segment.backwardCoord && clear < (segment.forwardCoord ?: 1f)) segment.backwardCoord = clear
            }
        }
        return segments.map { PlacedItem(it.item, it.backwardCoord, it.forwardCoord ?: 1f) }
    }

    private fun <T> coordinates(segment: Segment<T>, seriesBackwardPressure: Int, seriesBackwardCoord: Float) {
        if (segment.forwardCoord != null) return
        val forward = segment.forward.sortedWith(compareByDescending<Segment<T>> { it.pressure }.thenBy { it.backwardCoord }.thenBy { it.start })
        val forwardCoord = if (forward.isEmpty()) {
            1f
        } else {
            coordinates(forward.first(), seriesBackwardPressure + 1, seriesBackwardCoord)
            forward.first().backwardCoord
        }
        segment.forwardCoord = forwardCoord
        segment.backwardCoord = forwardCoord - (forwardCoord - seriesBackwardCoord) / (seriesBackwardPressure + 1)
        forward.forEach { coordinates(it, 0, forwardCoord) }
    }
}
