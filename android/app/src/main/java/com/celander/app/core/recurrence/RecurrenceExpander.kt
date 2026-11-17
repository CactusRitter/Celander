package com.celander.app.core.recurrence

import com.celander.app.core.ical.ICalDates
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object RecurrenceExpander {
    /** Default cap on the occurrences one expansion returns. */
    const val MAX_INSTANCES = 2_000
    private const val MAX_PERIODS = 60_000

    /**
     * Start times of the occurrences of a series that overlap `[rangeStart, rangeEnd)`.
     *
     * Occurrences are computed on the wall clock of [zone] (UTC for all-day events), so a weekly
     * 09:00 meeting stays at 09:00 across daylight-saving changes. DTSTART is always the first
     * occurrence and counts toward COUNT, as do occurrences removed by [exdates]. At most [limit]
     * occurrences are returned.
     */
    fun expand(
        start: Long,
        duration: Long,
        zone: ZoneId,
        allDay: Boolean,
        rule: RRule,
        exdates: Set<Long>,
        rangeStart: Long,
        rangeEnd: Long,
        limit: Int = MAX_INSTANCES,
    ): List<Long> {
        val z = if (allDay) ZoneOffset.UTC else zone
        val dtStart = Instant.ofEpochMilli(start).atZone(z).toLocalDateTime()
        val until = rule.until?.let { parseUntil(it, z) }
        val result = mutableListOf<Long>()
        var produced = 0

        fun accept(millis: Long): Boolean {
            if (until != null && millis > until) return false
            if (rule.count != null && produced >= rule.count) return false
            if (millis >= rangeEnd) return false
            produced++
            val overlaps = millis + duration > rangeStart || (duration <= 0 && millis >= rangeStart)
            if (overlaps && millis !in exdates) result += millis
            return result.size < limit
        }

        if (rule.freq.ordinal < Frequency.DAILY.ordinal) {
            val step = when (rule.freq) {
                Frequency.SECONDLY -> 1_000L
                Frequency.MINUTELY -> 60_000L
                else -> 3_600_000L
            } * rule.interval
            // These steps are exact durations, not wall-clock times, so without COUNT the steps that
            // end before the window can be skipped arithmetically.
            val skipped = if (rule.count == null) maxOf(0L, Math.floorDiv(rangeStart - duration - start, step)) else 0L
            var t = start + skipped * step
            var n = 0
            while (n++ < MAX_PERIODS && accept(t)) t += step
            return result
        }

        // Without COUNT the periods before the window cannot matter, so skip them.
        var period = 0L
        if (rule.count == null) {
            val from = Instant.ofEpochMilli(rangeStart - duration).atZone(z).toLocalDate().minusDays(1)
            val unit = when (rule.freq) {
                Frequency.DAILY -> ChronoUnit.DAYS
                Frequency.WEEKLY -> ChronoUnit.WEEKS
                Frequency.MONTHLY -> ChronoUnit.MONTHS
                else -> ChronoUnit.YEARS
            }
            period = (unit.between(dtStart.toLocalDate(), from) / rule.interval - 1).coerceAtLeast(0)
        }

        if (period == 0L && !accept(start)) return result
        val lastDate = Instant.ofEpochMilli(rangeEnd).atZone(z).toLocalDate().plusDays(1)
        var periods = 0
        while (periods++ < MAX_PERIODS) {
            if (periodStart(period, dtStart.toLocalDate(), rule).isAfter(lastDate)) break
            val dates = candidates(period, dtStart, rule)
            if (dates == null) break
            for (date in dates) {
                val occurrence = date.atTime(dtStart.toLocalTime())
                if (!occurrence.isAfter(dtStart)) continue
                if (!accept(occurrence.atZone(z).toInstant().toEpochMilli())) return result
            }
            period++
        }
        return result
    }

    /** Earliest date period [index] can produce. */
    private fun periodStart(index: Long, base: LocalDate, rule: RRule): LocalDate {
        val offset = index * rule.interval
        return when (rule.freq) {
            Frequency.DAILY -> base.plusDays(offset)
            Frequency.WEEKLY -> base.with(TemporalAdjusters.previousOrSame(rule.weekStart)).plusWeeks(offset)
            Frequency.MONTHLY -> YearMonth.from(base).plusMonths(offset).atDay(1)
            else -> LocalDate.of((base.year + offset).toInt().coerceAtMost(9999), 1, 1)
        }
    }

    /** Dates produced by period [index], or null once the series can no longer produce dates. */
    private fun candidates(index: Long, dtStart: LocalDateTime, rule: RRule): List<LocalDate>? {
        val base = dtStart.toLocalDate()
        val offset = index * rule.interval
        val dates: List<LocalDate> = when (rule.freq) {
            Frequency.DAILY -> {
                val d = base.plusDays(offset)
                if (d.year > 9000) return null
                listOf(d).filter { matchesMonth(it, rule) && matchesMonthDay(it, rule) && matchesWeekday(it, rule) }
            }
            Frequency.WEEKLY -> {
                val weekStart = base.with(TemporalAdjusters.previousOrSame(rule.weekStart)).plusWeeks(offset)
                if (weekStart.year > 9000) return null
                val days = if (rule.byDay.isEmpty()) listOf(base.dayOfWeek) else rule.byDay.map { it.day }.distinct()
                days.map { weekStart.plusDays(((it.value - rule.weekStart.value + 7) % 7).toLong()) }
                    .filter { matchesMonth(it, rule) }
            }
            Frequency.MONTHLY -> {
                val ym = YearMonth.from(base).plusMonths(offset)
                if (ym.year > 9000) return null
                if (rule.byMonth.isNotEmpty() && ym.monthValue !in rule.byMonth) emptyList() else monthDates(ym, base, rule)
            }
            else -> {
                val year = base.year + offset
                if (year > 9000) return null
                yearDates(year.toInt(), base, rule)
            }
        }
        val sorted = dates.distinct().sorted()
        if (rule.bySetPos.isEmpty()) return sorted
        return rule.bySetPos.mapNotNull { pos ->
            if (pos > 0) sorted.getOrNull(pos - 1) else sorted.getOrNull(sorted.size + pos)
        }.distinct().sorted()
    }

    private fun monthDates(ym: YearMonth, base: LocalDate, rule: RRule): List<LocalDate> {
        val length = ym.lengthOfMonth()
        val byMonthDay = rule.byMonthDay.mapNotNull { d ->
            val day = if (d > 0) d else length + d + 1
            if (day in 1..length) ym.atDay(day) else null
        }
        return when {
            rule.byMonthDay.isNotEmpty() && rule.byDay.isNotEmpty() ->
                byMonthDay.filter { date -> rule.byDay.any { it.day == date.dayOfWeek } }
            rule.byMonthDay.isNotEmpty() -> byMonthDay
            rule.byDay.isNotEmpty() -> {
                val all = (1..length).map(ym::atDay)
                rule.byDay.flatMap { wd -> pick(all.filter { it.dayOfWeek == wd.day }, wd.ordinal) }
            }
            base.dayOfMonth <= length -> listOf(ym.atDay(base.dayOfMonth))
            else -> emptyList()
        }
    }

    private fun yearDates(year: Int, base: LocalDate, rule: RRule): List<LocalDate> {
        if (rule.byMonth.isEmpty() && rule.byMonthDay.isEmpty() && rule.byDay.isNotEmpty()) {
            val all = generateSequence(LocalDate.of(year, 1, 1)) { it.plusDays(1) }.takeWhile { it.year == year }.toList()
            return rule.byDay.flatMap { wd -> pick(all.filter { it.dayOfWeek == wd.day }, wd.ordinal) }
        }
        // Without BYMONTH, BYMONTHDAY expands to every month of the year (RFC 5545 §3.3.10); only
        // a rule without BYxxx parts repeats DTSTART's month and day.
        val months = rule.byMonth.ifEmpty { if (rule.byMonthDay.isEmpty()) listOf(base.monthValue) else (1..12).toList() }
        if (rule.byMonthDay.isEmpty() && rule.byDay.isEmpty()) {
            return months.mapNotNull { m ->
                val ym = YearMonth.of(year, m)
                if (base.dayOfMonth <= ym.lengthOfMonth()) ym.atDay(base.dayOfMonth) else null
            }
        }
        return months.flatMap { monthDates(YearMonth.of(year, it), base, rule) }
    }

    private fun pick(days: List<LocalDate>, ordinal: Int): List<LocalDate> = when {
        ordinal == 0 -> days
        ordinal > 0 -> listOfNotNull(days.getOrNull(ordinal - 1))
        else -> listOfNotNull(days.getOrNull(days.size + ordinal))
    }

    private fun matchesMonth(d: LocalDate, rule: RRule) = rule.byMonth.isEmpty() || d.monthValue in rule.byMonth

    private fun matchesWeekday(d: LocalDate, rule: RRule) = rule.byDay.isEmpty() || rule.byDay.any { it.day == d.dayOfWeek }

    private fun matchesMonthDay(d: LocalDate, rule: RRule): Boolean {
        if (rule.byMonthDay.isEmpty()) return true
        val length = d.lengthOfMonth()
        return rule.byMonthDay.any { (if (it > 0) it else length + it + 1) == d.dayOfMonth }
    }

    /** UNTIL is inclusive; a bare date means the whole day in the series' zone. */
    private fun parseUntil(value: String, zone: ZoneId): Long? {
        val parsed = ICalDates.parse(value, null, null) ?: return null
        if (!parsed.allDay) {
            if (value.endsWith("Z", ignoreCase = true)) return parsed.millis
            return Instant.ofEpochMilli(parsed.millis).atZone(ZoneId.systemDefault()).toLocalDateTime()
                .atZone(zone).toInstant().toEpochMilli()
        }
        val date = Instant.ofEpochMilli(parsed.millis).atZone(ZoneOffset.UTC).toLocalDate()
        return date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    }
}
