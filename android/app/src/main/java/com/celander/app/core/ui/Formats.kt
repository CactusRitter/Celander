package com.celander.app.core.ui

import android.content.Context
import android.icu.text.DateIntervalFormat
import android.icu.util.DateInterval
import android.icu.util.TimeZone
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.celander.app.R
import com.celander.app.settings.TimeFormat
import java.text.FieldPosition
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Locale-aware date and time formatters built from Android's best-pattern skeletons. */
@Immutable
class Formats(val locale: Locale, val use24h: Boolean) {
    private fun skeleton(pattern: String): DateTimeFormatter =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, pattern), locale)

    val time: DateTimeFormatter = DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a", locale)
    val hour: DateTimeFormatter = DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h a", locale)
    val year: DateTimeFormatter = DateTimeFormatter.ofPattern("y", locale)
    val monthName: DateTimeFormatter = skeleton("LLLL")
    val monthShort: DateTimeFormatter = skeleton("LLL")
    val monthYear: DateTimeFormatter = skeleton("MMMMy")
    val monthYearShort: DateTimeFormatter = skeleton("MMMy")
    val dayLong: DateTimeFormatter = skeleton("EEEEdMMMMy")
    val dayMonthLong: DateTimeFormatter = skeleton("EEEEdMMMM")
    val dayMonthShort: DateTimeFormatter = skeleton("EEEdMMM")
    val dayMonth: DateTimeFormatter = skeleton("dMMM")
    val dayMonthYear: DateTimeFormatter = skeleton("dMMMMy")
    val dayMonthName: DateTimeFormatter = skeleton("dMMMM")
    val dateMedium: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)

    fun weekdayShort(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, locale).trimEnd('.')

    fun weekdayNarrow(day: DayOfWeek): String = day.getDisplayName(TextStyle.NARROW, locale)

    fun weekdayLong(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, locale)

    fun capitalize(text: String): String = text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

    /** Days [start] to [endInclusive] without repeating the month or year, e.g. "18.–24. Oktober 2026". */
    fun dayRange(start: LocalDate, endInclusive: LocalDate): String {
        val format = DateIntervalFormat.getInstance("yMMMMd", locale).apply { timeZone = TimeZone.GMT_ZONE }
        fun millis(date: LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return format.format(DateInterval(millis(start), millis(endInclusive)), StringBuffer(), FieldPosition(0)).toString()
    }

    /** A fraction as a whole percentage, e.g. "65 %" in German and "65%" in English. */
    fun percent(fraction: Float): String = NumberFormat.getPercentInstance(locale).format(fraction)

    companion object {
        fun is24h(context: Context, setting: TimeFormat): Boolean = when (setting) {
            TimeFormat.H24 -> true
            TimeFormat.H12 -> false
            TimeFormat.SYSTEM -> DateFormat.is24HourFormat(context)
        }

        /**
         * Formats in the language the UI texts are shown in (the system language if translated,
         * English otherwise), keeping the system region for conventions like week start and date order.
         */
        fun of(context: Context, setting: TimeFormat): Formats =
            Formats(uiLocale(context.resources.configuration.locales.get(0), context.getString(R.string.resource_language)), is24h(context, setting))

        /**
         * [system] if the texts are in its language, else the text [language] with the system region
         * and its Unicode extensions, such as the first day of the week from the regional settings.
         * Script and variant belong to the system language and are dropped.
         */
        fun uiLocale(system: Locale, language: String): Locale =
            if (system.language == language) system else Locale.Builder().setLocale(system).setLanguage(language).setScript("").setVariant("").build()
    }
}

/** [Formats.of] for composables; updates with the configuration (language, region). */
@Composable
fun rememberFormats(timeFormat: TimeFormat): Formats {
    val context = LocalContext.current
    val locale = Formats.uiLocale(LocalConfiguration.current.locales.get(0), stringResource(R.string.resource_language))
    val use24h = Formats.is24h(context, timeFormat)
    return remember(locale, use24h) { Formats(locale, use24h) }
}
