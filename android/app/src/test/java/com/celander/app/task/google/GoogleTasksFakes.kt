package com.celander.app.task.google

import android.app.Activity
import android.content.Intent
import com.celander.app.core.googletasks.GTask
import com.celander.app.core.googletasks.GTaskList
import com.celander.app.core.googletasks.GTaskListPage
import com.celander.app.core.googletasks.GTaskPage
import com.celander.app.core.googletasks.GoogleTaskMapper
import com.celander.app.core.time.TimeSource
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A clock the test moves forward. */
class MutableTimeSource(var millis: Long, private val zone: ZoneId = ZoneId.of("Europe/Berlin")) : TimeSource {
    override fun now(): Instant = Instant.ofEpochMilli(millis)
    override fun zone(): ZoneId = zone

    fun advance(ms: Long) {
        millis += ms
    }
}

/**
 * In-memory Google Tasks server: server-side `updated` times and etags, tombstones for deleted
 * tasks, paging, and fault injection. [idPrefix] keeps task ids apart when several servers stand for
 * several accounts.
 */
class FakeTasksRemote(private val clock: MutableTimeSource, private val idPrefix: String = "") : TasksRemote {
    private class Stored(var task: GTask, var listId: String)

    val lists = mutableListOf<GTaskList>()
    var defaultListId: String = ""
    private val tasks = linkedMapOf<String, Stored>()
    private var nextId = 1
    private var version = 1

    /** Every call as "METHOD list[/task]" plus its query details, in order. */
    val calls = mutableListOf<String>()
    val bodies = mutableListOf<JsonObject>()
    val validTokens = mutableSetOf("token-1")
    var pageSize = 100

    /** Returns an exception to throw for a call name (`lists`, `tasks`, `insert`, `patch`, `delete`, `move`), or null. */
    var fault: (String) -> Exception? = { null }

    /** The next insert is stored, but its answer is lost on the way back. */
    var dropNextInsertAnswer = false

    /** Server-side normalisation of inserted titles. */
    var normaliseTitle: (String) -> String = { it }

    /** Runs after a patch was stored and before its answer arrives. */
    var afterPatch: suspend () -> Unit = {}

    val writes: List<String> get() = calls.filter { it.startsWith("POST") || it.startsWith("PATCH") || it.startsWith("DELETE") || it.startsWith("MOVE") }

    fun addList(id: String, title: String, default: Boolean = false) {
        lists += GTaskList(id = id, title = title)
        if (default) defaultListId = id
    }

    fun removeList(id: String) {
        lists.removeAll { it.id == id }
        tasks.values.filter { it.listId == id }.forEach { tasks.values.remove(it) }
    }

    /** A task created in Google's own apps. */
    fun serverAdd(listId: String, title: String, notes: String? = null, due: String? = null, completed: String? = null, hidden: Boolean = false): String {
        val id = "${idPrefix}g${nextId++}"
        val status = if (completed != null) GoogleTaskMapper.STATUS_DONE else GoogleTaskMapper.STATUS_OPEN
        tasks[id] = Stored(stamp(GTask(id = id, title = title, notes = notes, due = due, status = status, completed = completed, hidden = hidden)), listId)
        return id
    }

    fun serverEdit(id: String, edit: (GTask) -> GTask) {
        val stored = tasks.getValue(id)
        stored.task = stamp(edit(stored.task))
    }

    fun serverDelete(id: String) = serverEdit(id) { it.copy(deleted = true) }

    fun serverMove(id: String, listId: String) {
        tasks.getValue(id).listId = listId
        serverEdit(id) { it }
    }

    /** Live (not deleted) tasks of a list. */
    fun live(listId: String): List<GTask> = tasks.values.filter { it.listId == listId && !it.task.deleted }.map { it.task }

    fun task(id: String): GTask? = tasks[id]?.task?.takeIf { !it.deleted }

    fun listIdOf(id: String): String? = tasks[id]?.listId

    private fun stamp(task: GTask): GTask {
        clock.advance(1)
        return task.copy(etag = "\"e${version++}\"", updated = GoogleTaskMapper.formatTime(clock.millis))
    }

    private fun check(token: String, call: String) {
        if (token !in validTokens) throw TasksApiException(401, listOf("authError"))
        fault(call)?.let { throw it }
    }

    override suspend fun lists(token: String, pageToken: String?): GTaskListPage {
        calls += "GET lists${pageToken?.let { " page=$it" }.orEmpty()}"
        check(token, "lists")
        val start = pageToken?.toInt() ?: 0
        val page = lists.drop(start).take(pageSize)
        return GTaskListPage(page, (start + pageSize).takeIf { it < lists.size }?.toString())
    }

    override suspend fun defaultList(token: String): GTaskList {
        calls += "GET default"
        check(token, "lists")
        return lists.first { it.id == defaultListId }
    }

    override suspend fun tasks(token: String, listId: String, updatedMin: Long?, pageToken: String?): GTaskPage {
        calls += "GET $listId${updatedMin?.let { " since=$it" }.orEmpty()}${pageToken?.let { " page=$it" }.orEmpty()}"
        check(token, "tasks")
        if (lists.none { it.id == listId }) throw TasksApiException(404)
        val matching = tasks.values.filter { it.listId == listId }.map { it.task }
            .filter { updatedMin != null || !it.deleted }
            .filter { updatedMin == null || GoogleTaskMapper.parseTime(it.updated!!)!! >= updatedMin }
        val start = pageToken?.toInt() ?: 0
        return GTaskPage(matching.drop(start).take(pageSize), (start + pageSize).takeIf { it < matching.size }?.toString())
    }

    override suspend fun insert(token: String, listId: String, body: JsonObject): GTask {
        calls += "POST $listId"
        bodies += body
        check(token, "insert")
        if (lists.none { it.id == listId }) throw TasksApiException(404)
        val id = "${idPrefix}c${nextId++}"
        val created = stamp(apply(GTask(id = id, status = GoogleTaskMapper.STATUS_OPEN), body).let { it.copy(title = normaliseTitle(it.title.orEmpty())) })
        tasks[id] = Stored(created, listId)
        if (dropNextInsertAnswer) {
            dropNextInsertAnswer = false
            throw IOException("connection reset")
        }
        return created
    }

    override suspend fun patch(token: String, listId: String, taskId: String, body: JsonObject): GTask {
        calls += "PATCH $listId/$taskId"
        bodies += body
        check(token, "patch")
        val stored = tasks[taskId]?.takeIf { it.listId == listId && !it.task.deleted } ?: throw TasksApiException(404)
        stored.task = stamp(apply(stored.task, body))
        afterPatch()
        return stored.task
    }

    override suspend fun delete(token: String, listId: String, taskId: String) {
        calls += "DELETE $listId/$taskId"
        check(token, "delete")
        val stored = tasks[taskId]?.takeIf { it.listId == listId && !it.task.deleted } ?: return
        stored.task = stamp(stored.task.copy(deleted = true))
    }

    override suspend fun move(token: String, listId: String, taskId: String, destinationListId: String): GTask {
        calls += "MOVE $listId/$taskId -> $destinationListId"
        check(token, "move")
        val stored = tasks[taskId]?.takeIf { it.listId == listId && !it.task.deleted } ?: throw TasksApiException(404)
        stored.listId = destinationListId
        stored.task = stamp(stored.task)
        return stored.task
    }

    private fun apply(task: GTask, body: JsonObject): GTask {
        var result = task
        fun text(key: String): String? = (body[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        if ("title" in body) result = result.copy(title = text("title"))
        if ("notes" in body) result = result.copy(notes = text("notes"))
        if ("due" in body) result = result.copy(due = text("due"))
        if ("status" in body) result = result.copy(status = text("status"))
        if ("completed" in body) result = result.copy(completed = text("completed"))
        if (result.status == GoogleTaskMapper.STATUS_DONE && result.completed == null) result = result.copy(completed = GoogleTaskMapper.formatTime(clock.millis))
        if (result.status != GoogleTaskMapper.STATUS_DONE) result = result.copy(completed = null)
        return result
    }
}

/** Hands out "token-1", and "token-2" once "token-1" was cleared; [perAccount] answers for other accounts. */
class FakeAuthorizer : GoogleAuthorizer {
    var available = true
    var background: () -> AuthResult = { AuthResult.Granted(if ("token-1" in cleared) "token-2" else "token-1") }
    val perAccount = mutableMapOf<String, () -> AuthResult>()
    val cleared = mutableListOf<String>()

    override fun playServicesAvailable(): Boolean = available
    override fun chooseAccountIntent(preselect: String?): Intent = Intent("choose")
    override fun accountFromChooser(resultCode: Int, data: Intent?): String? = data?.getStringExtra("account")
    override suspend fun authorize(activity: Activity, email: String): AuthResult = backgroundToken(email)
    override fun consentResult(resultCode: Int, data: Intent?): AuthResult = background()
    override suspend fun backgroundToken(email: String): AuthResult = perAccount[email]?.invoke() ?: background()
    override suspend fun clearToken(token: String) {
        cleared += token
    }
}

/** Several Google accounts, each its own server: a call goes to the server that accepts its token. */
class AccountsRemote(private vararg val servers: FakeTasksRemote) : TasksRemote {
    private fun of(token: String) = servers.firstOrNull { token in it.validTokens } ?: servers.first()

    override suspend fun lists(token: String, pageToken: String?) = of(token).lists(token, pageToken)
    override suspend fun defaultList(token: String) = of(token).defaultList(token)
    override suspend fun tasks(token: String, listId: String, updatedMin: Long?, pageToken: String?) = of(token).tasks(token, listId, updatedMin, pageToken)
    override suspend fun insert(token: String, listId: String, body: JsonObject) = of(token).insert(token, listId, body)
    override suspend fun patch(token: String, listId: String, taskId: String, body: JsonObject) = of(token).patch(token, listId, taskId, body)
    override suspend fun delete(token: String, listId: String, taskId: String) = of(token).delete(token, listId, taskId)
    override suspend fun move(token: String, listId: String, taskId: String, destinationListId: String) = of(token).move(token, listId, taskId, destinationListId)
}

/** Records what would be scheduled. */
class FakeScheduler : TaskSyncScheduler {
    val requests = mutableListOf<Long>()
    val followUps = mutableListOf<Long>()
    var periodic = false
    var cancelled = 0

    override fun requestSoon(delayMillis: Long) {
        synchronized(requests) { requests += delayMillis }
    }

    override fun requestFollowUp(delayMillis: Long) {
        followUps += delayMillis
    }

    override fun schedulePeriodic() {
        periodic = true
    }

    override fun cancelAll() {
        cancelled++
        periodic = false
    }
}
