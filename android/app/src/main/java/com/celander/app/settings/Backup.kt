package com.celander.app.settings

import android.content.Context
import android.net.Uri
import com.celander.app.BuildConfig
import com.ce.kit.backup.BackupFormat
import com.ce.kit.backup.NotABackupException
import com.ce.kit.backup.TooNewException
import com.celander.app.core.ical.LocalDataIcs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Backup container (shared with Cemail and Cenotes): a .zip with a `manifest.json` and the data in separate, clearly named entries:
 *
 * - `manifest.json`: `{"app":"celander","container":1,"appVersion":"…","createdAt":ms,"contents":["settings"]}`
 * - `settings.json`: the settings, `{"app":"celander","format":1,"settings":{…typed values…}}` (see [SettingsRepository.exportJson]).
 *
 * Importing reads the zip, looks at what is in it and lets the user choose what to restore. Only this container is understood.
 * `contents` says what went in: `settings`, `data`, or both.
 */
@Singleton
class Backup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val data: LocalDataIcs,
) {
    /** What a backup file carries (and so what the import can restore). */
    data class Contents(val settings: Boolean, val data: Boolean)

    data class ImportResult(val settingsRestored: Boolean = false, val data: LocalDataIcs.ImportResult? = null)

    /** One backup zip: `manifest.json` + `settings.json` if [includeSettings] + `data.ics` if [includeData]. */
    suspend fun export(uri: Uri, includeSettings: Boolean, includeData: Boolean = false) = withContext(Dispatchers.IO) {
        require(includeSettings || includeData) { "Nothing to back up" }
        val settingsJson = if (includeSettings) settings.exportJson() else null
        val dataIcs = if (includeData) data.export() else null
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not write the file")
        ZipOutputStream(out.buffered()).use { zip ->
            BackupFormat.putJson(zip, BackupFormat.MANIFEST, BackupFormat.manifest(ManifestApp, BuildConfig.VERSION_NAME, listOfNotNull(ContentSettings.takeIf { includeSettings }, ContentData.takeIf { includeData })))
            settingsJson?.let { BackupFormat.putText(zip, BackupFormat.SETTINGS, it) }
            dataIcs?.let { BackupFormat.putText(zip, DataEntry, it) }
        }
    }

    /**
     * Looks inside the file and says what it carries, so the import can ask what to restore. Throws [NotABackupException]
     * for anything else (another app's zip included) and [TooNewException] for a newer container.
     */
    suspend fun inspect(uri: Uri): Contents = withContext(Dispatchers.IO) {
        val input = BufferedInputStream(context.contentResolver.openInputStream(uri) ?: error("Could not read the file"))
        input.use {
            var manifest: JSONObject? = null
            var hasSettings = false
            var hasData = false
            var entries = 0
            ZipInputStream(it).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    BackupFormat.checkEntryCount(++entries)
                    when {
                        entry.isDirectory -> Unit
                        entry.name == BackupFormat.MANIFEST -> manifest = runCatching { JSONObject(BackupFormat.readLimited(zip).toString(Charsets.UTF_8)) }.getOrNull()
                        entry.name == BackupFormat.SETTINGS -> hasSettings = looksLikeSettings(BackupFormat.readLimited(zip).toString(Charsets.UTF_8))
                        entry.name == DataEntry -> hasData = BackupFormat.readLimited(zip).toString(Charsets.UTF_8).contains("BEGIN:VCALENDAR")
                    }
                    zip.closeEntry()
                }
            }
            // The manifest says whose zip it is: another app's (or no manifest at all) is not a backup of ours.
            BackupFormat.checkManifest(manifest, ManifestApp)
            if (!hasSettings && !hasData) throw NotABackupException()
            Contents(hasSettings, hasData)
        }
    }

    /** Restores what the user chose from the file; what it does not carry is ignored. */
    suspend fun import(uri: Uri, restoreSettings: Boolean, restoreData: Boolean = false): ImportResult = withContext(Dispatchers.IO) {
        var settingsJson: String? = null
        var dataIcs: String? = null
        val input = BufferedInputStream(context.contentResolver.openInputStream(uri) ?: error("Could not read the file"))
        input.use {
            var entries = 0
            ZipInputStream(it).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    BackupFormat.checkEntryCount(++entries)
                    if (!entry.isDirectory && entry.name == BackupFormat.SETTINGS && restoreSettings) settingsJson = BackupFormat.readLimited(zip).toString(Charsets.UTF_8)
                    if (!entry.isDirectory && entry.name == DataEntry && restoreData) dataIcs = BackupFormat.readLimited(zip).toString(Charsets.UTF_8)
                    zip.closeEntry()
                }
            }
        }
        var restored = false
        if (restoreSettings) settingsJson?.let { restored = settings.importJson(it) }
        ImportResult(settingsRestored = restored, data = dataIcs?.let { data.import(it) })
    }

    /** Adds the events and tasks of a plain .ics file (from another calendar app) to this device; throws [NotABackupException] if it is not one. */
    suspend fun importIcs(uri: Uri): LocalDataIcs.ImportResult = withContext(Dispatchers.IO) {
        val bytes = (context.contentResolver.openInputStream(uri) ?: error("Could not read the file")).use { BackupFormat.readLimited(it) }
        val text = bytes.toString(Charsets.UTF_8)
        if (!text.contains("BEGIN:VCALENDAR", ignoreCase = true)) throw NotABackupException()
        data.import(text)
    }

    private fun looksLikeSettings(text: String): Boolean {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return false
        return o.optString("app").equals(ManifestApp, ignoreCase = true) && o.optJSONObject("settings") != null
    }

    private companion object {
        const val ManifestApp = "celander"
        const val DataEntry = "data.ics"
        const val ContentSettings = "settings"
        const val ContentData = "data"

    }
}
