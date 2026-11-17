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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
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
    private val scheduler: AccountSyncScheduler,
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

    /** Syncs every account (one after the other). True when a passing problem (offline, server error) means it should be tried again. */
    suspend fun syncAll(): Boolean {
        var retry = false
        dao.accounts().forEach { if (syncAccount(it.id) is SyncOutcome.Failed) retry = true }
        return retry
    }

    /** Removes an account with its calendars and events on this device and its tokens. The server is not touched. */
    suspend fun remove(id: Long) = lock.withLock {
        sync.remove(id)
        tokens.clear(AccountTokens.key(id))
    }

    private var lastForegroundSync = 0L

    /** The app came to the front: syncs, but not more often than every [FOREGROUND_MIN_MILLIS]. */
    fun onForeground(scope: CoroutineScope) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastForegroundSync != 0L && now - lastForegroundSync < FOREGROUND_MIN_MILLIS) return
        lastForegroundSync = now
        scope.launch { runCatching { syncAll() } }
    }

    /**
     * Keeps the accounts in sync without a server of our own: a run every 15 minutes in the background while an account
     * is connected, one a few seconds after any change made here (the database marks them, see AccountTriggers), and
     * one when the app starts.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            dao.observeAccounts().map { it.isNotEmpty() }.distinctUntilChanged().collect { connected ->
                if (connected) scheduler.schedulePeriodic() else scheduler.cancelAll()
            }
        }
        scope.launch {
            dao.observePendingCount().distinctUntilChanged().filter { it > 0 }.collect {
                scheduler.requestSoon(AccountSyncScheduler.LOCAL_CHANGE_DELAY)
            }
        }
        onForeground(scope)
    }

    private companion object {
        const val TAG = "Accounts"
        const val FOREGROUND_MIN_MILLIS = 30_000L
    }
}
