package com.celander.app.settings

import com.celander.app.core.model.CalendarView
import com.celander.app.designsystem.AccentPalette
import com.celander.app.designsystem.GlassTokens
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class WeekStart { SYSTEM, MONDAY, SUNDAY }

enum class TimeFormat { SYSTEM, H24, H12 }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentHue: Float = AccentPalette.DEFAULT_HUE,
    /** Glass inside the app: bars, sheets, cards and backgrounds. */
    val glassTransparency: Float = GlassTokens.DEFAULT_TRANSPARENCY,
    /** The cards of all home screen widgets, set apart from [glassTransparency]. */
    val widgetTransparency: Float = GlassTokens.DEFAULT_TRANSPARENCY,
    /** Widgets show the wallpaper blurred behind their glass (needs "All files access", see `WallpaperBlur`). */
    val widgetBlur: Boolean = false,
    val reduceMotion: Boolean = false,
    val weekStart: WeekStart = WeekStart.SYSTEM,
    val showWeekNumbers: Boolean = true,
    val timeFormat: TimeFormat = TimeFormat.SYSTEM,
    /** Default and last used calendar for events. */
    val defaultCalendarId: Long? = null,
    val lastUsedCalendarId: Long? = null,
    /** Default and last used calendar or Google list for tasks. */
    val defaultTaskCalendarId: Long? = null,
    val lastUsedTaskCalendarId: Long? = null,
    /**
     * Shows the calendars the phone syncs (Google Calendar through the system calendar). Null until
     * the user switches it: then they show as soon as calendar access is granted.
     */
    val deviceCalendarsEnabled: Boolean? = null,
    val defaultDurationMinutes: Int = 60,
    val startView: CalendarView = CalendarView.MONTH,
    val eventReminders: List<Int> = listOf(10),
    val allDayReminders: List<Int> = listOf(360),
    val taskReminders: List<Int> = listOf(0),
) {
    fun firstDayOfWeek(locale: Locale = Locale.getDefault()): DayOfWeek = when (weekStart) {
        WeekStart.MONDAY -> DayOfWeek.MONDAY
        WeekStart.SUNDAY -> DayOfWeek.SUNDAY
        WeekStart.SYSTEM -> WeekFields.of(locale).firstDayOfWeek
    }
}
