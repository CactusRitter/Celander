package com.celander.app.task.google

import com.celander.app.core.googletasks.GTask
import com.celander.app.core.googletasks.GTaskList
import com.celander.app.core.googletasks.GTaskListPage
import com.celander.app.core.googletasks.GTaskPage
import java.io.IOException
import kotlinx.serialization.json.JsonObject

/**
 * The Google Tasks REST API (v1). Every call takes the OAuth access token. Network failures throw
 * [IOException]; answers other than 2xx throw [TasksApiException].
 */
interface TasksRemote {
    suspend fun lists(token: String, pageToken: String? = null): GTaskListPage

    /** The user's default list ("My Tasks"). */
    suspend fun defaultList(token: String): GTaskList

    /**
     * One page of a list's tasks, including completed and hidden ones. With [updatedMin] (ms) only
     * tasks changed since then are returned, deleted ones included.
     */
    suspend fun tasks(token: String, listId: String, updatedMin: Long? = null, pageToken: String? = null): GTaskPage

    suspend fun insert(token: String, listId: String, body: JsonObject): GTask

    suspend fun patch(token: String, listId: String, taskId: String, body: JsonObject): GTask

    /** Deletes a task; a task Google no longer has (404, 410) counts as deleted. */
    suspend fun delete(token: String, listId: String, taskId: String)

    /** Moves a task into another list; Google keeps its id. */
    suspend fun move(token: String, listId: String, taskId: String, destinationListId: String): GTask
}

/** An HTTP error answer of the Tasks API with the `reason` values Google gave for it. */
class TasksApiException(val code: Int, val reasons: List<String> = emptyList(), message: String? = null) :
    Exception(listOfNotNull("HTTP $code", reasons.joinToString(",").ifEmpty { null }, message).joinToString(" · ")) {

    /** The Tasks API is not enabled in the app's Google Cloud project. */
    val apiDisabled: Boolean get() = code == 403 && reasons.any { it in API_DISABLED_REASONS }

    /** The token lacks the Tasks scope; a new consent fixes it. */
    val insufficientScope: Boolean get() = code == 403 && reasons.any { it in SCOPE_REASONS }

    /**
     * Timeouts, conflicts, rate limits, quotas and server errors pass on their own; the sync tries
     * again later. Checked first, so a limit wins over any other reason given with it.
     */
    val transient: Boolean
        get() = code == 408 || code == 409 || code == 429 || code >= 500 || reasons.any { it in TRANSIENT_REASONS }

    /**
     * Google refuses this one request for what it carries, so sending the same task again cannot
     * help: its content (400, 413) or access to this very task or its list (403 `forbidden`, while
     * the account could read its lists). Errors without such a sure sign concern the whole run.
     */
    val refusesThisTask: Boolean
        get() = !transient && !apiDisabled && !insufficientScope && when (code) {
            400, 413 -> true
            403 -> reasons.any { it in TASK_REASONS }
            else -> false
        }

    private companion object {
        val API_DISABLED_REASONS = setOf("accessNotConfigured", "SERVICE_DISABLED")
        val SCOPE_REASONS = setOf("insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT")
        val TRANSIENT_REASONS = setOf(
            "rateLimitExceeded",
            "userRateLimitExceeded",
            "RATE_LIMIT_EXCEEDED",
            "quotaExceeded",
            "dailyLimitExceeded",
            "servingLimitExceeded",
            "concurrentLimitExceeded",
            "backendError",
            "internalError",
        )
        val TASK_REASONS = setOf("forbidden")
    }
}
