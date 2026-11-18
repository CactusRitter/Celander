package com.celander.app.account.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.rounded.Link
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.celander.app.ui.CalendarColor
import com.celander.app.ui.GoogleLogo
import com.celander.app.ui.MicrosoftLogo
import com.celander.app.ui.theme.LocalGlass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import com.celander.app.core.model.AccountType
import com.celander.app.settings.SettingsDivider
import com.celander.app.settings.SettingsRow
import com.celander.app.settings.SettingsScaffold
import com.celander.app.settings.SettingsSwitchRow
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
import com.celander.app.task.google.GoogleTasksState
import com.celander.app.task.google.GoogleTasksStatus

/**
 * Settings › Accounts, laid out like Cemail's: a big row for each account that opens its page, and an "Add" button. An
 * account's page shows who it is, when it last synced, "Sync now" (or "Sign in again") and, at the end, "Remove".
 */
@Composable
fun AccountsScreen(onBack: () -> Unit, viewModel: AccountsViewModel = hiltViewModel()) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val snackbar by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val tasksStates by viewModel.tasksStates.collectAsStateWithLifecycle()
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onSignInResult(result.data)
    }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var removing by rememberSaveable { mutableStateOf(false) }
    var subscribing by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var subscriptionUrl by rememberSaveable { mutableStateOf("") }
    var subscriptionName by rememberSaveable { mutableStateOf("") }
    val list = accounts ?: return
    val account = list.firstOrNull { it.id == selected }
    // An account that was removed (or is not there any more) takes the page back to the list.
    if (selected != null && account == null) selected = null
    BackHandler(enabled = account != null || adding) { if (account != null) selected = null else adding = false }
    val photoVersion by viewModel.photoVersion.collectAsStateWithLifecycle()
    val startSignIn = { type: AccountType, hint: String? -> viewModel.signInIntent(type, hint)?.let(signIn::launch) ?: viewModel.onSignInResult(null) }
    val title = stringResource(R.string.accounts_title)
    val tasksTitle = stringResource(R.string.google_tasks_title)

    SettingsScaffold(
        title = account?.displayName ?: if (adding) stringResource(R.string.accounts_add_title) else title,
        onBack = { if (account != null) selected = null else if (adding) adding = false else onBack() },
        snackbar = snackbar,
        onSnackbarAction = viewModel::onSnackbarAction,
        onSnackbarTimeout = viewModel::onSnackbarTimeout,
        modalOpen = removing || subscribing,
        overlay = {
            // The link of a calendar to subscribe to, and what to call it (optional), in the same glass as the other dialogs.
            GlassDialog(
                visible = subscribing,
                title = stringResource(R.string.accounts_subscription_title),
                message = stringResource(R.string.accounts_subscription_body),
                confirmLabel = stringResource(R.string.accounts_subscription_confirm),
                onConfirm = {
                    subscribing = false
                    viewModel.addSubscription(subscriptionUrl, subscriptionName)
                    subscriptionUrl = ""
                    subscriptionName = ""
                },
                onDismiss = { subscribing = false },
                confirmEnabled = subscriptionUrl.isNotBlank(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = subscriptionUrl,
                        onValueChange = { subscriptionUrl = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.accounts_subscription_url)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        value = subscriptionName,
                        onValueChange = { subscriptionName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.accounts_subscription_name)) },
                    )
                }
            }
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
        if (adding && account == null) {
            addAccountItems(
                googleConfigured = viewModel.googleConfigured,
                microsoftConfigured = viewModel.microsoftConfigured,
                onGoogle = { adding = false; startSignIn(AccountType.GOOGLE, null) },
                onMicrosoft = { adding = false; startSignIn(AccountType.MICROSOFT, null) },
                onSubscription = { adding = false; subscribing = true },
            )
        } else if (account == null) {
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
                            stringResource(R.string.accounts_row_body, row.identifier, providerName(row)),
                            if (attention) statusText(row) else null,
                        ).joinToString("\n"),
                        index = index,
                        onClick = { selected = row.id },
                        modifier = Modifier.widthIn(max = Sizes.maxFormWidth).padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        AccountAvatar(row, viewModel.photoFile(row.id), photoVersion, size = 52)
                    }
                }
            }
            item(key = "add") {
                AddButton(text = stringResource(R.string.accounts_add_button), onClick = { adding = true })
            }
        } else {
            item(key = "header") {
                SettingsCard(Modifier.widthIn(max = Sizes.maxFormWidth).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AccountAvatar(account, viewModel.photoFile(account.id), photoVersion, size = 56)
                        androidx.compose.foundation.layout.Column {
                            Text(account.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(account.identifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(providerName(account), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
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
                    SettingsRow(title = stringResource(R.string.accounts_sign_in_again), onClick = { startSignIn(AccountType.valueOf(account.type), account.identifier) })
                } else {
                    SettingsRow(title = stringResource(R.string.accounts_sync_now), onClick = { viewModel.syncNow(account.id) })
                }
            }
            // Tasks are Google's: a Microsoft account has no such switch.
            if (account.type == AccountType.GOOGLE.name) {
                settingsGroup(tasksTitle) {
                    val tasks = tasksStates[account.identifier]
                    SettingsSwitchRow(
                        title = stringResource(R.string.google_tasks_title),
                        checked = tasks != null,
                        onCheckedChange = { viewModel.setTasks(account.identifier, it) },
                        summary = tasks?.let { tasksSummary(it) } ?: stringResource(R.string.accounts_tasks_summary),
                    )
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

/** The provider an account belongs to, as the user knows it. */
@Composable
private fun providerName(account: AccountEntity): String = stringResource(
    when (account.type) {
        AccountType.MICROSOFT.name -> R.string.accounts_provider_microsoft
        AccountType.ICS_URL.name -> R.string.accounts_provider_subscription
        else -> R.string.accounts_provider_google
    },
)


/**
 * The round mark of an account, as in Cemail: its picture at Google or Microsoft (the first letter while there is none),
 * inside a ring of the colour that belongs to the account. [photoVersion] makes it reload when a picture arrives.
 */
@Composable
private fun AccountAvatar(account: AccountEntity, photoFile: java.io.File, photoVersion: Int, size: Int = 52) {
    val photo by produceState<ImageBitmap?>(null, account.id, photoVersion) {
        value = withContext(Dispatchers.IO) {
            if (photoFile.exists()) BitmapFactory.decodeFile(photoFile.path)?.asImageBitmap() else null
        }
    }
    val dark = LocalGlass.current.dark
    val color = CalendarColor.entries[(account.id % CalendarColor.entries.size).toInt()].base(dark)
    Box(Modifier.size(size.dp).border(2.dp, color, CircleShape), contentAlignment = Alignment.Center) {
        val inner = (size - 6).dp
        val image = photo
        if (image != null) {
            Image(image, null, Modifier.size(inner).clip(CircleShape), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.size(inner).background(color.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                Text(
                    (account.displayName.firstOrNull() ?: account.identifier.firstOrNull() ?: '?').uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** The kinds of account that can be added, as big cards with the logo of each service (like Cemail's "Add account"). */
private fun androidx.compose.foundation.lazy.LazyListScope.addAccountItems(
    googleConfigured: Boolean,
    microsoftConfigured: Boolean,
    onGoogle: () -> Unit,
    onMicrosoft: () -> Unit,
    onSubscription: () -> Unit,
) {
    item(key = "add-google") {
        val surface = MaterialTheme.colorScheme.surface
        BigRow(stringResource(R.string.accounts_provider_google), stringResource(R.string.accounts_add_google_body), 0, onGoogle, Modifier.widthIn(max = Sizes.maxFormWidth).padding(horizontal = 12.dp, vertical = 6.dp), verticalPadding = 22.dp) {
            IconBubble(60.dp, surface) { GoogleLogo(32.dp) }
        }
    }
    item(key = "add-microsoft") {
        val surface = MaterialTheme.colorScheme.surface
        BigRow(stringResource(R.string.accounts_provider_microsoft), stringResource(R.string.accounts_add_microsoft_body), 1, onMicrosoft, Modifier.widthIn(max = Sizes.maxFormWidth).padding(horizontal = 12.dp, vertical = 6.dp), verticalPadding = 22.dp) {
            IconBubble(60.dp, surface) { MicrosoftLogo(30.dp) }
        }
    }
    item(key = "add-subscription") {
        BigRow(stringResource(R.string.accounts_add_subscription), stringResource(R.string.accounts_add_subscription_body), 2, onSubscription, Modifier.widthIn(max = Sizes.maxFormWidth).padding(horizontal = 12.dp, vertical = 6.dp), verticalPadding = 22.dp) {
            IconBubble(60.dp) { Icon(Icons.Rounded.Link, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
    }
    if (!googleConfigured) {
        item(key = "google-not-configured") { MissingNote(R.string.accounts_google_missing) }
    }
    if (!microsoftConfigured) {
        item(key = "microsoft-not-configured") { MissingNote(R.string.accounts_microsoft_missing) }
    }
}

@Composable
private fun MissingNote(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.widthIn(max = Sizes.maxFormWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    )
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

/** The state of a connected Google Tasks account: running, the problem, or when it last synced. */
@Composable
private fun tasksSummary(state: GoogleTasksState): String = when (val status = state.status) {
    GoogleTasksStatus.Syncing -> stringResource(R.string.google_tasks_syncing)
    GoogleTasksStatus.Offline -> stringResource(R.string.google_tasks_offline)
    GoogleTasksStatus.NeedsReauth -> stringResource(R.string.google_tasks_reauth)
    GoogleTasksStatus.SetupError -> stringResource(R.string.google_tasks_setup_error)
    GoogleTasksStatus.ApiDisabled -> stringResource(R.string.google_tasks_api_disabled)
    is GoogleTasksStatus.Failed -> stringResource(R.string.google_tasks_failed, status.code)
    GoogleTasksStatus.Ok -> state.lastSyncAt?.let {
        stringResource(
            R.string.google_tasks_synced_at,
            DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
        )
    } ?: stringResource(R.string.google_tasks_not_synced)
}
