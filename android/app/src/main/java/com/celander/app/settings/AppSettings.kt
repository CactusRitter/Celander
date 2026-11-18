package com.celander.app.settings

import com.celander.app.core.model.CalendarView
import com.ce.kit.ui.glass.GlassTokens
import com.ce.kit.ui.theme.Accent
import com.ce.kit.ui.theme.AccentPalette
import com.ce.kit.ui.theme.Skin
import com.ce.kit.ui.theme.ThemeMode
import com.ce.kit.ui.theme.ThemeStyle
import com.ce.kit.ui.theme.customPalette
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale

enum class WeekStart { SYSTEM, MONDAY, SUNDAY }

enum class TimeFormat { SYSTEM, H24, H12 }

data class AppSettings(
    val accent: Accent = Accent.INDIGO,
    /** A colour of the free hue bar (0–360) instead of [accent]; null = the chosen accent. */
    val customHue: Float? = null,
    /** Light, dark or following the phone. */
    override val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Background and glass tint of the glass look (only used with [Skin.GLASS]). */
    override val themeStyle: ThemeStyle = ThemeStyle.AURORA,
    /** The whole look of the app; [Skin.GLASS] is the one shared with Cemail and Cenotes. */
    override val skin: Skin = Skin.GLASS,
    /** Soft "3D" shadow under every glass surface. */
    override val depth3d: Boolean = false,
    /** 0 = solid glass, 100 = fully see-through. */
    override val transparency: Int = 65,
    /** Ask GitHub once or twice a day whether there is a newer Celander. */
    val checkUpdates: Boolean = true,
    /** The cards of all home screen widgets, set apart from [transparency]. */
    val widgetTransparency: Float = GlassTokens.DEFAULT_TRANSPARENCY,
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
    val defaultDurationMinutes: Int = 60,
    val startView: CalendarView = CalendarView.MONTH,
    val eventReminders: List<Int> = listOf(10),
    val allDayReminders: List<Int> = listOf(360),
    val taskReminders: List<Int> = listOf(0),
) : com.ce.kit.ui.theme.CeAppearance {
    /** The app's colour in its four shades: the custom one, or the chosen accent. */
    override val accentPalette: AccentPalette
        get() = customHue?.let { customPalette(it) } ?: accent.palette

    fun firstDayOfWeek(locale: Locale = Locale.getDefault()): DayOfWeek = when (weekStart) {
        WeekStart.MONDAY -> DayOfWeek.MONDAY
        WeekStart.SUNDAY -> DayOfWeek.SUNDAY
        WeekStart.SYSTEM -> WeekFields.of(locale).firstDayOfWeek
    }
}
