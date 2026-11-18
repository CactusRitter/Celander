package com.celander.app.settings

import com.celander.app.testing.testPreferencesDataStore
import com.celander.app.core.di.settingsCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A damaged settings file must not break reading or saving settings. */
class SettingsCorruptionTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun aDamagedFileStartsOverWithTheDefaults() = runBlocking {
        // Wire type 7 does not exist in protocol buffers: the file cannot be parsed.
        val file = File(folder.root, "settings.preferences_pb").apply { writeBytes(byteArrayOf(0x0f, 0x0f, 0x0f)) }
        val repository = SettingsRepository(testPreferencesDataStore(scope, file, settingsCorruptionHandler()))
        assertEquals(AppSettings(), repository.current())

        repository.update { it.copy(showWeekNumbers = !it.showWeekNumbers) }
        assertEquals(!AppSettings().showWeekNumbers, repository.current().showWeekNumbers)
    }
}
