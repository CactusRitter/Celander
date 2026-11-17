package com.celander.app.task.google

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Outcome of the last Google Tasks sync of an account, or [Syncing] while one runs. */
sealed interface GoogleTasksStatus {
    data object Ok : GoogleTasksStatus
    data object Syncing : GoogleTasksStatus
    data object Offline : GoogleTasksStatus

    /** The grant expired or was revoked; the user has to sign in again (UI only, never a worker). */
    data object NeedsReauth : GoogleTasksStatus

    /** No OAuth client for this app's package and signature. */
    data object SetupError : GoogleTasksStatus

    /** The Tasks API is disabled in the Google Cloud project. */
    data object ApiDisabled : GoogleTasksStatus

    /** Any other failure; [code] is the HTTP status code. */
    data class Failed(val code: Int) : GoogleTasksStatus

    /** Anything but a finished or running sync; shown as a problem. */
    val isProblem: Boolean get() = this != Ok && this != Syncing
}

/**
 * A connected Google Tasks account and its sync state. [rescuedCount] tasks with unsent changes from
 * lists deleted at Google were moved to [rescuedTo].
 */
data class GoogleTasksState(
    val email: String,
    val status: GoogleTasksStatus = GoogleTasksStatus.Ok,
    val lastSyncAt: Long? = null,
    val rescuedCount: Int = 0,
    val rescuedTo: String? = null,
    val rejectedCount: Int = 0,
)

/**
 * The connected Google Tasks accounts, each with its own sync state, persisted as JSON in the
 * SharedPreferences file `google_tasks`, which is excluded from backups: a restored device starts
 * disconnected and imports afresh. The single account of earlier versions is taken over on first read.
 */
@Singleton
class GoogleTasksAccounts internal constructor(private val prefs: SharedPreferences) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    private val stored = MutableStateFlow(read())
    private val syncing = MutableStateFlow(emptySet<String>())
    private val combined = MutableStateFlow(stored.value)

    /**
     * The accounts in the order they were connected; [GoogleTasksStatus.Syncing] replaces the stored
     * status of an account while its sync runs.
     */
    val state: StateFlow<List<GoogleTasksState>> = combined.asStateFlow()

    /** Whether at least one account is connected. */
    val connected: Flow<Boolean> = state.map { it.isNotEmpty() }.distinctUntilChanged()

    val emails: List<String> get() = stored.value.map { it.email }

    operator fun get(email: String): GoogleTasksState? = state.value.firstOrNull { it.email == email }

    /**
     * Adds [email], or marks it as fine again after a new sign-in (its sync state stays). Written
     * synchronously.
     */
    fun connect(email: String) = write(commit = true) { list ->
        if (list.any { it.email == email }) list.map { if (it.email == email) it.copy(status = GoogleTasksStatus.Ok) else it } else list + GoogleTasksState(email)
    }

    /** Forgets [email]. Written synchronously. */
    fun remove(email: String) = write(commit = true) { list -> list.filter { it.email != email } }

    /** Forgets every account. Written synchronously. */
    fun clear() = write(commit = true) { emptyList() }

    @Synchronized
    fun setSyncing(email: String, running: Boolean) {
        syncing.update { if (running) it + email else it - email }
        publish()
    }

    /** Records a failed or interrupted sync of [email]. */
    fun setStatus(email: String, status: GoogleTasksStatus) = change(email) { it.copy(status = status) }

    fun recordSuccess(email: String, at: Long, rejectedCount: Int) =
        change(email) { it.copy(status = GoogleTasksStatus.Ok, lastSyncAt = at, rejectedCount = rejectedCount) }

    /** Adds rescued tasks; a rescue into another calendar replaces the earlier note. */
    fun recordRescue(email: String, count: Int, target: String) = change(email) {
        val total = if (it.rescuedTo == target) it.rescuedCount + count else count
        it.copy(rescuedCount = total, rescuedTo = target)
    }

    /** The user saw the rescue note of [email]. */
    fun clearRescued(email: String) = change(email) { it.copy(rescuedCount = 0, rescuedTo = null) }

    private fun change(email: String, transform: (GoogleTasksState) -> GoogleTasksState) = write { list ->
        list.map { if (it.email == email) transform(it) else it }
    }

    @Synchronized
    private fun write(commit: Boolean = false, transform: (List<GoogleTasksState>) -> List<GoogleTasksState>) {
        stored.update(transform)
        persist(stored.value, commit)
        publish()
    }

    private fun persist(accounts: List<GoogleTasksState>, commit: Boolean) {
        prefs.edit(commit = commit) {
            clear()
            if (accounts.isNotEmpty()) putString(KEY_ACCOUNTS, json.encodeToString(ListSerializer(StoredAccount.serializer()), accounts.map { it.toStored() }))
        }
    }

    private fun publish() {
        val running = syncing.value
        combined.value = stored.value.map { if (it.email in running) it.copy(status = GoogleTasksStatus.Syncing) else it }
    }

    private fun read(): List<GoogleTasksState> {
        prefs.getString(KEY_ACCOUNTS, null)?.let { text ->
            return try {
                json.decodeFromString(ListSerializer(StoredAccount.serializer()), text).map { it.toState() }
            } catch (_: SerializationException) {
                emptyList()
            } catch (_: IllegalArgumentException) {
                emptyList()
            }
        }
        // Versions with a single account kept it in separate keys; it becomes the first entry.
        val email = prefs.getString(LEGACY_EMAIL, null) ?: return emptyList()
        val migrated = listOf(
            StoredAccount(
                email = email,
                state = prefs.getString(LEGACY_STATE, null),
                errorCode = prefs.getInt(LEGACY_ERROR, 0),
                lastSyncAt = prefs.getLong(LEGACY_LAST_SYNC, 0).takeIf { it > 0 },
                rescuedCount = prefs.getInt(LEGACY_RESCUED_COUNT, 0),
                rescuedTo = prefs.getString(LEGACY_RESCUED_TO, null),
                rejectedCount = prefs.getInt(LEGACY_REJECTED, 0),
            ).toState(),
        )
        persist(migrated, commit = true)
        return migrated
    }

    /** The persisted form; the status as a key plus the error code of [GoogleTasksStatus.Failed]. */
    @Serializable
    private data class StoredAccount(
        val email: String,
        val state: String? = null,
        val errorCode: Int = 0,
        val lastSyncAt: Long? = null,
        val rescuedCount: Int = 0,
        val rescuedTo: String? = null,
        val rejectedCount: Int = 0,
    ) {
        fun toState() = GoogleTasksState(email, statusOf(state, errorCode), lastSyncAt, rescuedCount, rescuedTo, rejectedCount)
    }

    private fun GoogleTasksState.toStored() = StoredAccount(
        email = email,
        state = status.key(),
        errorCode = (status as? GoogleTasksStatus.Failed)?.code ?: 0,
        lastSyncAt = lastSyncAt,
        rescuedCount = rescuedCount,
        rescuedTo = rescuedTo,
        rejectedCount = rejectedCount,
    )

    companion object {
        /** File name; `google_tasks.xml` is excluded in the backup rules. */
        const val FILE = "google_tasks"
        private const val KEY_ACCOUNTS = "accounts"
        private const val LEGACY_EMAIL = "email"
        private const val LEGACY_STATE = "state"
        private const val LEGACY_ERROR = "lastErrorCode"
        private const val LEGACY_LAST_SYNC = "lastSyncAt"
        private const val LEGACY_RESCUED_COUNT = "rescuedCount"
        private const val LEGACY_RESCUED_TO = "rescuedTo"
        private const val LEGACY_REJECTED = "rejectedCount"
        private val json = Json { ignoreUnknownKeys = true }

        private fun GoogleTasksStatus.key(): String = when (this) {
            GoogleTasksStatus.Ok, GoogleTasksStatus.Syncing -> "OK"
            GoogleTasksStatus.Offline -> "OFFLINE"
            GoogleTasksStatus.NeedsReauth -> "NEEDS_REAUTH"
            GoogleTasksStatus.SetupError -> "SETUP_ERROR"
            GoogleTasksStatus.ApiDisabled -> "API_DISABLED"
            is GoogleTasksStatus.Failed -> "FAILED"
        }

        private fun statusOf(key: String?, code: Int): GoogleTasksStatus = when (key) {
            "OFFLINE" -> GoogleTasksStatus.Offline
            "NEEDS_REAUTH" -> GoogleTasksStatus.NeedsReauth
            "SETUP_ERROR" -> GoogleTasksStatus.SetupError
            "API_DISABLED" -> GoogleTasksStatus.ApiDisabled
            "FAILED" -> GoogleTasksStatus.Failed(code)
            else -> GoogleTasksStatus.Ok
        }
    }
}
