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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ce.kit.backup.NotABackupException
import com.ce.kit.backup.TooNewException
import com.celander.app.R
import com.ce.kit.ui.GlassDialog
import com.ce.kit.ui.settings.ChoiceChip
import com.ce.kit.ui.settings.SettingsCard
import com.ce.kit.ui.glass.TaskCheckbox
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the backup page and its dialogs share. It lives in the settings screen, not in the page, because [GlassDialog]
 * draws inside its parent and so has to sit at the root of the screen ([BackupDialogs] goes in the overlay).
 */
@Stable
internal class BackupController(
    private val backup: Backup,
    private val scope: CoroutineScope,
    private val resources: android.content.res.Resources,
) {
    /** The "what to back up" dialog is open. */
    var creating by mutableStateOf(false)
    /** The "what to restore" dialog is open; [pendingUri] and [pendingContents] stay set while it fades out. */
    var restoring by mutableStateOf(false)
    var pendingUri by mutableStateOf<Uri?>(null)
    var pendingContents by mutableStateOf<Backup.Contents?>(null)
    /** The check boxes of whichever dialog is open. */
    var dialogSettings by mutableStateOf(true)
    var dialogData by mutableStateOf(true)
    var working by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    // What the user chose in the create dialog, kept while the file picker is open.
    var chosenSettings = true
    var chosenData = true
    /** Set by the page, which owns the file picker. */
    var launchCreate: ((fileDate: String) -> Unit)? = null

    val modalOpen get() = creating || restoring

    fun run(block: suspend () -> String) {
        working = true
        message = null
        scope.launch {
            message = try {
                block()
            } catch (e: NotABackupException) {
                resources.getString(R.string.backup_not_celander)
            } catch (e: TooNewException) {
                resources.getString(R.string.backup_too_new)
            } catch (e: Exception) {
                resources.getString(R.string.backup_failed, e.localizedMessage ?: e.javaClass.simpleName)
            }
            working = false
        }
    }

    fun openCreate() {
        dialogSettings = true
        dialogData = true
        creating = true
    }

    fun onPicked(uri: Uri) = run {
        val contents = backup.inspect(uri)
        pendingUri = uri
        pendingContents = contents
        dialogSettings = contents.settings
        dialogData = contents.data
        restoring = true
        ""
    }

    fun export(uri: Uri) = run {
        backup.export(uri, chosenSettings, chosenData)
        resources.getString(R.string.backup_exported_settings)
    }

    fun importIcs(uri: Uri) = run {
        val r = backup.importIcs(uri)
        resources.getString(R.string.backup_imported_data, r.events, r.tasks, r.skipped)
    }

    fun restore() {
        val uri = pendingUri ?: return
        val settings = dialogSettings
        val data = dialogData
        restoring = false
        run {
            val r = backup.import(uri, settings, data)
            listOfNotNull(
                resources.getString(R.string.backup_imported_settings).takeIf { r.settingsRestored },
                r.data?.let { resources.getString(R.string.backup_imported_data, it.events, it.tasks, it.skipped) },
            ).joinToString(" ")
        }
    }
}

@Composable
internal fun rememberBackupController(backup: Backup): BackupController {
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    return remember(backup) { BackupController(backup, scope, resources) }
}

/**
 * Backups are one .zip the user saves and picks with the system file picker (Drive, Downloads, a USB stick…). A dialog
 * asks what goes in (check boxes). Importing looks inside the file and asks what to restore. See [Backup] for the container.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BackupPage(controller: BackupController) {
    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) controller.export(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.onPicked(uri)
    }
    val icsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.importIcs(uri)
    }
    val resources = LocalResources.current
    // The create dialog asks the page to open the file picker.
    controller.launchCreate = { name -> createLauncher.launch(resources.getString(R.string.backup_file_settings, name)) }
    val working = controller.working

    SettingsCard {
        Text(stringResource(R.string.backup), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.backup_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(stringResource(R.string.backup_create), selected = false, enabled = !working) { controller.openCreate() }
            ChoiceChip(stringResource(R.string.backup_import), selected = false, enabled = !working) {
                // Any file: some devices label a .zip with another type (x-zip, x-compressed…) and the picker would grey it
                // out. Backup.inspect checks that it is a Celander backup and says so if it is not.
                importLauncher.launch(arrayOf("*/*"))
            }
            ChoiceChip(stringResource(R.string.backup_import_ics), selected = false, enabled = !working) { icsLauncher.launch(arrayOf("*/*")) }
        }
        val status = if (working) stringResource(R.string.backup_working) else controller.message
        if (!status.isNullOrEmpty()) {
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** The create and restore dialogs. Put them as the last child of a screen-sized box (see [BackupController]). */
@Composable
internal fun BackupDialogs(controller: BackupController) {
    GlassDialog(
        visible = controller.creating,
        title = stringResource(R.string.backup_create_title),
        message = "",
        confirmLabel = stringResource(R.string.backup_create_confirm),
        confirmEnabled = controller.dialogSettings || controller.dialogData,
        onDismiss = { controller.creating = false },
        onConfirm = {
            controller.creating = false
            controller.chosenSettings = controller.dialogSettings
            controller.chosenData = controller.dialogData
            controller.launchCreate?.invoke(SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()))
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RestoreRow(R.string.backup_restore_settings, controller.dialogSettings) { controller.dialogSettings = it }
            RestoreRow(R.string.backup_restore_data, controller.dialogData) { controller.dialogData = it }
        }
    }
    val contents = controller.pendingContents
    GlassDialog(
        visible = controller.restoring,
        title = stringResource(R.string.backup_restore_title),
        message = "",
        confirmLabel = stringResource(R.string.backup_restore_confirm),
        confirmEnabled = controller.dialogSettings || controller.dialogData,
        onDismiss = { controller.restoring = false },
        onConfirm = controller::restore,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (contents?.settings == true) RestoreRow(R.string.backup_restore_settings, controller.dialogSettings) { controller.dialogSettings = it }
            if (contents?.data == true) RestoreRow(R.string.backup_restore_data, controller.dialogData) { controller.dialogData = it }
        }
    }
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
