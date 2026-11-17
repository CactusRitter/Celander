package com.celander.app.core.ical

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class ICalDateTime(val millis: Long, val allDay: Boolean, val zone: ZoneId)

object ICalDates {
    private val DATE = DateTimeFormatter.BASIC_ISO_DATE
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun parse(value: String, tzid: String?, valueType: String?): ICalDateTime? {
        val v = value.trim()
        return runCatching {
            if (valueType.equals("DATE", ignoreCase = true) || (v.length == 8 && v.all(Char::isDigit))) {
                val date = LocalDate.parse(v, DATE)
                ICalDateTime(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true, ZoneOffset.UTC)
            } else {
                val utc = v.endsWith("Z", ignoreCase = true)
                var raw = v.removeSuffix("Z").removeSuffix("z")
                if (raw.length == 13) raw += "00" // tolerate missing seconds
                val local = LocalDateTime.parse(raw, DATE_TIME)
                val zone = when {
                    utc -> ZoneOffset.UTC
                    tzid != null -> TimeZones.resolve(tzid)
                    else -> ZoneId.systemDefault()
                }
                ICalDateTime(local.atZone(zone).toInstant().toEpochMilli(), false, zone)
            }
        }.getOrNull()
    }

    fun parse(line: ContentLine): ICalDateTime? = parse(line.value, line.param("TZID"), line.param("VALUE"))

    /** Values of a multi-valued property such as EXDATE. */
    fun parseList(line: ContentLine): List<ICalDateTime> =
        line.value.split(',').filter { it.isNotBlank() }.mapNotNull { parse(it, line.param("TZID"), line.param("VALUE")) }

    /** Returns `;PARAMS` and the value for a DTSTART-like property. */
    fun format(millis: Long, allDay: Boolean, timeZone: String): Pair<String, String> {
        val instant = Instant.ofEpochMilli(millis)
        if (allDay) return ";VALUE=DATE" to instant.atZone(ZoneOffset.UTC).toLocalDate().format(DATE)
        val zone = TimeZones.resolve(timeZone)
        if (TimeZones.isUtc(zone)) return "" to instant.atZone(ZoneOffset.UTC).format(DATE_TIME) + "Z"
        return ";TZID=${zone.id}" to instant.atZone(zone).format(DATE_TIME)
    }

    fun formatUtc(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).format(DATE_TIME) + "Z"

    private val DURATION = Regex("""([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""")

    /** A DURATION as exact time, a day counting 24 hours (alarm triggers, provider rows). */
    fun parseDuration(value: String): Duration? =
        durationParts(value)?.let { (days, exact) -> Duration.ofDays(days).plus(exact) }

    /**
     * The end of a DURATION after [start]. Weeks and days are nominal (RFC 5545 §3.3.6): they move the
     * wall clock of the start's zone, so a day across a daylight-saving change lasts 23 or 25 hours.
     * Hours, minutes and seconds are exact.
     */
    fun plusDuration(start: ICalDateTime, value: String): Long? {
        val (days, exact) = durationParts(value) ?: return null
        return Instant.ofEpochMilli(start.millis).atZone(start.zone).plusDays(days).toInstant().plus(exact).toEpochMilli()
    }

    /** Signed weeks plus days, and the signed hours, minutes and seconds of a DURATION. */
    private fun durationParts(value: String): Pair<Long, Duration>? {
        val m = DURATION.matchEntire(value.trim().uppercase()) ?: return null
        val (sign, w, d, h, min, s) = m.destructured
        val days = (w.toLongOrNull() ?: 0) * 7 + (d.toLongOrNull() ?: 0)
        val exact = Duration.ofHours(h.toLongOrNull() ?: 0)
            .plusMinutes(min.toLongOrNull() ?: 0)
            .plusSeconds(s.toLongOrNull() ?: 0)
        return if (sign == "-") -days to exact.negated() else days to exact
    }
}

object TimeZones {
    /** Common Outlook/Exchange zone names that are not IANA IDs. */
    private val WINDOWS = mapOf(
        "W. Europe Standard Time" to "Europe/Berlin",
        "Central Europe Standard Time" to "Europe/Budapest",
        "Central European Standard Time" to "Europe/Warsaw",
        "Romance Standard Time" to "Europe/Paris",
        "GMT Standard Time" to "Europe/London",
        "Greenwich Standard Time" to "Atlantic/Reykjavik",
        "E. Europe Standard Time" to "Europe/Chisinau",
        "FLE Standard Time" to "Europe/Kiev",
        "GTB Standard Time" to "Europe/Bucharest",
        "Turkey Standard Time" to "Europe/Istanbul",
        "Russian Standard Time" to "Europe/Moscow",
        "Eastern Standard Time" to "America/New_York",
        "Central Standard Time" to "America/Chicago",
        "Mountain Standard Time" to "America/Denver",
        "Pacific Standard Time" to "America/Los_Angeles",
        "Tokyo Standard Time" to "Asia/Tokyo",
        "China Standard Time" to "Asia/Shanghai",
        "India Standard Time" to "Asia/Kolkata",
        "AUS Eastern Standard Time" to "Australia/Sydney",
        "UTC" to "UTC",
        "Coordinated Universal Time" to "UTC",
    )

    fun resolve(tzid: String): ZoneId {
        val id = tzid.trim().trim('"')
        runCatching { return ZoneId.of(id, ZoneId.SHORT_IDS) }
        WINDOWS[id]?.let { return ZoneId.of(it) }
        // e.g. "/mozilla.org/20050126_1/Europe/Berlin"
        val parts = id.split('/').filter { it.isNotBlank() }
        for (n in 3 downTo 1) {
            if (parts.size >= n) runCatching { return ZoneId.of(parts.takeLast(n).joinToString("/")) }
        }
        return ZoneId.systemDefault()
    }

    fun isUtc(zone: ZoneId): Boolean = zone.normalized() == ZoneOffset.UTC
}
