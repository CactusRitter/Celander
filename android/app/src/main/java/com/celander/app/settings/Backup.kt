package com.celander.app.settings

import android.content.Context
import android.net.Uri
import com.celander.app.BuildConfig
import com.celander.app.core.ical.LocalDataIcs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

    /** Not a Celander backup (another app's zip, or anything else). */
    class NotABackupException : Exception()

    /** A backup made by a newer container version than this app understands. */
    class TooNewException : Exception()

    /** One backup zip: `manifest.json` + `settings.json` if [includeSettings] + `data.ics` if [includeData]. */
    suspend fun export(uri: Uri, includeSettings: Boolean, includeData: Boolean = false) = withContext(Dispatchers.IO) {
        require(includeSettings || includeData) { "Nothing to back up" }
        val settingsJson = if (includeSettings) settings.exportJson() else null
        val dataIcs = if (includeData) data.export() else null
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not write the file")
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putText(ManifestEntry, manifest(listOfNotNull(ContentSettings.takeIf { includeSettings }, ContentData.takeIf { includeData })).toString(2))
            settingsJson?.let { zip.putText(SettingsEntry, it) }
            dataIcs?.let { zip.putText(DataEntry, it) }
        }
    }

    private fun manifest(contents: List<String>) = JSONObject()
        .put("app", ManifestApp)
        .put("container", Container)
        .put("appVersion", BuildConfig.VERSION_NAME)
        .put("createdAt", System.currentTimeMillis())
        .put("contents", JSONArray(contents))

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
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
                    if (++entries > MaxEntries) throw NotABackupException()
                    when {
                        entry.isDirectory -> Unit
                        entry.name == ManifestEntry -> manifest = runCatching { JSONObject(readLimited(zip).toString(Charsets.UTF_8)) }.getOrNull()
                        entry.name == SettingsEntry -> hasSettings = looksLikeSettings(readLimited(zip).toString(Charsets.UTF_8))
                        entry.name == DataEntry -> hasData = readLimited(zip).toString(Charsets.UTF_8).contains("BEGIN:VCALENDAR")
                    }
                    zip.closeEntry()
                }
            }
            // The manifest says whose zip it is: another app's (or no manifest at all) is not a backup of ours.
            val m = manifest ?: throw NotABackupException()
            if (!m.optString("app").equals(ManifestApp, ignoreCase = true)) throw NotABackupException()
            if (m.optInt("container", 0) > Container) throw TooNewException()
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
                    if (++entries > MaxEntries) throw NotABackupException()
                    if (!entry.isDirectory && entry.name == SettingsEntry && restoreSettings) settingsJson = readLimited(zip).toString(Charsets.UTF_8)
                    if (!entry.isDirectory && entry.name == DataEntry && restoreData) dataIcs = readLimited(zip).toString(Charsets.UTF_8)
                    zip.closeEntry()
                }
            }
        }
        var restored = false
        if (restoreSettings) settingsJson?.let { restored = settings.importJson(it) }
        ImportResult(settingsRestored = restored, data = dataIcs?.let { data.import(it) })
    }

    private fun looksLikeSettings(text: String): Boolean {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return false
        return o.optString("app").equals(ManifestApp, ignoreCase = true) && o.optJSONObject("settings") != null
    }

    /** Reads at most [MaxJsonBytes] of an entry; a bigger one is not a backup (guards against zip bombs). */
    private fun readLimited(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MaxJsonBytes) throw NotABackupException()
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val ManifestApp = "celander"
        const val Container = 1
        const val ManifestEntry = "manifest.json"
        const val SettingsEntry = "settings.json"
        const val DataEntry = "data.ics"
        const val ContentSettings = "settings"
        const val ContentData = "data"

        /** Limits for an untrusted zip: JSON entries and the number of entries. */
        const val MaxJsonBytes = 64 * 1024 * 1024
        const val MaxEntries = 10_000
    }
}
