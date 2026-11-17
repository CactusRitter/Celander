package com.celander.app.core.recurrence

import com.celander.app.core.ical.IcsWriter
import java.time.DayOfWeek

enum class Frequency { SECONDLY, MINUTELY, HOURLY, DAILY, WEEKLY, MONTHLY, YEARLY }

/** A BYDAY entry such as `MO`, `2TU` or `-1FR` ([ordinal] 0 means every such weekday). */
data class WeekdayNum(val ordinal: Int, val day: DayOfWeek)

/** Parsed RFC 5545 recurrence rule (the subset calendar clients use in practice). */
data class RRule(
    val freq: Frequency,
    val interval: Int = 1,
    val count: Int? = null,
    val until: String? = null,
    val byDay: List<WeekdayNum> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
    val byMonth: List<Int> = emptyList(),
    val bySetPos: List<Int> = emptyList(),
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    /** Parts this parser does not interpret (e.g. BYWEEKNO, BYHOUR), kept verbatim and in order. */
    val extras: List<Pair<String, String>> = emptyList(),
) {
    /** True when the rule is just a frequency, optionally with an end date. */
    val isSimple: Boolean
        get() = interval == 1 && count == null && byDay.isEmpty() && byMonthDay.isEmpty() &&
            byMonth.isEmpty() && bySetPos.isEmpty() && extras.isEmpty()

    fun format(): String = buildList {
        add("FREQ=${freq.name}")
        if (interval != 1) add("INTERVAL=$interval")
        if (count != null) add("COUNT=$count")
        if (until != null) add("UNTIL=$until")
        if (byDay.isNotEmpty()) {
            add("BYDAY=" + byDay.joinToString(",") { (if (it.ordinal != 0) it.ordinal.toString() else "") + IcsWriter.dayCode(it.day) })
        }
        if (byMonthDay.isNotEmpty()) add("BYMONTHDAY=${byMonthDay.joinToString(",")}")
        if (byMonth.isNotEmpty()) add("BYMONTH=${byMonth.joinToString(",")}")
        if (bySetPos.isNotEmpty()) add("BYSETPOS=${bySetPos.joinToString(",")}")
        if (weekStart != DayOfWeek.MONDAY) add("WKST=${IcsWriter.dayCode(weekStart)}")
        extras.forEach { (key, value) -> add("$key=$value") }
    }.joinToString(";")

    companion object {
        private val DAYS = DayOfWeek.entries.associateBy { IcsWriter.dayCode(it) }
        /** Bounds for values from damaged files, the same on every platform; no real calendar comes close. */
        const val MAX_INTERVAL = 10_000
        const val MAX_COUNT = 100_000

        private val KNOWN = setOf("FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "BYMONTHDAY", "BYMONTH", "BYSETPOS", "WKST")

        fun parse(value: String): RRule? {
            val pairs = value.removePrefix("RRULE:").split(';')
                .mapNotNull { part ->
                    val eq = part.indexOf('=')
                    if (eq <= 0) null else part.substring(0, eq).trim().uppercase() to part.substring(eq + 1).trim()
                }
            val parts = pairs.toMap()
            val freq = parts["FREQ"]?.uppercase()?.let { f -> Frequency.entries.firstOrNull { it.name == f } }
                ?: return null
            fun ints(key: String) = parts[key]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
            return RRule(
                freq = freq,
                interval = parts["INTERVAL"]?.toIntOrNull()?.coerceIn(1, MAX_INTERVAL) ?: 1,
                count = parts["COUNT"]?.toIntOrNull()?.coerceIn(1, MAX_COUNT),
                until = parts["UNTIL"],
                byDay = parts["BYDAY"]?.split(',')?.mapNotNull(::parseWeekday).orEmpty(),
                byMonthDay = ints("BYMONTHDAY").filter { it != 0 && it in -31..31 },
                byMonth = ints("BYMONTH").filter { it in 1..12 },
                bySetPos = ints("BYSETPOS").filter { it != 0 },
                weekStart = parts["WKST"]?.uppercase()?.let(DAYS::get) ?: DayOfWeek.MONDAY,
                extras = pairs.filter { (key, _) -> key !in KNOWN },
            )
        }

        private fun parseWeekday(token: String): WeekdayNum? {
            val t = token.trim().uppercase()
            if (t.length < 2) return null
            val day = DAYS[t.takeLast(2)] ?: return null
            val ordinal = t.dropLast(2).let { if (it.isEmpty()) 0 else it.toIntOrNull() ?: return null }
            return WeekdayNum(ordinal, day)
        }
    }
}
