package com.celander.app.account.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.celander.app.R
import com.celander.app.account.AccountRepository
import com.celander.app.account.AddAccountResult
import com.celander.app.core.database.AccountEntity
import com.celander.app.core.ui.SnackbarManager
import com.celander.app.core.ui.SnackbarMessage
import com.celander.app.core.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val repository: AccountRepository,
    private val snackbar: SnackbarManager,
) : ViewModel() {
    val accounts: StateFlow<List<AccountEntity>?> = repository.accounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val snackbarMessage: StateFlow<SnackbarMessage?> = snackbar.current

    val googleConfigured: Boolean get() = repository.googleConfigured

    fun googleSignInIntent(loginHint: String? = null): Intent? = repository.googleSignInIntent(loginHint)

    /** What came back from the browser sign-in. */
    fun onSignInResult(data: Intent?) {
        android.util.Log.i("Accounts", "browser returned, data=" + (data != null))
        viewModelScope.launch {
            when (val result = repository.addGoogle(data)) {
                is AddAccountResult.Added -> snackbar.show(UiText.Res(R.string.accounts_added, listOf(result.identifier)))
                AddAccountResult.Cancelled -> Unit
                AddAccountResult.NotConfigured -> snackbar.show(UiText.Res(R.string.accounts_google_missing))
                is AddAccountResult.Failed -> snackbar.show(UiText.Res(R.string.accounts_sign_in_failed))
            }
        }
    }

    fun syncNow(id: Long) {
        viewModelScope.launch { repository.syncAccount(id) }
    }

    fun remove(id: Long) {
        viewModelScope.launch { repository.remove(id) }
    }

    fun onSnackbarAction(message: SnackbarMessage) = snackbar.performAction(message)

    fun onSnackbarTimeout(message: SnackbarMessage) = snackbar.dismiss(message.id)
}
