package com.celander.app.account.ui

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import com.celander.app.ui.GlassDialog
import com.celander.app.ui.Sizes

/** Settings › Accounts: the accounts whose calendars Celander syncs, adding one, syncing now, removing. */
@Composable
fun AccountsScreen(onBack: () -> Unit, viewModel: AccountsViewModel = hiltViewModel()) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val snackbar by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onSignInResult(result.data)
    }
    var removing by rememberSaveable { mutableStateOf<Long?>(null) }
    val list = accounts ?: return
    val removingAccount = list.firstOrNull { it.id == removing }
    val title = stringResource(R.string.accounts_title)
    val startSignIn = { hint: String? -> viewModel.googleSignInIntent(hint)?.let(signIn::launch) ?: viewModel.onSignInResult(null) }

    SettingsScaffold(
        title = stringResource(R.string.accounts_title),
        onBack = onBack,
        snackbar = snackbar,
        onSnackbarAction = viewModel::onSnackbarAction,
        onSnackbarTimeout = viewModel::onSnackbarTimeout,
        modalOpen = removingAccount != null,
        overlay = {
            GlassDialog(
                visible = removingAccount != null,
                title = stringResource(R.string.accounts_remove_title, removingAccount?.identifier.orEmpty()),
                message = stringResource(R.string.accounts_remove_message),
                confirmLabel = stringResource(R.string.accounts_remove_confirm),
                onConfirm = {
                    removing?.let(viewModel::remove)
                    removing = null
                },
                onDismiss = { removing = null },
                destructive = true,
            )
        },
    ) {
        item(key = "intro") {
            Text(
                stringResource(R.string.accounts_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = Sizes.maxFormWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        list.forEach { account ->
            settingsGroup(account.identifier) {
                AccountRows(account, onSyncNow = { viewModel.syncNow(account.id) }, onSignInAgain = { startSignIn(account.identifier) }, onRemove = { removing = account.id })
            }
        }
        settingsGroup(title) {
            SettingsRow(
                title = stringResource(R.string.accounts_add_google),
                summary = if (viewModel.googleConfigured) null else stringResource(R.string.accounts_google_missing),
                onClick = { startSignIn(null) },
            )
        }
    }
}

@Composable
private fun AccountRows(account: AccountEntity, onSyncNow: () -> Unit, onSignInAgain: () -> Unit, onRemove: () -> Unit) {
    val summary = when (account.status) {
        AccountStatus.NEEDS_SIGN_IN.name -> stringResource(R.string.accounts_needs_sign_in)
        AccountStatus.ERROR.name -> stringResource(R.string.accounts_error)
        else -> if (account.lastSyncAt == 0L) {
            stringResource(R.string.accounts_never_synced)
        } else {
            stringResource(R.string.accounts_last_sync, DateUtils.getRelativeTimeSpanString(account.lastSyncAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString())
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        SettingsRow(
            title = account.displayName,
            summary = summary,
            summaryColor = if (account.status == AccountStatus.OK.name) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        SettingsDivider()
        if (account.status == AccountStatus.NEEDS_SIGN_IN.name) {
            SettingsRow(title = stringResource(R.string.accounts_sign_in_again), onClick = onSignInAgain)
        } else {
            SettingsRow(title = stringResource(R.string.accounts_sync_now), onClick = onSyncNow)
        }
        SettingsDivider()
        SettingsRow(title = stringResource(R.string.accounts_remove), onClick = onRemove)
    }
}

/** The line under "Accounts" in Settings: the connected addresses, or that there are none. */
@Composable
fun accountsSummary(viewModel: AccountsViewModel = hiltViewModel()): String {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    return accounts?.joinToString { it.identifier }?.ifEmpty { null } ?: stringResource(R.string.accounts_summary_none)
}
