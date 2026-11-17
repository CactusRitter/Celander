package com.celander.app.task.google

import androidx.room.withTransaction
import com.celander.app.core.database.CalendarEntity
import com.celander.app.core.database.CelanderDatabase
import com.celander.app.core.database.GoogleTaskLinkEntity
import com.celander.app.core.database.GoogleTaskListEntity
import com.celander.app.core.database.PendingUpsert
import com.celander.app.core.database.TaskEntity
import com.celander.app.core.googletasks.GTask
import com.celander.app.core.googletasks.GTaskList
import com.celander.app.core.googletasks.GoogleTaskMapper
import com.celander.app.core.googletasks.TaskFields
import com.celander.app.core.googletasks.TaskMerge
import com.celander.app.core.model.CalendarSource
import com.celander.app.core.time.TimeSource
import com.celander.app.ui.CalendarColor
import com.celander.app.settings.SettingsRepository
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException

/** What a sync run achieved; the worker turns it into a WorkManager result. */
sealed interface SyncOutcome {
    /** Everything was exchanged; deletes still inside their undo window are due in [nextRunIn] ms. */
    data class Done(val nextRunIn: Long?) : SyncOutcome

    /** Offline, rate limited or a server error: try again with backoff. */
    data class Retry(val status: GoogleTasksStatus) : SyncOutcome

    /** Needs the user (sign-in, setup); trying again would not help. */
    data class Stopped(val status: GoogleTasksStatus) : SyncOutcome

    data object NotConnected : SyncOutcome

    companion object {
        /**
         * One outcome for the runs of several accounts: a retry wins (the next run is incremental, so
         * the accounts that were fine cost little), then done with the earliest follow-up, then stopped.
         */
        fun combine(outcomes: Collection<SyncOutcome>): SyncOutcome {
            outcomes.firstOrNull { it is Retry }?.let { return it }
            val done = outcomes.filterIsInstance<Done>()
            if (done.isNotEmpty()) return Done(done.mapNotNull { it.nextRunIn }.minOrNull())
            return outcomes.firstOrNull { it is Stopped } ?: NotConnected
        }
    }
}

/**
 * Two-way sync of Google Tasks lists with their mirror in Room: lists, then a pull per list, then
 * uploads and deletes. Every connected account syncs on its own with its own token; a failing
 * account only records its status and the others go on. Runs of accounts are serialized by one
 * mutex. Network calls never run inside a transaction; every applied item is its own transaction,
 * so an interrupted run loses nothing.
 */
@Singleton
class GoogleTasksSync @Inject constructor(
    private val db: CelanderDatabase,
    private val accounts: GoogleTasksAccounts,
    private val authorizer: GoogleAuthorizer,
    private val remote: TasksRemote,
    private val settings: SettingsRepository,
    private val time: TimeSource,
) {
    private val dao = db.googleTasksDao()
    private val calendarDao = db.calendarDao()
    private val taskDao = db.taskDao()
    private val mutex = Mutex()

    /** Runs [block] while no sync runs, e.g. to remove the mirrored lists. */
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * Syncs every connected account in turn. A task moved into a list of another account reaches it
     * once its old account deleted it at Google; that account then runs once more in the same call.
     */
    suspend fun run(): SyncOutcome {
        val emails = accounts.emails
        if (emails.isEmpty()) return SyncOutcome.NotConnected
        val outcomes = linkedMapOf<String, SyncOutcome>()
        val handedOver = mutableSetOf<String>()
        for (email in emails) {
            val result = runAccount(email)
            outcomes[email] = result.outcome
            handedOver += result.handedOver
        }
        for (email in handedOver.filter { it in outcomes && it in accounts.emails }) outcomes[email] = runAccount(email).outcome
        return SyncOutcome.combine(outcomes.values)
    }

    /** Syncs only [email], e.g. right after connecting it. */
    suspend fun run(email: String): SyncOutcome = runAccount(email).outcome

    /** One account's run and the accounts that got tasks from it (see [run]). */
    private class AccountRun(val outcome: SyncOutcome, val handedOver: Set<String> = emptySet())

    private suspend fun runAccount(email: String): AccountRun = mutex.withLock {
        // Removed while waiting for the lock.
        if (accounts[email] == null) return AccountRun(SyncOutcome.NotConnected)
        accounts.setSyncing(email, true)
        val handedOver = mutableSetOf<String>()
        val outcome = try {
            val token = when (val auth = authorizer.backgroundToken(email)) {
                is AuthResult.Granted -> auth.token
                is AuthResult.Failed -> return AccountRun(stop(email, auth.status, retry = auth.status.isTransient()))
            }
            val session = Session(email, token, handedOver)
            val lists = syncLists(session)
            val deferred = lists.flatMap { pullList(session, it) }
            // Deletions are applied once every list was read: a task moved between lists at Google
            // must not be taken for deleted in the list it left.
            deferred.forEach { (listRemoteId, remoteId) -> db.withTransaction { applyDeletion(listRemoteId, remoteId) } }
            var nextRunAt: Long? = null
            // Deletes Google refused in this run; they are tried again in the next one.
            val refusedDeletes = mutableSetOf<Long>()
            for (pass in 0 until PUSH_PASSES) {
                pushUpserts(session)
                nextRunAt = pushDeletes(session, refusedDeletes)
                if (!hasDuePushes(email, refusedDeletes)) break
            }
            if (dao.pendingUpsertsFor(email).isNotEmpty()) nextRunAt = minOf(nextRunAt ?: Long.MAX_VALUE, now() + TaskSyncScheduler.LOCAL_CHANGE_DELAY)
            accounts.recordSuccess(email, now(), dao.rejectedCountFor(email))
            SyncOutcome.Done(nextRunAt?.let { (it - now()).coerceAtLeast(0) })
        } catch (e: StopSync) {
            stop(email, e.status, e.retry)
        } catch (_: IOException) {
            stop(email, GoogleTasksStatus.Offline, retry = true)
        } catch (e: TasksApiException) {
            when {
                e.apiDisabled -> stop(email, GoogleTasksStatus.ApiDisabled, retry = false)
                e.insufficientScope || e.code == 401 -> stop(email, GoogleTasksStatus.NeedsReauth, retry = false)
                else -> stop(email, GoogleTasksStatus.Failed(e.code), retry = e.transient)
            }
        } catch (_: SerializationException) {
            stop(email, GoogleTasksStatus.Failed(0), retry = true)
        } finally {
            accounts.setSyncing(email, false)
        }
        AccountRun(outcome, handedOver)
    }

    private fun stop(email: String, status: GoogleTasksStatus, retry: Boolean): SyncOutcome {
        accounts.setStatus(email, status)
        return if (retry) SyncOutcome.Retry(status) else SyncOutcome.Stopped(status)
    }

    private fun GoogleTasksStatus.isTransient() = this is GoogleTasksStatus.Offline || this is GoogleTasksStatus.Failed

    /** Step 1: mirror the lists; a list deleted at Google takes its untouched tasks along. */
    private suspend fun syncLists(s: Session): List<GoogleTaskListEntity> {
        val remoteLists = mutableListOf<GTaskList>()
        var page: String? = null
        do {
            val result = s.api { remote.lists(it, page) }
            remoteLists += result.items
            page = result.nextPageToken
        } while (page != null)
        val defaultId = s.api { remote.defaultList(it) }.id
        val known = dao.lists().filter { it.account == s.email }.associateBy { it.remoteId }
        for (list in remoteLists) {
            db.withTransaction {
                val existing = known[list.id]
                if (existing == null) {
                    val used = calendarDao.getAll().map { it.colorKey }.toSet()
                    val color = CalendarColor.entries.firstOrNull { it.key !in used } ?: CalendarColor.entries[used.size % CalendarColor.entries.size]
                    val calendarId = calendarDao.insert(
                        CalendarEntity(
                            name = list.title,
                            colorKey = color.key,
                            sortOrder = calendarDao.maxSortOrder() + 1,
                            createdAt = now(),
                            source = CalendarSource.GOOGLE_TASKS.name,
                        ),
                    )
                    dao.upsertList(GoogleTaskListEntity(calendarId = calendarId, account = s.email, remoteId = list.id, isDefault = list.id == defaultId))
                } else {
                    calendarDao.getById(existing.calendarId)?.takeIf { it.name != list.title }?.let { calendarDao.update(it.copy(name = list.title)) }
                    if (existing.isDefault != (list.id == defaultId)) dao.upsertList(existing.copy(isDefault = list.id == defaultId))
                }
            }
        }
        val remoteIds = remoteLists.map { it.id }.toSet()
        known.values.filter { it.remoteId !in remoteIds }.forEach { rescue(it) }
        return dao.lists().filter { it.account == s.email }
    }

    /**
     * Moves tasks of a list deleted at Google that have unsent changes into a local calendar, then
     * removes the list. Its other tasks are gone at Google too and go with the calendar, unsent.
     */
    private suspend fun rescue(list: GoogleTaskListEntity) {
        val preferred = settings.current().defaultCalendarId
        val rescued = db.withTransaction {
            val calendar = calendarDao.getById(list.calendarId)
            val tasks = taskDao.getForCalendar(list.calendarId)
            val keep = tasks.filter { task ->
                val link = dao.link(task.id)
                !task.isSample && (link?.remoteId == null || task.fields() != link.base())
            }
            dao.deleteLinksForCalendar(list.calendarId)
            dao.deleteLinksForList(list.remoteId)
            val locals = calendarDao.getBySource(CalendarSource.LOCAL.name)
            val target = locals.firstOrNull { it.id == preferred } ?: locals.firstOrNull()
            if (target != null) {
                keep.forEach { dao.moveTask(it.id, target.id) }
                calendarDao.delete(list.calendarId)
                keep.size to target.name
            } else {
                // No local calendar left: the list itself becomes one.
                (tasks - keep.toSet()).forEach { taskDao.delete(it.id) }
                dao.deleteList(list.calendarId)
                calendar?.let { calendarDao.update(it.copy(source = CalendarSource.LOCAL.name)) }
                keep.size to calendar?.name.orEmpty()
            }
        }
        if (rescued.first > 0) accounts.recordRescue(list.account, rescued.first, rescued.second)
    }

    /**
     * Step 2: reads a list's changes (everything on a full pull) and applies them. Returns the
     * deletions (list, task id) to apply after all lists were read.
     */
    private suspend fun pullList(s: Session, list: GoogleTaskListEntity): List<Pair<String, String>> {
        val now = now()
        val full = list.watermark == null || now - list.lastFullSyncAt > FULL_PULL_INTERVAL
        val since = if (full) null else list.watermark?.minus(OVERLAP)
        val deletions = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        var watermark = list.watermark
        var page: String? = null
        do {
            val result = s.api { remote.tasks(it, list.remoteId, since, page) }
            for (item in result.items) {
                seen += item.id
                item.updated?.let(GoogleTaskMapper::parseTime)?.let { watermark = maxOf(watermark ?: it, it) }
                if (item.deleted) deletions += list.remoteId to item.id else db.withTransaction { applyItem(list, item) }
            }
            page = result.nextPageToken
        } while (page != null)
        if (full) {
            dao.linksForList(list.remoteId).mapNotNull { it.remoteId }.filter { it !in seen }.forEach { deletions += list.remoteId to it }
        }
        dao.lists().firstOrNull { it.calendarId == list.calendarId }?.let { current ->
            dao.upsertList(current.copy(watermark = watermark, lastFullSyncAt = if (full) now else current.lastFullSyncAt))
        }
        return deletions
    }

    private suspend fun applyItem(list: GoogleTaskListEntity, item: GTask) {
        val remoteFields = GoogleTaskMapper.fields(item)
        val remoteUpdated = item.updated?.let(GoogleTaskMapper::parseTime) ?: 0
        val link = dao.linkByRemoteId(item.id)
        if (link == null) {
            importItem(list, item, remoteFields, remoteUpdated)
            return
        }
        if (item.etag != null && item.etag == link.etag && link.remoteListId == list.remoteId) return
        val task = taskDao.getById(link.taskId)
        if (task == null) {
            // Deleted here and waiting for its undo window: the delete wins. The base stays, so a
            // restore within the window still merges this change on the next pull.
            if (link.remoteListId != list.remoteId) dao.upsertLink(link.copy(remoteListId = list.remoteId))
            return
        }
        val local = task.fields()
        var calendarId = task.calendarId
        if (link.remoteListId != list.remoteId) {
            val oldCalendarId = dao.listByRemoteId(link.account, link.remoteListId)?.calendarId
            val movedHere = oldCalendarId == null || task.calendarId == oldCalendarId
            if (movedHere) calendarId = list.calendarId
        }
        val merged = TaskMerge.merge(link.base(), local, remoteFields, task.updatedAt, remoteUpdated)
        if (merged != local || calendarId != task.calendarId) {
            dao.applyRemote(task.id, merged.title, merged.note, merged.dueDay?.toEpochDay(), merged.done, merged.completedAt, calendarId, now())
        }
        dao.upsertLink(link.copy(remoteListId = list.remoteId, etag = item.etag, remoteUpdated = remoteUpdated).withBase(remoteFields))
    }

    private suspend fun importItem(list: GoogleTaskListEntity, item: GTask, fields: TaskFields, remoteUpdated: Long) {
        // An upload whose answer got lost: the task is already here.
        val inFlight = dao.inFlightLinks(list.remoteId).firstOrNull { link ->
            val base = link.base()
            base.title.take(GoogleTaskMapper.MAX_TITLE) == fields.title && base.note.take(GoogleTaskMapper.MAX_NOTES) == fields.note &&
                base.dueDay == fields.dueDay && base.done == fields.done && (link.insertStartedAt ?: 0) - OVERLAP <= remoteUpdated
        }
        if (inFlight != null) {
            dao.upsertLink(inFlight.copy(remoteId = item.id, etag = item.etag, remoteUpdated = remoteUpdated, insertStartedAt = null).withBase(fields))
            return
        }
        val now = now()
        if (item.hidden && fields.done && (fields.completedAt ?: 0) < now - OLD_COMPLETED) return
        val taskId = dao.insertTask(
            TaskEntity(
                calendarId = list.calendarId,
                title = fields.title,
                note = fields.note,
                dueDay = fields.dueDay?.toEpochDay(),
                dueMinute = null,
                reminders = emptyList(),
                done = fields.done,
                completedAt = fields.completedAt,
                isSample = false,
                createdAt = now,
                updatedAt = now,
            ),
        )
        dao.upsertLink(newLink(taskId, list.account, list.remoteId).copy(remoteId = item.id, etag = item.etag, remoteUpdated = remoteUpdated).withBase(fields))
    }

    /** A task deleted at Google: removed here unless it has local changes, which win and are uploaded again. */
    private suspend fun applyDeletion(listRemoteId: String, remoteId: String) {
        val link = dao.linkByRemoteId(remoteId) ?: return
        // Moved to another list at Google meanwhile: the stale deletion does not count.
        if (link.remoteListId != listRemoteId) return
        val task = taskDao.getById(link.taskId)
        val listCalendarId = dao.listByRemoteId(link.account, listRemoteId)?.calendarId
        dao.deleteLink(link.taskId)
        if (task != null && task.calendarId == listCalendarId && task.fields() == link.base()) taskDao.delete(task.id)
    }

    /** Step 3: uploads new and changed tasks, oldest change first. */
    private suspend fun pushUpserts(s: Session) {
        for (pending in dao.pendingUpsertsFor(s.email)) {
            val task = pending.task
            val snapshot = task.fields()
            val link = pending.link
            try {
                when {
                    link == null || link.remoteId == null -> insert(s, pending, snapshot)
                    link.remoteListId != pending.listRemoteId -> move(s, task, snapshot, link, pending.listRemoteId)
                    else -> patch(s, task.id, snapshot, link)
                }
            } catch (e: TasksApiException) {
                when {
                    // Gone at Google: the next pass uploads the task again.
                    e.code == 404 || e.code == 410 -> dao.deleteLink(task.id)
                    // Google refuses this task (its content, access to it): parked until the next
                    // local edit, so it does not hold up the others. Anything else ends the run and
                    // leaves the task pending for the next one.
                    e.refusesThisTask -> db.withTransaction {
                        dao.link(task.id)?.let { dao.upsertLink(it.copy(rejectedUpdatedAt = task.updatedAt, lastError = e.message, insertStartedAt = null)) }
                    }
                    else -> throw e
                }
            }
        }
    }

    private suspend fun insert(s: Session, pending: PendingUpsert, snapshot: TaskFields) {
        val task = pending.task
        val target = pending.listRemoteId
        // The marker comes first: if the answer to the POST is lost, the next pull adopts the task.
        val stillThere = db.withTransaction {
            if (taskDao.getById(task.id) == null) return@withTransaction false
            val link = dao.link(task.id) ?: newLink(task.id, pending.listAccount, target)
            dao.upsertLink(link.copy(remoteId = null, remoteListId = target, etag = null, insertStartedAt = now(), rejectedUpdatedAt = null, lastError = null).withBase(snapshot))
            true
        }
        if (!stillThere) return
        val created = s.api { remote.insert(it, target, GoogleTaskMapper.insertBody(snapshot)) }
        confirm(task.id, pending.listAccount, snapshot, created, target)
    }

    private suspend fun move(s: Session, task: TaskEntity, snapshot: TaskFields, link: GoogleTaskLinkEntity, target: String) {
        val remoteId = link.remoteId ?: return
        val moved = try {
            s.api { remote.move(it, link.remoteListId, remoteId, target) }
        } catch (e: TasksApiException) {
            if (e.code != 400 && e.code != 404) throw e
            // Fallback: a new copy in the target list, then the old one goes (a repetition set in Google is lost).
            val created = s.api { remote.insert(it, target, GoogleTaskMapper.insertBody(snapshot)) }
            confirm(task.id, link.account, snapshot, created, target)
            s.api { remote.delete(it, link.remoteListId, remoteId) }
            return
        }
        val movedLink = db.withTransaction {
            val current = dao.link(task.id) ?: link
            current.copy(remoteId = moved.id, remoteListId = target, etag = moved.etag, remoteUpdated = moved.updated?.let(GoogleTaskMapper::parseTime) ?: 0)
                .withBase(GoogleTaskMapper.fields(moved))
                .also { dao.upsertLink(it) }
        }
        if (movedLink.base() != snapshot) patch(s, task.id, snapshot, movedLink)
    }

    private suspend fun patch(s: Session, taskId: Long, snapshot: TaskFields, link: GoogleTaskLinkEntity) {
        val remoteId = link.remoteId ?: return
        val body = GoogleTaskMapper.patchBody(link.base(), snapshot)
        if (body.isEmpty()) return
        val updated = s.api { remote.patch(it, link.remoteListId, remoteId, body) }
        confirm(taskId, link.account, snapshot, updated, link.remoteListId)
    }

    /**
     * Stores Google's answer as the new base. If the task did not change meanwhile, Google's
     * normalised fields are written back, so they cannot cause an endless loop of uploads.
     */
    private suspend fun confirm(taskId: Long, accountName: String, snapshot: TaskFields, answer: GTask, listRemoteId: String) {
        val fields = GoogleTaskMapper.fields(answer)
        db.withTransaction {
            val link = dao.link(taskId) ?: newLink(taskId, accountName, listRemoteId)
            dao.upsertLink(
                link.copy(
                    remoteId = answer.id,
                    remoteListId = listRemoteId,
                    etag = answer.etag,
                    remoteUpdated = answer.updated?.let(GoogleTaskMapper::parseTime) ?: 0,
                    insertStartedAt = null,
                    rejectedUpdatedAt = null,
                    lastError = null,
                ).withBase(fields),
            )
            val current = taskDao.getById(taskId)
            if (current != null && current.fields() == snapshot && fields != snapshot) {
                dao.applyRemote(taskId, fields.title, fields.note, fields.dueDay?.toEpochDay(), fields.done, fields.completedAt, current.calendarId, current.updatedAt)
            }
        }
    }

    /**
     * Step 4: deletes at Google; returns when the next delete leaves its undo window. A delete Google
     * refuses is added to [refused] and skipped for the rest of the run; its link stays, so the task
     * is not imported again and the delete is tried in the next run.
     */
    private suspend fun pushDeletes(s: Session, refused: MutableSet<Long>): Long? {
        dao.dropOrphanedInFlightFor(s.email)
        var nextRunAt: Long? = null
        for (link in dao.pendingDeletesFor(s.email)) {
            val remoteId = link.remoteId ?: continue
            if (link.taskId in refused) continue
            val deletedAt = link.deletedAt
            if (deletedAt != null && now() - deletedAt < DELETE_GRACE) {
                nextRunAt = minOf(nextRunAt ?: Long.MAX_VALUE, deletedAt + DELETE_GRACE)
                continue
            }
            try {
                s.api { remote.delete(it, link.remoteListId, remoteId) }
            } catch (e: TasksApiException) {
                if (!e.refusesThisTask) throw e
                refused += link.taskId
                db.withTransaction {
                    dao.link(link.taskId)?.takeIf { it.remoteId == remoteId }?.let { dao.upsertLink(it.copy(lastError = e.message)) }
                }
                continue
            }
            val movedTo = db.withTransaction {
                if (dao.link(link.taskId)?.remoteId != remoteId) return@withTransaction null
                dao.deleteLink(link.taskId)
                // Still a task, now in another account's list: that account uploads it next.
                taskDao.getById(link.taskId)?.let { task -> dao.lists().firstOrNull { it.calendarId == task.calendarId }?.account }
            }
            if (movedTo != null && movedTo != s.email) s.handedOver += movedTo
        }
        return nextRunAt
    }

    private suspend fun hasDuePushes(email: String, refusedDeletes: Set<Long>): Boolean = dao.pendingUpsertsFor(email).isNotEmpty() ||
        dao.pendingDeletesFor(email).any { link -> link.taskId !in refusedDeletes && (link.deletedAt?.let { now() - it >= DELETE_GRACE } ?: true) }

    private fun now(): Long = time.now().toEpochMilli()

    /**
     * One account's run: its token, which is cleared and replaced once when Google rejects it, and
     * the accounts that received tasks moved out of this one.
     */
    private inner class Session(val email: String, private var token: String, val handedOver: MutableSet<String>) {
        suspend fun <T> api(call: suspend (String) -> T): T = try {
            call(token)
        } catch (e: TasksApiException) {
            if (e.code != 401) throw e
            authorizer.clearToken(token)
            token = when (val auth = authorizer.backgroundToken(email)) {
                is AuthResult.Granted -> auth.token
                is AuthResult.Failed -> throw StopSync(auth.status, auth.status.isTransient())
            }
            try {
                call(token)
            } catch (again: TasksApiException) {
                if (again.code == 401) throw StopSync(GoogleTasksStatus.NeedsReauth, retry = false) else throw again
            }
        }
    }

    private class StopSync(val status: GoogleTasksStatus, val retry: Boolean) : Exception()

    companion object {
        /** A full pull also notices deletions that incremental pulls cannot report. */
        const val FULL_PULL_INTERVAL = 24 * 60 * 60 * 1000L

        /** Overlap for incremental pulls and lost uploads, against clock skew between Google's servers. */
        const val OVERLAP = 5 * 60 * 1000L

        /** Undo window of a delete; longer than the snackbar even with TalkBack's longer timeout. */
        const val DELETE_GRACE = 150_000L

        /** Completed tasks that Google already hides are not imported after this. */
        const val OLD_COMPLETED = 30 * 24 * 60 * 60 * 1000L
        private const val PUSH_PASSES = 3
    }
}

/** The synced fields of a task; the completion time only counts while done. */
internal fun TaskEntity.fields() = TaskFields(title, note, dueDay?.let(LocalDate::ofEpochDay), done, if (done) completedAt else null)

/** The state Celander and Google last agreed on. */
internal fun GoogleTaskLinkEntity.base() =
    TaskFields(baseTitle, baseNote, baseDueDay?.let(LocalDate::ofEpochDay), baseDone, if (baseDone) baseCompletedAt else null)

internal fun GoogleTaskLinkEntity.withBase(fields: TaskFields) = copy(
    baseTitle = fields.title,
    baseNote = fields.note,
    baseDueDay = fields.dueDay?.toEpochDay(),
    baseDone = fields.done,
    baseCompletedAt = fields.completedAt,
)

private fun newLink(taskId: Long, account: String, listRemoteId: String) = GoogleTaskLinkEntity(
    taskId = taskId,
    account = account,
    remoteId = null,
    remoteListId = listRemoteId,
    etag = null,
    baseTitle = "",
    baseNote = "",
    baseDueDay = null,
    baseDone = false,
    baseCompletedAt = null,
)
