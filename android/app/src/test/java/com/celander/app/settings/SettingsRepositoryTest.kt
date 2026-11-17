package com.celander.app.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.celander.app.ui.glass.GlassTokens
import com.celander.app.ui.theme.Accent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore by lazy { PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") } }
    private val repository by lazy { SettingsRepository(dataStore) }
    private val legacyGlass = stringPreferencesKey("glass")
    private val legacyDeviceCalendars = booleanPreferencesKey("device_calendars")
    private val glassTransparency = floatPreferencesKey("glass_transparency")
    private val accentHue = floatPreferencesKey("accent_hue")

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun defaultsUseIndigoAndStandardGlass() = runBlocking {
        val settings = repository.current()
        assertEquals(Accent.INDIGO, settings.accent)
        assertEquals(65, settings.transparency)
        assertEquals(GlassTokens.DEFAULT_TRANSPARENCY, settings.widgetTransparency)
    }

    @Test
    fun widgetsKeepTheAppTransparencyUntilTheirOwnIsSet() = runBlocking {
        // Before widgets had their own setting, they used the app's.
        dataStore.edit { it[glassTransparency] = 0.2f }
        assertEquals(0.2f, repository.current().widgetTransparency)

        // Changing the app's glass afterwards no longer moves the widgets.
        repository.update { it.copy(transparency = 90) }
        assertEquals(90, repository.current().transparency)
        assertEquals(0.2f, repository.current().widgetTransparency)

        repository.update { it.copy(widgetTransparency = 0.5f) }
        val settings = repository.current()
        assertEquals(90, settings.transparency)
        assertEquals(0.5f, settings.widgetTransparency)
    }

    @Test
    fun widgetsFollowAFormerGlassLevel() = runBlocking {
        dataStore.edit { it[legacyGlass] = "OFF" }
        assertEquals(0f, repository.current().widgetTransparency)
    }

    @Test
    fun theFormerGlassLevelsCarryOver() = runBlocking {
        for ((level, expected) in listOf("STANDARD" to 65, "SUBTLE" to 40, "OFF" to 0)) {
            dataStore.edit { it.clear(); it[legacyGlass] = level }
            assertEquals(level, expected, repository.current().transparency)
        }
    }

    @Test
    fun savingReplacesTheFormerGlassLevel() = runBlocking {
        dataStore.edit { it[legacyGlass] = "OFF" }
        repository.update { it.copy(customHue = 145f) }

        val stored = dataStore.data.first()
        assertNull(stored[legacyGlass])
        val settings = repository.current()
        assertEquals(0, settings.transparency)
        assertEquals(145f, settings.customHue)
    }

    @Test
    fun onlyASwitchedOnPhoneCalendarCarriesOver() = runBlocking {
        // Earlier versions stored an untouched switch as false, so false means "not chosen".
        for ((stored, expected) in listOf(true to true, false to null)) {
            dataStore.edit { it.clear(); it[legacyDeviceCalendars] = stored }
            assertEquals(expected, repository.current().deviceCalendarsEnabled)
        }
        repository.update { it.copy(deviceCalendarsEnabled = false) }
        assertEquals(false, repository.current().deviceCalendarsEnabled)
        assertNull(dataStore.data.first()[legacyDeviceCalendars])
    }

    @Test
    fun designChoicesArePersisted() = runBlocking {
        repository.update { it.copy(accent = Accent.CORAL, customHue = 12f, transparency = 30) }
        val settings = repository.current()
        assertEquals(Accent.CORAL, settings.accent)
        assertEquals(12f, settings.customHue)
        assertEquals(30, settings.transparency)
    }

    @Test
    fun theEarlierHuePickerBecomesTheFreeColour() = runBlocking {
        dataStore.edit { it[accentHue] = 145f; it[glassTransparency] = 0.4f }
        val settings = repository.current()
        assertEquals(145f, settings.customHue)
        assertEquals(40, settings.transparency)
    }
}
