package com.celander.app.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celander.app.R
import com.celander.app.core.ui.Formats
import com.celander.app.core.ui.rememberFormats
import com.celander.app.ui.AccentButton
import com.celander.app.ui.theme.CelanderTheme
import com.celander.app.ui.GlassDialog
import com.celander.app.ui.Sizes
import com.celander.app.task.google.GoogleTasksState
import com.celander.app.task.google.GoogleTasksStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Settings › Google accounts: every Google account with its Google Tasks connection, and adding an
 * account in one flow.
 */
@Composable
fun GoogleAccountsScreen(
    onBack: () -> Unit,
    onOpenLists: () -> Unit,
    viewModel: GoogleAccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val timeFormat by viewModel.timeFormat.collectAsStateWithLifecycle()
    val formats = rememberFormats(timeFormat)
    val activity = LocalActivity.current
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onConsent(result.resultCode, result.data)
    }
    val chooserLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        activity?.let { viewModel.onAccountChosen(it, result.resultCode, result.data) }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is GoogleAccountsEvent.Consent -> consentLauncher.launch(IntentSenderRequest.Builder(event.intent).build())
            }
        }
    }
    // The account stays set while the dialog fades out, so its text does not change on the way.
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val current = state ?: return
    val removingRow = current.accounts.firstOrNull { it.account.email == removing }
    val tasksTitle = stringResource(R.string.settings_google_tasks)
    val dialogOpen = confirmRemove && removingRow != null

    SettingsScaffold(
        title = stringResource(R.string.google_accounts_title),
        onBack = onBack,
        snackbar = snackbar,
        onSnackbarAction = viewModel::onSnackbarAction,
        onSnackbarTimeout = viewModel::onSnackbarTimeout,
        modalOpen = dialogOpen,
        overlay = {
            val email = removingRow?.account?.email.orEmpty()
            val pending = removingRow?.pending ?: 0
            val loses = if (pending > 0) pluralStringResource(R.plurals.google_tasks_disconnect_loses, pending, pending) else null
            GlassDialog(
                visible = dialogOpen,
                title = stringResource(R.string.google_accounts_remove_title, email),
                message = listOfNotNull(stringResource(R.string.google_accounts_remove_message), loses).joinToString(" "),
                confirmLabel = stringResource(R.string.google_accounts_remove_confirm),
                onConfirm = {
                    confirmRemove = false
                    viewModel.remove(email)
                },
                onDismiss = { confirmRemove = false },
                destructive = true,
            )
        },
    ) {
        item(key = "intro") {
            Text(
                stringResource(R.string.google_accounts_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = Sizes.maxFormWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        current.accounts.forEach { row ->
            settingsGroup(row.account.email) {
                AccountRows(
                    row = row,
                    state = current,
                    formats = formats,
                    onConnectTasks = { activity?.let { viewModel.connectTasks(it, row.account.email) } },
                    onSyncNow = viewModel::syncNow,
                    onClearRescued = { viewModel.clearRescued(row.account.email) },
                    onRemove = {
                        removing = row.account.email
                        confirmRemove = true
                    },
                )
            }
        }
        item(key = "add") {
            Column(Modifier.widthIn(max = Sizes.maxFormWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                if (current.accounts.isEmpty()) {
                    Text(
                        stringResource(R.string.google_accounts_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                if (current.adding) {
                    Text(
                        current.busy?.let { stringResource(R.string.google_accounts_adding, it) } ?: stringResource(R.string.google_tasks_connecting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                } else {
                    AccentButton(
                        stringResource(R.string.google_accounts_add),
                        icon = Icons.Rounded.Add,
                        onClick = { chooserLauncher.launch(viewModel.chooserIntent()) },
                    )
                }
            }
        }
        if (current.accounts.any { it.account.tasks != null }) {
            settingsGroup(tasksTitle) {
                SettingsRow(title = stringResource(R.string.google_tasks_sync_now), onClick = viewModel::syncNow)
                SettingsDivider()
                SettingsRow(title = stringResource(R.string.google_tasks_lists), onClick = onOpenLists)
            }
            item(key = "local-only") {
                Text(
                    stringResource(R.string.google_tasks_local_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = Sizes.maxFormWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** One account: its Google Tasks connection with notes, and removing it from Celander. */
@Composable
private fun AccountRows(
    row: GoogleAccountRow,
    state: GoogleAccountsUiState,
    formats: Formats,
    onConnectTasks: () -> Unit,
    onSyncNow: () -> Unit,
    onClearRescued: () -> Unit,
    onRemove: () -> Unit,
) {
    val account = row.account
    val danger = MaterialTheme.colorScheme.error
    val tasks = account.tasks
    val tasksTitle = stringResource(R.string.settings_google_tasks)
    val polite = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    when {
        state.busy == account.email -> SettingsRow(title = tasksTitle, summary = stringResource(R.string.google_tasks_connecting), modifier = polite)
        tasks == null -> {
            val error = row.connectError
            SettingsRow(
                title = tasksTitle,
                summary = when {
                    !state.playServices -> stringResource(R.string.google_tasks_no_play_services)
                    error != null -> statusText(error, null)
                    else -> stringResource(R.string.google_accounts_tasks_off)
                },
                summaryColor = if (error != null || !state.playServices) danger else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = if (state.playServices && state.busy == null) onConnectTasks else null,
                modifier = polite,
            )
        }
        else -> {
            SettingsRow(
                title = tasksTitle,
                summary = statusText(tasks.status, tasks, formats),
                summaryColor = if (tasks.status.isProblem) danger else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = when (tasks.status) {
                    GoogleTasksStatus.NeedsReauth -> onConnectTasks
                    is GoogleTasksStatus.Failed, GoogleTasksStatus.Offline -> onSyncNow
                    else -> null
                },
                modifier = polite,
            )
            if (row.pending > 0) {
                SettingsDivider()
                SettingsRow(title = pluralStringResource(R.plurals.google_tasks_pending, row.pending, row.pending))
            }
            if (tasks.rejectedCount > 0) {
                SettingsDivider()
                SettingsRow(
                    title = pluralStringResource(R.plurals.google_tasks_rejected, tasks.rejectedCount, tasks.rejectedCount),
                    summaryColor = danger,
                )
            }
            val rescuedTo = tasks.rescuedTo
            if (tasks.rescuedCount > 0 && rescuedTo != null) {
                SettingsDivider()
                SettingsRow(
                    title = pluralStringResource(R.plurals.google_tasks_rescued, tasks.rescuedCount, tasks.rescuedCount, rescuedTo),
                    onClick = onClearRescued,
                )
            }
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.google_accounts_remove),
                summary = stringResource(R.string.google_accounts_remove_summary),
                summaryColor = danger,
                onClick = onRemove,
            )
        }
    }
}

/** Status as one line; [account] adds the time of the last sync when [formats] are given. */
@Composable
fun statusText(status: GoogleTasksStatus, account: GoogleTasksState?, formats: Formats? = null): String = when (status) {
    GoogleTasksStatus.Ok -> account?.lastSyncAt?.let { at ->
        formats?.let { stringResource(R.string.google_tasks_synced_at, syncedAt(it, at)) }
    } ?: stringResource(R.string.google_tasks_not_synced)
    GoogleTasksStatus.Syncing -> stringResource(R.string.google_tasks_syncing)
    GoogleTasksStatus.Offline -> stringResource(R.string.google_tasks_offline)
    GoogleTasksStatus.NeedsReauth -> stringResource(R.string.google_tasks_reauth)
    GoogleTasksStatus.SetupError -> stringResource(R.string.google_tasks_setup_error)
    GoogleTasksStatus.ApiDisabled -> stringResource(R.string.google_tasks_api_disabled)
    GoogleTasksStatus.NoPlayServices -> stringResource(R.string.google_tasks_no_play_services)
    is GoogleTasksStatus.Failed -> stringResource(R.string.google_tasks_failed, status.code)
}

/** Today only the time, otherwise day and time. */
fun syncedAt(formats: Formats, millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochMilli(millis).atZone(zone)
    val time = at.format(formats.time)
    return if (at.toLocalDate() == LocalDate.now(zone)) time else formats.capitalize(at.format(formats.dayMonthShort)) + ", " + time
}
