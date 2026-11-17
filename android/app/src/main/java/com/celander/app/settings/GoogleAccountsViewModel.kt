package com.celander.app.settings

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.celander.app.R
import com.celander.app.core.database.GoogleTasksDao
import com.celander.app.core.ui.SnackbarManager
import com.celander.app.core.ui.SnackbarMessage
import com.celander.app.core.ui.UiText
import com.celander.app.task.google.AuthResult
import com.celander.app.task.google.DefaultListChange
import com.celander.app.task.google.GoogleAuthorizer
import com.celander.app.task.google.GoogleTasksAccounts
import com.celander.app.task.google.GoogleTasksConnection
import com.celander.app.task.google.GoogleTasksStatus
import com.celander.app.task.google.SyncOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One account on the page: what Celander knows of it, its unsent changes and why connecting Tasks last failed. */
data class GoogleAccountRow(val account: GoogleAccount, val pending: Int = 0, val connectError: GoogleTasksStatus? = null)

data class GoogleAccountsUiState(
    val accounts: List<GoogleAccountRow>,
    /** The account being added or connected to Google Tasks. */
    val busy: String? = null,
    /** An account is being added, from the account chooser to the result message. */
    val adding: Boolean = false,
    val playServices: Boolean = true,
)

/** What the page has to launch for the view model. */
sealed interface GoogleAccountsEvent {
    /** Google's consent screen, for `StartIntentSenderForResult`. */
    data class Consent(val intent: PendingIntent) : GoogleAccountsEvent
}

/**
 * Settings › Google accounts: every Google account Celander knows with its Google Tasks connection, adding an account in one flow, and removing one from Celander.
 */
@HiltViewModel
class GoogleAccountsViewModel @Inject constructor(
    googleAccounts: GoogleAccounts,
    private val tasks: GoogleTasksAccounts,
    private val connection: GoogleTasksConnection,
    private val authorizer: GoogleAuthorizer,
    private val settings: SettingsRepository,
    private val snackbar: SnackbarManager,
    dao: GoogleTasksDao,
) : ViewModel() {
    val timeFormat: StateFlow<TimeFormat> = settings.settings.map { it.timeFormat }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimeFormat.SYSTEM)

    private val playServices = authorizer.playServicesAvailable()
    private val busy = MutableStateFlow<String?>(null)
    private val adding = MutableStateFlow(false)
    private val connectErrors = MutableStateFlow<Map<String, GoogleTasksStatus>>(emptyMap())

    /** Accounts added on this page whose Google Tasks connection is not there yet. */
    private val added = MutableStateFlow<Set<String>>(emptySet())

    private val rows = combine(googleAccounts.accounts(added), dao.observePendingCounts(), connectErrors) { list, pending, errors ->
        val counts = pending.associate { it.account to it.count }
        list.map { GoogleAccountRow(it, counts[it.email] ?: 0, errors[it.email]) }
    }

    val state: StateFlow<GoogleAccountsUiState?> = combine(rows, busy, adding) { accounts, current, add ->
        GoogleAccountsUiState(accounts, current, add, playServices)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val snackbarMessage: StateFlow<SnackbarMessage?> = snackbar.current

    private val _events = MutableSharedFlow<GoogleAccountsEvent>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<GoogleAccountsEvent> = _events.asSharedFlow()

    /** The sign-in under way: adding a whole account, or only connecting Google Tasks for one. */
    private var pending: Pending? = null

    private sealed interface Pending {
        val email: String

        data class Add(override val email: String) : Pending

        data class Connect(override val email: String) : Pending
    }

    /** Step 1 of adding: the system account chooser, which can also add a new account to the phone. */
    fun chooserIntent(): Intent {
        adding.value = true
        return authorizer.chooseAccountIntent(null)
    }

    /** Step 2: the chosen account signs in to Google Tasks (without Play services, nothing is connected). */
    fun onAccountChosen(activity: Activity, resultCode: Int, data: Intent?) {
        val email = authorizer.accountFromChooser(resultCode, data) ?: return run { adding.value = false }
        start(Pending.Add(email))
        viewModelScope.launch {
            if (playServices) handle(authorizer.authorize(activity, email)) else finishAdd(email, tasksConnected = false)
        }
    }

    /** Connects Google Tasks for an account already listed, also "sign in again" after the grant expired. */
    fun connectTasks(activity: Activity, email: String) {
        start(Pending.Connect(email))
        viewModelScope.launch { handle(authorizer.authorize(activity, email)) }
    }

    private fun start(next: Pending) {
        pending = next
        busy.value = next.email
        connectErrors.update { it - next.email }
    }

    fun onConsent(resultCode: Int, data: Intent?) {
        viewModelScope.launch { handle(authorizer.consentResult(resultCode, data)) }
    }

    private suspend fun handle(result: AuthResult) {
        val current = pending ?: return
        when (result) {
            is AuthResult.Granted -> {
                val connected = connection.connected(current.email, switchDefault = current is Pending.Connect)
                when (current) {
                    is Pending.Add -> finishAdd(current.email, tasksConnected = connected.outcome !is SyncOutcome.Stopped)
                    is Pending.Connect -> {
                        done()
                        connected.defaultChange?.let { change ->
                            snackbar.show(UiText.Res(R.string.google_tasks_default_changed, listOf(change.listName)), UiText.Res(R.string.action_undo)) {
                                connection.undoDefaultChange(change)
                            }
                        }
                    }
                }
            }
            is AuthResult.NeedsConsent -> result.consent?.let { _events.tryEmit(GoogleAccountsEvent.Consent(it)) } ?: failed(current, GoogleTasksStatus.NeedsReauth)
            AuthResult.Cancelled -> if (current is Pending.Add) finishAdd(current.email, tasksConnected = false) else done()
            is AuthResult.Failed -> failed(current, result.status)
        }
    }

    private suspend fun failed(current: Pending, status: GoogleTasksStatus) {
        connectErrors.update { it + (current.email to status) }
        if (tasks[current.email] != null) tasks.setStatus(current.email, status)
        if (current is Pending.Add) {
            finishAdd(current.email, tasksConnected = false)
        } else {
            done()
            snackbar.show(UiText.Res(R.string.google_tasks_connect_failed))
        }
    }

    /**
     * Step 3 of adding: one message tells whether the account is connected. When it is, new tasks go
     * to it, which the message can undo.
     */
    private suspend fun finishAdd(email: String, tasksConnected: Boolean) {
        added.update { it + email }
        val taskChange: DefaultListChange? = if (tasksConnected) connection.makeDefault(email) else null
        done()
        val message = when {
            !tasksConnected -> R.string.google_accounts_add_failed
            taskChange != null -> R.string.google_accounts_added_default_tasks
            else -> R.string.google_accounts_added
        }
        if (taskChange == null) {
            snackbar.show(UiText.Res(message, listOf(email)))
        } else {
            snackbar.show(UiText.Res(message, listOf(email)), UiText.Res(R.string.action_undo)) {
                connection.undoDefaultChange(taskChange)
            }
        }
    }

    private fun done() {
        pending = null
        busy.value = null
        adding.value = false
    }

    fun syncNow() = connection.syncNow()

    fun clearRescued(email: String) = tasks.clearRescued(email)

    /** Disconnects Google Tasks of [email] and removes its lists from this phone. */
    fun remove(email: String) {
        viewModelScope.launch {
            connection.disconnect(email)
            added.update { it - email }
            connectErrors.update { it - email }
            snackbar.show(UiText.Res(R.string.google_accounts_removed, listOf(email)))
        }
    }

    fun onSnackbarAction(message: SnackbarMessage) = snackbar.performAction(message)

    fun onSnackbarTimeout(message: SnackbarMessage) = snackbar.dismiss(message.id)
}
