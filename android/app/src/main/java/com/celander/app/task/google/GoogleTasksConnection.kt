package com.celander.app.task.google

import androidx.room.withTransaction
import com.celander.app.core.database.CelanderDatabase
import com.celander.app.core.di.ApplicationScope
import com.celander.app.core.model.CalendarSource
import com.celander.app.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * New tasks now go to Google's default list (calendar [calendarId], named [listName]). The previous
 * choices are kept so [GoogleTasksConnection.undoDefaultChange] can put them back.
 */
data class DefaultListChange(
    val calendarId: Long,
    val listName: String,
    val previousDefault: Long?,
    val previousLastUsed: Long?,
)

/** Result of [GoogleTasksConnection.connected]. */
data class ConnectResult(val outcome: SyncOutcome, val defaultChange: DefaultListChange?)

/**
 * Connecting, disconnecting and cleaning up the Google Tasks mirror of each account. Nothing here
 * ever deletes data at Google: disconnecting only removes the local copies.
 */
@Singleton
class GoogleTasksConnection @Inject constructor(
    private val db: CelanderDatabase,
    private val accounts: GoogleTasksAccounts,
    private val sync: GoogleTasksSync,
    private val scheduler: TaskSyncScheduler,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val dao = db.googleTasksDao()
    private val calendarDao = db.calendarDao()

    /**
     * The user granted access for [email]: adds the account next to the others (or marks it fine
     * again after a new sign-in), schedules the hourly sync and runs the account's first sync in the
     * application scope (it continues if the screen goes away). With [switchDefault], Google's default
     * list of [email] becomes the task default when that was unset or a local calendar.
     */
    suspend fun connected(email: String, switchDefault: Boolean = true): ConnectResult = scope.async {
        accounts.connect(email)
        scheduler.schedulePeriodic()
        val outcome = sync.run(email)
        follow(outcome)
        ConnectResult(outcome, if (switchDefault && outcome is SyncOutcome.Done) makeDefault(email) else null)
    }.await()

    /**
     * Makes Google's default list of [email] the task default, unless a Google list is the default
     * already. Null if nothing changed.
     */
    suspend fun makeDefault(email: String): DefaultListChange? {
        val s = settings.current()
        val current = s.defaultTaskCalendarId?.let { calendarDao.getById(it) }
        if (current != null && current.source != CalendarSource.LOCAL.name) return null
        val list = dao.lists().firstOrNull { it.account == email && it.isDefault } ?: return null
        val calendar = calendarDao.getById(list.calendarId) ?: return null
        settings.update { it.copy(defaultTaskCalendarId = calendar.id, lastUsedTaskCalendarId = null) }
        return DefaultListChange(calendar.id, calendar.name, s.defaultTaskCalendarId, s.lastUsedTaskCalendarId)
    }

    /** Undo of the snackbar shown for [change]. */
    suspend fun undoDefaultChange(change: DefaultListChange) {
        settings.update { it.copy(defaultTaskCalendarId = change.previousDefault, lastUsedTaskCalendarId = change.previousLastUsed) }
    }

    /** "Sync now" for every account: runs in the application scope; offline it leaves a run waiting for the network. */
    fun syncNow() {
        scope.launch { follow(sync.run()) }
    }

    private fun follow(outcome: SyncOutcome) {
        when (outcome) {
            is SyncOutcome.Retry -> scheduler.requestSoon(0)
            is SyncOutcome.Done -> outcome.nextRunIn?.let(scheduler::requestFollowUp)
            else -> Unit
        }
    }

    /** Local changes of [email] that have not reached Google yet (shown before disconnecting). */
    suspend fun pendingCount(email: String): Int = dao.observePendingCounts().first().firstOrNull { it.account == email }?.count ?: 0

    /**
     * Disconnects [email]: sends its pending changes once if possible, then removes its lists with
     * their tasks from this device. The other accounts keep syncing.
     */
    suspend fun disconnect(email: String) = scope.async {
        if (accounts[email] != null && pendingCount(email) > 0) sync.run(email)
        disconnectLocally(email)
    }.await()

    /** Forgets [email] and removes its mirror without contacting Google. */
    suspend fun disconnectLocally(email: String) {
        sync.exclusive {
            accounts.remove(email)
            removeMirror(email)
        }
        if (accounts.emails.isEmpty()) scheduler.cancelAll()
    }

    /** Forgets every account and removes the whole mirror without contacting Google (reset). */
    suspend fun disconnectLocally() {
        val wasConnected = accounts.emails.isNotEmpty()
        sync.exclusive {
            accounts.clear()
            removeMirror(null)
        }
        if (wasConnected) scheduler.cancelAll()
    }

    /**
     * After a restore from backup the accounts are gone (their prefs are not backed up) but the
     * mirrored lists came back. Lists of accounts that are not connected are removed, so stale data
     * is never uploaded; reconnecting imports afresh.
     */
    suspend fun purgeAfterRestore() {
        val connected = accounts.emails.toSet()
        if (connected.isEmpty()) {
            if (calendarDao.getBySource(CalendarSource.GOOGLE_TASKS.name).isNotEmpty() || dao.lists().isNotEmpty()) sync.exclusive { removeMirror(null) }
            return
        }
        (dao.lists().map { it.account }.toSet() - connected).forEach { email -> sync.exclusive { removeMirror(email) } }
    }

    /**
     * Removes the lists of [email] (all Google lists when null). Links first, so removing the lists'
     * tasks never reads as deletions to upload – neither for [email] nor for another account whose
     * task was moved into one of these lists. A task of the account moved into another account's
     * list loses its link and is uploaded there as a new task.
     */
    private suspend fun removeMirror(email: String?) {
        val listIds = if (email == null) {
            calendarDao.getBySource(CalendarSource.GOOGLE_TASKS.name).map { it.id }.toSet()
        } else {
            dao.lists().filter { it.account == email }.map { it.calendarId }.toSet()
        }
        db.withTransaction {
            if (email == null) {
                dao.deleteAllLinks()
                calendarDao.deleteBySource(CalendarSource.GOOGLE_TASKS.name)
            } else {
                dao.deleteLinksForAccount(email)
                // A task moved in from another account still carries that account's link until the
                // move is sent; removing it with the list must not read as a delete for that account.
                listIds.forEach { dao.deleteLinksForCalendar(it) }
                listIds.forEach { calendarDao.delete(it) }
            }
        }
        settings.update { s ->
            s.copy(
                defaultTaskCalendarId = s.defaultTaskCalendarId?.takeIf { it !in listIds },
                lastUsedTaskCalendarId = s.lastUsedTaskCalendarId?.takeIf { it !in listIds },
            )
        }
    }
}
