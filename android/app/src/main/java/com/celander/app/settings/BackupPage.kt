package com.celander.app.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.celander.app.R
import com.celander.app.ui.ChoiceChip
import com.celander.app.ui.SettingsCard
import com.celander.app.ui.glass.TaskCheckbox
import com.celander.app.ui.theme.LocalGlass
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Backups are one .zip the user saves and picks with the system file picker (Drive, Downloads, a USB stick…). A dialog
 * asks what goes in (check boxes). Importing looks inside the file and asks what to restore. See [Backup] for the container.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BackupPage(backup: Backup) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    // What the user chose in the create dialog, kept while the file picker is open.
    var creating by rememberSaveable { mutableStateOf(false) }
    var chosenSettings by rememberSaveable { mutableStateOf(true) }
    var chosenData by rememberSaveable { mutableStateOf(true) }
    // The file the user picked and what is inside it, while they choose what to restore.
    var pending by remember { mutableStateOf<Pair<Uri, Backup.Contents>?>(null) }
    var working by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> String) {
        working = true
        message = null
        scope.launch {
            message = try {
                block()
            } catch (e: Backup.NotABackupException) {
                resources.getString(R.string.backup_not_celander)
            } catch (e: Backup.TooNewException) {
                resources.getString(R.string.backup_too_new)
            } catch (e: Exception) {
                resources.getString(R.string.backup_failed, e.localizedMessage ?: e.javaClass.simpleName)
            }
            working = false
        }
    }

    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) run {
            backup.export(uri, chosenSettings, chosenData)
            resources.getString(R.string.backup_exported_settings)
        }
    }
    if (creating) {
        CreateDialog(
            onDismiss = { creating = false },
            onCreate = { settings, data ->
                creating = false
                chosenSettings = settings
                chosenData = data
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                createLauncher.launch(resources.getString(R.string.backup_file_settings, date))
            },
        )
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run {
            pending = uri to backup.inspect(uri)
            ""
        }
    }
    val icsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run {
            val r = backup.importIcs(uri)
            resources.getString(R.string.backup_imported_data, r.events, r.tasks, r.skipped)
        }
    }
    pending?.let { (uri, contents) ->
        RestoreDialog(
            contents = contents,
            onDismiss = { pending = null },
            onRestore = { settings, data ->
                pending = null
                run {
                    val r = backup.import(uri, settings, data)
                    listOfNotNull(
                        resources.getString(R.string.backup_imported_settings).takeIf { r.settingsRestored },
                        r.data?.let { resources.getString(R.string.backup_imported_data, it.events, it.tasks, it.skipped) },
                    ).joinToString(" ")
                }
            },
        )
    }

    SettingsCard {
        Text(stringResource(R.string.backup), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.backup_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(stringResource(R.string.backup_create), selected = false, enabled = !working) { creating = true }
            ChoiceChip(stringResource(R.string.backup_import), selected = false, enabled = !working) {
                // Any file: some devices label a .zip with another type (x-zip, x-compressed…) and the picker would grey it
                // out. Backup.inspect checks that it is a Celander backup and says so if it is not.
                importLauncher.launch(arrayOf("*/*"))
            }
            ChoiceChip(stringResource(R.string.backup_import_ics), selected = false, enabled = !working) { icsLauncher.launch(arrayOf("*/*")) }
        }
        val status = if (working) stringResource(R.string.backup_working) else message
        if (!status.isNullOrEmpty()) {
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Asks what to back up (at least one thing). */
@Composable
private fun CreateDialog(onDismiss: () -> Unit, onCreate: (settings: Boolean, data: Boolean) -> Unit) {
    var settings by rememberSaveable { mutableStateOf(true) }
    var data by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = LocalGlass.current.menu,
        title = { Text(stringResource(R.string.backup_create_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RestoreRow(R.string.backup_restore_settings, settings) { settings = it }
                RestoreRow(R.string.backup_restore_data, data) { data = it }
            }
        },
        confirmButton = {
            TextButton(enabled = settings || data, onClick = { onCreate(settings, data) }) {
                Text(stringResource(R.string.backup_create_confirm), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Asks what to restore from a backup, with a check box only for what the file carries. */
@Composable
private fun RestoreDialog(contents: Backup.Contents, onDismiss: () -> Unit, onRestore: (settings: Boolean, data: Boolean) -> Unit) {
    var settings by remember { mutableStateOf(contents.settings) }
    var data by remember { mutableStateOf(contents.data) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = LocalGlass.current.menu,
        title = { Text(stringResource(R.string.backup_restore_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (contents.settings) RestoreRow(R.string.backup_restore_settings, settings) { settings = it }
                if (contents.data) RestoreRow(R.string.backup_restore_data, data) { data = it }
            }
        },
        confirmButton = {
            TextButton(enabled = settings || data, onClick = { onRestore(settings, data) }) {
                Text(stringResource(R.string.backup_restore_confirm), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * One choice of the dialogs: a check box and its label, the whole row toggles. The box is the same one as in a task
 * (Material's, or the Windows 98 one in Retro 98), so it follows every design.
 */
@Composable
private fun RestoreRow(label: Int, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 4.dp),
    ) {
        TaskCheckbox(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.padding(start = 2.dp, end = 14.dp),
            size = 18.dp,
        ) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
    }
}
