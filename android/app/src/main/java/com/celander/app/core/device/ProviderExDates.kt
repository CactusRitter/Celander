package com.celander.app.core.device

import com.celander.app.core.ical.ICalDateTime
import com.celander.app.core.ical.ICalDates
import java.time.ZoneId

/**
 * The calendar provider's EXDATE column: a comma-separated value list, optionally prefixed with
 * `TZID;` (`"Europe/Berlin;20261023T100000,20261030T100000"`), otherwise UTC
 * (`"20261023T100000Z"`) or dates (`"20261023"`). Several EXDATE properties are joined by newlines.
 */
object ProviderExDates {
    private const val DAY_MS = 86_400_000L

    /** All excluded instants, or null when any part cannot be read faithfully. Blank means none. */
    fun parse(value: String): List<ICalDateTime>? {
        val result = mutableListOf<ICalDateTime>()
        for (line in value.split('\n').map(String::trim).filter(String::isNotEmpty)) {
            val semicolon = line.indexOf(';')
            val tzid = if (semicolon >= 0) line.substring(0, semicolon).trim() else "UTC"
            if (runCatching { ZoneId.of(tzid, ZoneId.SHORT_IDS) }.isFailure) return null
            val values = (if (semicolon >= 0) line.substring(semicolon + 1) else line)
                .split(',').map(String::trim).filter(String::isNotEmpty)
            for (v in values) result += ICalDates.parse(v, tzid, null) ?: return null
        }
        return result
    }

    /**
     * Occurrence keys the exclusions remove: epoch days for an all-day series, epoch millis
     * otherwise (as in `EventTiming.occurrenceKey`). Null when [value] is unparseable.
     */
    fun keys(value: String, allDaySeries: Boolean): List<Long>? = parse(value)?.map {
        if (allDaySeries) Math.floorDiv(it.millis, DAY_MS) else it.millis
    }

    /** The UTC list form for the given instants, sorted and without duplicates; null when empty. */
    fun format(millis: Collection<Long>): String? =
        millis.distinct().sorted().takeIf { it.isNotEmpty() }?.joinToString(",") { ICalDates.formatUtc(it) }
}
