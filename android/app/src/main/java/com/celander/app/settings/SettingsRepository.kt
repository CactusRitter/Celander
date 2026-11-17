package com.celander.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.celander.app.designsystem.GlassTokens
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val accentHue = floatPreferencesKey("accent_hue")
        val glassTransparency = floatPreferencesKey("glass_transparency")

        /** Replaced by [glassTransparency]; read once to carry an earlier choice over. */
        val legacyGlass = stringPreferencesKey("glass")

        /** Until it is set, the widgets keep the app's transparency they used to share. */
        val widgetTransparency = floatPreferencesKey("widget_transparency")
        val widgetBlur = booleanPreferencesKey("widget_blur")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val weekStart = stringPreferencesKey("week_start")
        val weekNumbers = booleanPreferencesKey("week_numbers")
        val timeFormat = stringPreferencesKey("time_format")
        val defaultCalendar = longPreferencesKey("default_calendar")
        val lastUsedCalendar = longPreferencesKey("last_used_calendar")
        val defaultTaskCalendar = longPreferencesKey("default_task_calendar")
        val lastUsedTaskCalendar = longPreferencesKey("last_used_task_calendar")
        /** Earlier versions stored an untouched switch as false, so only true is taken over. */
        val legacyDeviceCalendars = booleanPreferencesKey("device_calendars")
        val deviceCalendars = booleanPreferencesKey("device_calendars_choice")
        val defaultDuration = intPreferencesKey("default_duration")
        val startView = stringPreferencesKey("start_view")
        val eventReminders = stringPreferencesKey("event_reminders")
        val allDayReminders = stringPreferencesKey("all_day_reminders")
        val taskReminders = stringPreferencesKey("task_reminders")
    }

    val settings: Flow<AppSettings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
        this?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default

    private fun String?.toMinutes(default: List<Int>): List<Int> =
        if (this == null) default else split(',').mapNotNull { it.trim().toIntOrNull() }

    /** The former three glass levels "standard", "subtle" and "off". */
    private fun legacyTransparency(value: String?): Float? = when (value) {
        "STANDARD" -> GlassTokens.DEFAULT_TRANSPARENCY
        "SUBTLE" -> GlassTokens.SUBTLE_TRANSPARENCY
        "OFF" -> 0f
        else -> null
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        val glassTransparency = this[Keys.glassTransparency] ?: legacyTransparency(this[Keys.legacyGlass]) ?: d.glassTransparency
        return AppSettings(
            themeMode = this[Keys.theme].toEnum(d.themeMode),
            accentHue = this[Keys.accentHue] ?: d.accentHue,
            glassTransparency = glassTransparency,
            widgetTransparency = this[Keys.widgetTransparency] ?: glassTransparency,
            widgetBlur = this[Keys.widgetBlur] ?: d.widgetBlur,
            reduceMotion = this[Keys.reduceMotion] ?: d.reduceMotion,
            weekStart = this[Keys.weekStart].toEnum(d.weekStart),
            showWeekNumbers = this[Keys.weekNumbers] ?: d.showWeekNumbers,
            timeFormat = this[Keys.timeFormat].toEnum(d.timeFormat),
            defaultCalendarId = this[Keys.defaultCalendar],
            lastUsedCalendarId = this[Keys.lastUsedCalendar],
            defaultTaskCalendarId = this[Keys.defaultTaskCalendar],
            lastUsedTaskCalendarId = this[Keys.lastUsedTaskCalendar],
            deviceCalendarsEnabled = this[Keys.deviceCalendars] ?: this[Keys.legacyDeviceCalendars]?.takeIf { it } ?: d.deviceCalendarsEnabled,
            defaultDurationMinutes = this[Keys.defaultDuration] ?: d.defaultDurationMinutes,
            startView = this[Keys.startView].toEnum(d.startView),
            eventReminders = this[Keys.eventReminders].toMinutes(d.eventReminders),
            allDayReminders = this[Keys.allDayReminders].toMinutes(d.allDayReminders),
            taskReminders = this[Keys.taskReminders].toMinutes(d.taskReminders),
        )
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[Keys.theme] = s.themeMode.name
        this[Keys.accentHue] = s.accentHue
        this[Keys.glassTransparency] = s.glassTransparency
        remove(Keys.legacyGlass)
        this[Keys.widgetTransparency] = s.widgetTransparency
        this[Keys.widgetBlur] = s.widgetBlur
        this[Keys.reduceMotion] = s.reduceMotion
        this[Keys.weekStart] = s.weekStart.name
        this[Keys.weekNumbers] = s.showWeekNumbers
        this[Keys.timeFormat] = s.timeFormat.name
        if (s.defaultCalendarId != null) this[Keys.defaultCalendar] = s.defaultCalendarId else remove(Keys.defaultCalendar)
        if (s.lastUsedCalendarId != null) this[Keys.lastUsedCalendar] = s.lastUsedCalendarId else remove(Keys.lastUsedCalendar)
        if (s.defaultTaskCalendarId != null) this[Keys.defaultTaskCalendar] = s.defaultTaskCalendarId else remove(Keys.defaultTaskCalendar)
        if (s.lastUsedTaskCalendarId != null) this[Keys.lastUsedTaskCalendar] = s.lastUsedTaskCalendarId else remove(Keys.lastUsedTaskCalendar)
        if (s.deviceCalendarsEnabled != null) this[Keys.deviceCalendars] = s.deviceCalendarsEnabled else remove(Keys.deviceCalendars)
        remove(Keys.legacyDeviceCalendars)
        this[Keys.defaultDuration] = s.defaultDurationMinutes
        this[Keys.startView] = s.startView.name
        this[Keys.eventReminders] = s.eventReminders.joinToString(",")
        this[Keys.allDayReminders] = s.allDayReminders.joinToString(",")
        this[Keys.taskReminders] = s.taskReminders.joinToString(",")
    }
}
