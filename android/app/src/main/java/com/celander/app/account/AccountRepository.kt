package com.celander.app.account

import com.ce.kit.log.AppLog
import android.content.Intent
import com.celander.app.account.auth.GoogleAuth
import com.celander.app.account.auth.MicrosoftAuth
import com.celander.app.account.ics.IcsUrlProvider
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.celander.app.account.auth.SignInOutcome
import com.celander.app.account.auth.TokenStore
import com.celander.app.core.database.AccountDao
import com.celander.app.core.database.AccountEntity
import com.celander.app.core.model.AccountStatus
import com.celander.app.core.model.AccountType
import com.celander.app.task.google.GoogleTasksConnection
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/** How switching on "Birthdays from contacts" ended. */
enum class BirthdaysResult {
    Enabled,
    Cancelled,

    /** The consent did not work (another account answered, a problem with Google). */
    Failed,

    /** Consent given, but the People API refused (permission missing, or the API is not enabled for the app); the switch went off again. */
    Refused,
}

/** What "Delete all data" needs from the accounts: to forget every one of them on this device. */
fun interface AccountsReset {
    suspend fun removeAll()
}

/** The accounts whose calendars Celander syncs: signing in, syncing, removing. Screens read the database through [accounts]. */
@Singleton
class AccountRepository @Inject constructor(
    private val dao: AccountDao,
    private val sync: AccountSync,
    private val tokens: TokenStore,
    private val google: GoogleAuth,
    private val microsoft: MicrosoftAuth,
    private val ics: IcsUrlProvider,
    private val scheduler: AccountSyncScheduler,
    private val googleTasks: GoogleTasksConnection,
    private val push: Lazy<com.celander.app.push.PushManager>,
    private val birthdays: BirthdayPrefs,
) : AccountsReset {
    val accounts: Flow<List<AccountEntity>> = dao.observeAccounts()

    /** Only one sync runs at a time, whoever asks. */
    private val lock = Mutex()

    val googleConfigured: Boolean get() = google.isConfigured

    /** Opens Google's sign-in in the browser; hand what comes back to [addGoogle]. [loginHint] names an account to offer first. */
    fun googleSignInIntent(loginHint: String? = null): Intent? = google.signInIntent(loginHint)

    val microsoftConfigured: Boolean get() = microsoft.isConfigured

    /** Opens Microsoft's sign-in in the browser; hand what comes back to [addMicrosoft]. */
    fun microsoftSignInIntent(loginHint: String? = null): Intent? = microsoft.signInIntent(loginHint)

    suspend fun addGoogle(data: Intent?): AddAccountResult = added(AccountType.GOOGLE, google.handleResult(data))

    /** The accounts with "Birthdays from contacts" on, and the code with which the People API refused one that went off by itself. */
    val birthdaysOn = birthdays.enabledAccounts
    val birthdayFailures = birthdays.failureCodes

    /**
     * Google's consent page for the extra permission (read the contacts) of [account]; hand what comes back to [enableBirthdays].
     * The account stays signed in as it is: nothing changes until the answer is good.
     */
    fun birthdaysConsentIntent(account: AccountEntity): Intent? = google.signInIntent(account.identifier, GoogleAuth.CONTACTS_SCOPE)

    /**
     * Finishes the consent: the new tokens (which carry the contacts permission) replace the old ones only if the same account
     * answered; then the birthdays are switched on and read straight away. A cancelled or failed consent changes nothing.
     */
    suspend fun enableBirthdays(accountId: Long, data: Intent?): BirthdaysResult {
        val account = dao.account(accountId) ?: return BirthdaysResult.Failed
        return when (val outcome = google.handleResult(data)) {
            SignInOutcome.Cancelled -> BirthdaysResult.Cancelled
            SignInOutcome.NotConfigured, is SignInOutcome.Failed -> BirthdaysResult.Failed
            is SignInOutcome.Success -> {
                if (!outcome.result.email.equals(account.identifier, ignoreCase = true)) return BirthdaysResult.Failed
                val old = tokens.load(AccountTokens.key(accountId))
                // Google may leave out the refresh token when it was given before: the old one still works.
                tokens.save(AccountTokens.key(accountId), outcome.result.tokens.let { it.copy(refreshToken = it.refreshToken ?: old?.refreshToken) })
                birthdays.enable(accountId)
                syncAccount(accountId)
                if (birthdays.enabled(accountId)) BirthdaysResult.Enabled else BirthdaysResult.Refused
            }
        }
    }

    /** Switches the birthdays off: their calendar goes with its events at the next sync, which runs now. */
    suspend fun disableBirthdays(accountId: Long) {
        birthdays.disable(accountId)
        syncAccount(accountId)
    }

    /**
     * Subscribes to a calendar published as an .ics link (read only). The link is read once first, so a wrong one is refused;
     * [name] is what the calendar is called here (the file's own name, or the host, when empty).
     */
    suspend fun addSubscription(url: String, name: String?): AddAccountResult {
        val address = IcsUrlProvider.normalize(url)
        val host = address.toHttpUrlOrNull()?.host ?: return AddAccountResult.Failed("url")
        val feed = try { ics.fetch(address) } catch (e: java.io.IOException) { return AddAccountResult.Failed(e.message ?: "fetch") }
        val title = name?.trim()?.takeIf { it.isNotEmpty() } ?: feed.name?.takeIf { it.isNotBlank() } ?: host
        val existing = dao.account(AccountType.ICS_URL.name, address)
        val id = existing?.id ?: dao.insertAccount(
            AccountEntity(type = AccountType.ICS_URL.name, identifier = address, displayName = title, createdAt = System.currentTimeMillis()),
        )
        existing?.let { dao.updateAccount(it.copy(status = AccountStatus.OK.name, lastError = null)) }
        return AddAccountResult.Added(id, title, syncAccount(id))
    }

    suspend fun addMicrosoft(data: Intent?): AddAccountResult = added(AccountType.MICROSOFT, microsoft.handleResult(data))

    /** Stores the account a sign-in of [type] gave (or refreshes the one that was there) and syncs it. */
    private suspend fun added(type: AccountType, signedIn: SignInOutcome): AddAccountResult = when (val outcome = signedIn.also { AppLog.i("accounts", "sign-in result ($type): ${it.javaClass.simpleName}" + ((it as? SignInOutcome.Failed)?.let { f -> " (${f.reason})" } ?: "")) }) {
        SignInOutcome.Cancelled -> AddAccountResult.Cancelled
        SignInOutcome.NotConfigured -> AddAccountResult.NotConfigured
        is SignInOutcome.Failed -> AddAccountResult.Failed(outcome.reason)
        is SignInOutcome.Success -> {
            val result = outcome.result
            val existing = dao.account(type.name, result.email)
            val id = existing?.id ?: dao.insertAccount(
                AccountEntity(
                    type = type.name, identifier = result.email, displayName = result.name ?: result.email,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            tokens.save(AccountTokens.key(id), result.tokens)
            existing?.let { dao.updateAccount(it.copy(status = AccountStatus.OK.name, lastError = null)) }
            AddAccountResult.Added(id, result.email, syncAccount(id))
        }
    }

    suspend fun syncAccount(id: Long): SyncOutcome = lock.withLock { sync.sync(id).also { AppLog.i("accounts", "sync of account $id: $it") } }

    /** Syncs every account (one after the other). True when a passing problem (offline, server error) means it should be tried again. */
    suspend fun syncAll(): Boolean {
        var retry = false
        dao.accounts().forEach { if (syncAccount(it.id) is SyncOutcome.Failed) retry = true }
        return retry
    }

    /** Removes an account with its calendars, events and Google Tasks lists on this device and its tokens. Push channels and the relay are told first (PushManager.forget). */
    suspend fun remove(id: Long) = lock.withLock {
        push.get().forget(listOf(id))
        dao.account(id)?.takeIf { it.type == AccountType.GOOGLE.name }?.let { googleTasks.disconnect(it.identifier) }
        sync.remove(id)
        tokens.clear(AccountTokens.key(id))
        birthdays.forget(id)
    }

    /**
     * "Delete all data": every account goes with its calendars, events, links and tokens. Google Tasks is disconnected by the
     * reset itself (before this), so it is not touched here. Nothing is sent to any server.
     */
    override suspend fun removeAll() = lock.withLock {
        push.get().forget(dao.accounts().map { it.id })
        dao.accounts().forEach {
            sync.remove(it.id)
            tokens.clear(AccountTokens.key(it.id))
            birthdays.forget(it.id)
        }
    }

    private var lastForegroundSync = 0L
    private var visibleJob: Job? = null

    /**
     * The app came on screen or went off it. While it is on screen the accounts are asked for changes every
     * [VISIBLE_POLL_MILLIS] (cheap: Google only answers with what changed since the sync token) and once when it comes
     * up, but not more often than every [FOREGROUND_MIN_MILLIS]. Off screen the 15-minute background run takes over.
     */
    fun onVisibilityChanged(scope: CoroutineScope, visible: Boolean) {
        if (!visible) {
            visibleJob?.cancel()
            visibleJob = null
            return
        }
        if (visibleJob?.isActive == true) return
        val now = android.os.SystemClock.elapsedRealtime()
        val first = lastForegroundSync == 0L || now - lastForegroundSync >= FOREGROUND_MIN_MILLIS
        if (first) lastForegroundSync = now
        visibleJob = scope.launch {
            if (first) runCatching { syncAll() }
            while (true) {
                delay(VISIBLE_POLL_MILLIS)
                runCatching { syncAll() }
            }
        }
    }

    /**
     * Keeps the accounts in sync without a server of our own: a run every 15 minutes in the background while an account
     * is connected, one a few seconds after any change made here (the database marks them, see AccountTriggers), and
     * and one while the app is on screen every minute (see [onVisibilityChanged]).
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
    }

    private companion object {
        const val TAG = "Accounts"
        const val FOREGROUND_MIN_MILLIS = 30_000L
        const val VISIBLE_POLL_MILLIS = 60_000L
    }
}
