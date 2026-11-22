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
import com.ce.kit.ui.glass.GlassTokens
import com.ce.kit.ui.theme.Accent
import com.ce.kit.ui.theme.Skin
import com.ce.kit.ui.theme.ThemeStyle
import kotlin.math.roundToInt
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val accent = stringPreferencesKey("accent")
        val customHue = floatPreferencesKey("custom_hue")
        val themeStyle = stringPreferencesKey("theme_style")
        val skin = stringPreferencesKey("skin")
        val depth3d = booleanPreferencesKey("depth_3d")
        val transparency = intPreferencesKey("transparency")
        val checkUpdates = booleanPreferencesKey("check_updates")

        /** Replaced by [customHue] (the colour picked by hue); read once to carry an earlier choice over. */
        val legacyAccentHue = floatPreferencesKey("accent_hue")

        /** Replaced by [transparency] (0–100); read once to carry an earlier choice over. */
        val legacyGlassTransparency = floatPreferencesKey("glass_transparency")

        /** Replaced by [glassTransparency]; read once to carry an earlier choice over. */
        val legacyGlass = stringPreferencesKey("glass")

        /** Until it is set, the widgets keep the app's transparency they used to share. */
        val widgetTransparency = floatPreferencesKey("widget_transparency")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val weekStart = stringPreferencesKey("week_start")
        val weekNumbers = booleanPreferencesKey("week_numbers")
        val timeFormat = stringPreferencesKey("time_format")
        val defaultCalendar = longPreferencesKey("default_calendar")
        val lastUsedCalendar = longPreferencesKey("last_used_calendar")
        val defaultTaskCalendar = longPreferencesKey("default_task_calendar")
        val lastUsedTaskCalendar = longPreferencesKey("last_used_task_calendar")
        /** Earlier versions stored an untouched switch as false, so only true is taken over. */
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
        val legacyTransparency = this[Keys.legacyGlassTransparency] ?: legacyTransparency(this[Keys.legacyGlass])
        val transparency = this[Keys.transparency] ?: legacyTransparency?.let { (it * 100).roundToInt() } ?: d.transparency
        val accentName = this[Keys.accent]
        return AppSettings(
            accent = accentName.toEnum(d.accent),
            // The earlier hue picker becomes the free colour, unless a ready-made accent was chosen since.
            customHue = this[Keys.customHue] ?: if (accentName == null) this[Keys.legacyAccentHue] else null,
            themeMode = this[Keys.theme].toEnum(d.themeMode),
            themeStyle = this[Keys.themeStyle].toEnum(d.themeStyle),
            skin = this[Keys.skin].toEnum(d.skin),
            depth3d = this[Keys.depth3d] ?: d.depth3d,
            transparency = transparency,
            checkUpdates = this[Keys.checkUpdates] ?: d.checkUpdates,
            widgetTransparency = this[Keys.widgetTransparency] ?: (transparency / 100f),
            reduceMotion = this[Keys.reduceMotion] ?: d.reduceMotion,
            weekStart = this[Keys.weekStart].toEnum(d.weekStart),
            showWeekNumbers = this[Keys.weekNumbers] ?: d.showWeekNumbers,
            timeFormat = this[Keys.timeFormat].toEnum(d.timeFormat),
            defaultCalendarId = this[Keys.defaultCalendar],
            lastUsedCalendarId = this[Keys.lastUsedCalendar],
            defaultTaskCalendarId = this[Keys.defaultTaskCalendar],
            lastUsedTaskCalendarId = this[Keys.lastUsedTaskCalendar],
            defaultDurationMinutes = this[Keys.defaultDuration] ?: d.defaultDurationMinutes,
            startView = this[Keys.startView].toEnum(d.startView),
            eventReminders = this[Keys.eventReminders].toMinutes(d.eventReminders),
            allDayReminders = this[Keys.allDayReminders].toMinutes(d.allDayReminders),
            taskReminders = this[Keys.taskReminders].toMinutes(d.taskReminders),
        )
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[Keys.theme] = s.themeMode.name
        this[Keys.accent] = s.accent.name
        if (s.customHue != null) this[Keys.customHue] = s.customHue else remove(Keys.customHue)
        remove(Keys.legacyAccentHue)
        this[Keys.themeStyle] = s.themeStyle.name
        this[Keys.skin] = s.skin.name
        this[Keys.depth3d] = s.depth3d
        this[Keys.transparency] = s.transparency
        this[Keys.checkUpdates] = s.checkUpdates
        remove(Keys.legacyGlassTransparency)
        remove(Keys.legacyGlass)
        this[Keys.widgetTransparency] = s.widgetTransparency
        this[Keys.reduceMotion] = s.reduceMotion
        this[Keys.weekStart] = s.weekStart.name
        this[Keys.weekNumbers] = s.showWeekNumbers
        this[Keys.timeFormat] = s.timeFormat.name
        if (s.defaultCalendarId != null) this[Keys.defaultCalendar] = s.defaultCalendarId else remove(Keys.defaultCalendar)
        if (s.lastUsedCalendarId != null) this[Keys.lastUsedCalendar] = s.lastUsedCalendarId else remove(Keys.lastUsedCalendar)
        if (s.defaultTaskCalendarId != null) this[Keys.defaultTaskCalendar] = s.defaultTaskCalendarId else remove(Keys.defaultTaskCalendar)
        if (s.lastUsedTaskCalendarId != null) this[Keys.lastUsedTaskCalendar] = s.lastUsedTaskCalendarId else remove(Keys.lastUsedTaskCalendar)
        this[Keys.defaultDuration] = s.defaultDurationMinutes
        this[Keys.startView] = s.startView.name
        this[Keys.eventReminders] = s.eventReminders.joinToString(",")
        this[Keys.allDayReminders] = s.allDayReminders.joinToString(",")
        this[Keys.taskReminders] = s.taskReminders.joinToString(",")
    }

    /**
     * Every setting that is not tied to this phone, as JSON for the backup (the same format as Cemail's and Cenotes', with
     * "app": "celander"). The default calendars and the choice about the phone's calendars are ids and choices of this
     * phone: left out.
     */
    suspend fun exportJson(): String {
        val values = JSONObject()
        dataStore.data.first().asMap().forEach { (key, value) ->
            if (key.name in PhoneBound) return@forEach
            val typed = when (value) {
                is Boolean -> JSONObject().put("b", value)
                is Int -> JSONObject().put("i", value)
                is Long -> JSONObject().put("l", value)
                is Float -> JSONObject().put("f", value.toDouble())
                is String -> JSONObject().put("s", value)
                else -> null
            }
            if (typed != null) values.put(key.name, typed)
        }
        return JSONObject().put("app", "celander").put("format", 1).put("settings", values).toString(2)
    }

    /** Applies settings made by [exportJson]; returns false (and changes nothing) if [text] is not one. */
    suspend fun importJson(text: String): Boolean {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return false
        if (root.optString("app") != "celander") return false
        val values = root.optJSONObject("settings") ?: return false
        dataStore.edit { prefs ->
            values.keys().forEach { name ->
                if (name in PhoneBound) return@forEach
                val typed = values.optJSONObject(name) ?: return@forEach
                when {
                    typed.has("b") -> prefs[booleanPreferencesKey(name)] = typed.getBoolean("b")
                    typed.has("i") -> prefs[intPreferencesKey(name)] = typed.getInt("i")
                    typed.has("l") -> prefs[longPreferencesKey(name)] = typed.getLong("l")
                    typed.has("f") -> prefs[floatPreferencesKey(name)] = typed.getDouble("f").toFloat()
                    typed.has("s") -> prefs[stringPreferencesKey(name)] = typed.getString("s")
                }
            }
        }
        return true
    }

    private companion object {
        /** Ids of this phone's calendars (and the removed choice about showing the system calendars, still filtered from old backups): not part of a backup. */
        val PhoneBound = setOf(
            "default_calendar", "last_used_calendar", "default_task_calendar", "last_used_task_calendar",
            "device_calendars", "device_calendars_choice",
        )
    }
}
