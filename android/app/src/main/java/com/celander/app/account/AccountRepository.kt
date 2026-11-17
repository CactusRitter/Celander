package com.celander.app.account

import android.content.Intent
import com.celander.app.account.auth.GoogleAuth
import com.celander.app.account.auth.SignInOutcome
import com.celander.app.account.auth.TokenStore
import com.celander.app.core.database.AccountDao
import com.celander.app.core.database.AccountEntity
import com.celander.app.core.model.AccountStatus
import com.celander.app.core.model.AccountType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How adding an account ended. */
sealed interface AddAccountResult {
    data class Added(val accountId: Long, val identifier: String, val outcome: SyncOutcome) : AddAccountResult

    data object Cancelled : AddAccountResult

    data object NotConfigured : AddAccountResult

    data class Failed(val reason: String) : AddAccountResult
}

/** The accounts whose calendars Celander syncs: signing in, syncing, removing. Screens read the database through [accounts]. */
@Singleton
class AccountRepository @Inject constructor(
    private val dao: AccountDao,
    private val sync: AccountSync,
    private val tokens: TokenStore,
    private val google: GoogleAuth,
) {
    val accounts: Flow<List<AccountEntity>> = dao.observeAccounts()

    /** Only one sync runs at a time, whoever asks. */
    private val lock = Mutex()

    val googleConfigured: Boolean get() = google.isConfigured

    /** Opens Google's sign-in in the browser; hand what comes back to [addGoogle]. [loginHint] names an account to offer first. */
    fun googleSignInIntent(loginHint: String? = null): Intent? = google.signInIntent(loginHint)

    suspend fun addGoogle(data: Intent?): AddAccountResult = when (val outcome = google.handleResult(data).also { android.util.Log.i(TAG, "sign-in result: ${it.javaClass.simpleName}" + ((it as? SignInOutcome.Failed)?.let { f -> " (${f.reason})" } ?: "")) }) {
        SignInOutcome.Cancelled -> AddAccountResult.Cancelled
        SignInOutcome.NotConfigured -> AddAccountResult.NotConfigured
        is SignInOutcome.Failed -> AddAccountResult.Failed(outcome.reason)
        is SignInOutcome.Success -> {
            val result = outcome.result
            val existing = dao.account(AccountType.GOOGLE.name, result.email)
            val id = existing?.id ?: dao.insertAccount(
                AccountEntity(
                    type = AccountType.GOOGLE.name, identifier = result.email, displayName = result.name ?: result.email,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            tokens.save(AccountTokens.key(id), result.tokens)
            existing?.let { dao.updateAccount(it.copy(status = AccountStatus.OK.name, lastError = null)) }
            AddAccountResult.Added(id, result.email, syncAccount(id))
        }
    }

    suspend fun syncAccount(id: Long): SyncOutcome = lock.withLock { sync.sync(id).also { android.util.Log.i(TAG, "sync of account $id: $it") } }

    /** Syncs every account (one after the other). */
    suspend fun syncAll() {
        dao.accounts().forEach { syncAccount(it.id) }
    }

    /** Removes an account with its calendars and events on this device and its tokens. The server is not touched. */
    suspend fun remove(id: Long) = lock.withLock {
        sync.remove(id)
        tokens.clear(AccountTokens.key(id))
    }

    /** Syncs when the app starts and then every [EVERY_MILLIS] while it lives. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                runCatching { syncAll() }
                delay(EVERY_MILLIS)
            }
        }
    }

    private companion object {
        const val TAG = "Accounts"
        const val EVERY_MILLIS = 15L * 60 * 1000
    }
}
