package com.celander.app.account.ui

import com.celander.app.AppLog
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.celander.app.R
import com.celander.app.account.AccountRepository
import com.celander.app.account.AccountPhotos
import com.celander.app.account.AddAccountResult
import com.celander.app.core.database.AccountEntity
import com.celander.app.core.model.AccountType
import com.celander.app.core.ui.SnackbarManager
import com.celander.app.core.ui.SnackbarMessage
import com.celander.app.core.ui.UiText
import com.celander.app.task.google.GoogleTasksAccounts
import com.celander.app.task.google.GoogleTasksConnection
import com.celander.app.task.google.GoogleTasksState
import com.celander.app.task.google.SyncOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val repository: AccountRepository,
    private val snackbar: SnackbarManager,
    private val tasks: GoogleTasksAccounts,
    private val tasksConnection: GoogleTasksConnection,
    private val photos: AccountPhotos,
) : ViewModel() {
    val accounts: StateFlow<List<AccountEntity>?> = repository.accounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Changes whenever any account picture does. */
    val photoVersion: StateFlow<Int> = photos.version

    fun photoFile(accountId: Long): java.io.File = photos.file(accountId)

    init {
        // The pictures of the accounts, fetched the first time and checked again every few days.
        viewModelScope.launch { repository.accounts.collect { list -> list.forEach { photos.refreshIfStale(it) } } }
    }
    val snackbarMessage: StateFlow<SnackbarMessage?> = snackbar.current

    /** The Google Tasks connection of each connected account, by e-mail address. */
    val tasksStates: StateFlow<Map<String, GoogleTasksState>> = tasks.state.map { list -> list.associateBy { it.email } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), tasks.state.value.associateBy { it.email })

    private val switching = MutableStateFlow(emptySet<String>())

    /** Marks [email] as being switched; false when it already is, so a second tap is ignored. */
    private fun MutableStateFlow<Set<String>>.tryAdd(email: String): Boolean {
        while (true) {
            val current = value
            if (email in current) return false
            if (compareAndSet(current, current + email)) return true
        }
    }

    val googleConfigured: Boolean get() = repository.googleConfigured

    val microsoftConfigured: Boolean get() = repository.microsoftConfigured

    /** Whose sign-in the browser is showing: the answer that comes back is for this kind of account. */
    private var signingIn = AccountType.GOOGLE

    /** The browser sign-in for an account of [type]; [loginHint] names the address to offer first. */
    fun signInIntent(type: AccountType, loginHint: String? = null): Intent? {
        signingIn = type
        return if (type == AccountType.MICROSOFT) repository.microsoftSignInIntent(loginHint) else repository.googleSignInIntent(loginHint)
    }

    /** Subscribes to the calendar at [url]; [name] may be empty. */
    fun addSubscription(url: String, name: String) {
        viewModelScope.launch {
            when (val result = repository.addSubscription(url, name)) {
                is AddAccountResult.Added -> snackbar.show(UiText.Res(R.string.accounts_added, listOf(result.identifier)))
                else -> snackbar.show(UiText.Res(R.string.accounts_subscription_failed))
            }
        }
    }

    /** What came back from the browser sign-in. */
    fun onSignInResult(data: Intent?) {
        AppLog.i("accounts", "browser returned, data=" + (data != null))
        val type = signingIn
        viewModelScope.launch {
            val outcome = if (type == AccountType.MICROSOFT) repository.addMicrosoft(data) else repository.addGoogle(data)
            when (val result = outcome) {
                is AddAccountResult.Added -> snackbar.show(UiText.Res(R.string.accounts_added, listOf(result.identifier)))
                AddAccountResult.Cancelled -> Unit
                AddAccountResult.NotConfigured -> snackbar.show(UiText.Res(if (type == AccountType.MICROSOFT) R.string.accounts_microsoft_missing else R.string.accounts_google_missing))
                is AddAccountResult.Failed -> snackbar.show(UiText.Res(R.string.accounts_sign_in_failed))
            }
        }
    }

    /**
     * The Google Tasks switch of the account [email]: on connects and runs the first sync (new tasks go to its default list
     * when none is set, which the message can undo); off removes its lists from this phone. Tasks at Google stay.
     */
    fun setTasks(email: String, on: Boolean) {
        if (!switching.tryAdd(email)) return
        viewModelScope.launch {
            try {
                if (on) connectTasks(email) else tasksConnection.disconnect(email)
            } finally {
                switching.update { it - email }
            }
        }
    }

    private suspend fun connectTasks(email: String) {
        val result = tasksConnection.connected(email)
        if (result.outcome is SyncOutcome.Stopped) snackbar.show(UiText.Res(R.string.google_tasks_connect_failed))
        result.defaultChange?.let { change ->
            snackbar.show(UiText.Res(R.string.google_tasks_default_changed, listOf(change.listName)), UiText.Res(R.string.action_undo)) {
                tasksConnection.undoDefaultChange(change)
            }
        }
    }

    fun syncNow(id: Long) {
        viewModelScope.launch { repository.syncAccount(id) }
    }

    fun remove(id: Long) {
        viewModelScope.launch {
            repository.remove(id)
            photos.remove(id)
        }
    }

    fun onSnackbarAction(message: SnackbarMessage) = snackbar.performAction(message)

    fun onSnackbarTimeout(message: SnackbarMessage) = snackbar.dismiss(message.id)
}
