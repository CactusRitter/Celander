package com.celander.app.account.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celander.app.R
import com.celander.app.core.database.AccountEntity
import com.celander.app.core.model.AccountStatus
import com.celander.app.settings.SettingsDivider
import com.celander.app.settings.SettingsRow
import com.celander.app.settings.SettingsScaffold
import com.celander.app.settings.settingsGroup
import com.celander.app.ui.BigRow
import com.celander.app.ui.GlassDialog
import com.celander.app.ui.IconBubble
import com.celander.app.ui.Sizes
import com.celander.app.ui.SettingsCard
import com.celander.app.ui.TextAction
import com.celander.app.ui.glass.GlassKind
import com.celander.app.ui.glass.glass
import com.celander.app.ui.glass.pressScale

/**
 * Settings › Accounts, laid out like Cemail's: a big row for each account that opens its page, and an "Add" button. An
 * account's page shows who it is, when it last synced, "Sync now" (or "Sign in again") and, at the end, "Remove".
 */
@Composable
fun AccountsScreen(onBack: () -> Unit, viewModel: AccountsViewModel = hiltViewModel()) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val snackbar by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onSignInResult(result.data)
    }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var removing by rememberSaveable { mutableStateOf(false) }
    val list = accounts ?: return
    val account = list.firstOrNull { it.id == selected }
    // An account that was removed (or is not there any more) takes the page back to the list.
    if (selected != null && account == null) selected = null
    BackHandler(enabled = account != null) { selected = null }
    val startSignIn = { hint: String? -> viewModel.googleSignInIntent(hint)?.let(signIn::launch) ?: viewModel.onSignInResult(null) }
    val title = stringResource(R.string.accounts_title)

    SettingsScaffold(
        title = account?.displayName ?: title,
        onBack = { if (account != null) selected = null else onBack() },
        snackbar = snackbar,
        onSnackbarAction = viewModel::onSnackbarAction,
        onSnackbarTimeout = viewModel::onSnackbarTimeout,
        modalOpen = removing,
        overlay = {
            GlassDialog(
                visible = removing && account != null,
                title = stringResource(R.string.accounts_remove_title, account?.identifier.orEmpty()),
                message = stringResource(R.string.accounts_remove_message),
                confirmLabel = stringResource(R.string.accounts_remove_confirm),
                onConfirm = {
                    removing = false
                    account?.let { viewModel.remove(it.id) }
                    selected = null
                },
                onDismiss = { removing = false },
                destructive = true,
            )
        },
    ) {
        if (account == null) {
            item(key = "intro") {
                Text(
                    stringResource(R.string.accounts_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = Sizes.maxFormWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (list.isEmpty()) {
                item(key = "none") {
                    Text(
                        stringResource(R.string.accounts_summary_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = Sizes.maxFormWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            list.forEachIndexed { index, row ->
                item(key = "account:${row.id}") {
                    val attention = row.status != AccountStatus.OK.name
                    BigRow(
                        title = row.displayName,
                        body = listOfNotNull(
                            stringResource(R.string.accounts_row_body, row.identifier, stringResource(R.string.accounts_provider_google)),
                            if (attention) statusText(row) else null,
                        ).joinToString("\n"),
                        index = index,
                        onClick = { selected = row.id },
                        modifier = Modifier.widthIn(max = Sizes.maxFormWidth).padding(horizontal = 12.dp),
                    ) {
                        Initial(row)
                    }
                }
            }
            item(key = "add") {
                AddButton(
                    text = stringResource(R.string.accounts_add_button),
                    onClick = { startSignIn(null) },
                )
            }
            if (!viewModel.googleConfigured) {
                item(key = "not-configured") {
                    Text(
                        stringResource(R.string.accounts_google_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.widthIn(max = Sizes.maxFormWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
        } else {
            item(key = "header") {
                SettingsCard(Modifier.widthIn(max = Sizes.maxFormWidth).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Initial(account, size = 56)
                        androidx.compose.foundation.layout.Column {
                            Text(account.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(account.identifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.accounts_provider_google), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            settingsGroup(title) {
                SettingsRow(
                    title = syncSummary(account),
                    summaryColor = if (account.status == AccountStatus.OK.name) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                SettingsDivider()
                if (account.status == AccountStatus.NEEDS_SIGN_IN.name) {
                    SettingsRow(title = stringResource(R.string.accounts_sign_in_again), onClick = { startSignIn(account.identifier) })
                } else {
                    SettingsRow(title = stringResource(R.string.accounts_sync_now), onClick = { viewModel.syncNow(account.id) })
                }
            }
            item(key = "remove") {
                TextAction(
                    text = stringResource(R.string.accounts_remove),
                    onClick = { removing = true },
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** The round mark of an account: its first letter. */
@Composable
private fun Initial(account: AccountEntity, size: Int = 48) {
    IconBubble(size = size.dp) {
        Text(
            (account.displayName.firstOrNull() ?: account.identifier.firstOrNull() ?: '?').uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun statusText(account: AccountEntity): String = when (account.status) {
    AccountStatus.NEEDS_SIGN_IN.name -> stringResource(R.string.accounts_needs_sign_in)
    else -> stringResource(R.string.accounts_error)
}

@Composable
private fun syncSummary(account: AccountEntity): String = when {
    account.status != AccountStatus.OK.name -> statusText(account)
    account.lastSyncAt == 0L -> stringResource(R.string.accounts_never_synced)
    else -> stringResource(
        R.string.accounts_last_sync,
        DateUtils.getRelativeTimeSpanString(account.lastSyncAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
    )
}

/** The pill under the list: a glass button with a plus and "Add". */
@Composable
private fun AddButton(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .pressScale(0.96f)
            .glass(CircleShape, GlassKind.Pinned)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** The line under "Accounts" in Settings: the connected addresses, or that there are none. */
@Composable
fun accountsSummary(viewModel: AccountsViewModel = hiltViewModel()): String {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    return accounts?.joinToString { it.identifier }?.ifEmpty { null } ?: stringResource(R.string.accounts_summary_none)
}
