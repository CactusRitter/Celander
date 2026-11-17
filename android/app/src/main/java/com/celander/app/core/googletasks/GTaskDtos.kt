package com.celander.app.core.googletasks

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A task list as the Google Tasks API returns it. Times are RFC 3339 strings. */
@Serializable
data class GTaskList(
    val id: String,
    val title: String = "",
    val etag: String? = null,
    val updated: String? = null,
)

/** One page of `users/@me/lists`. */
@Serializable
data class GTaskListPage(
    val items: List<GTaskList> = emptyList(),
    val nextPageToken: String? = null,
)

/**
 * A task as the Google Tasks API returns it. [due] carries only a date: Google stores it as UTC
 * midnight and drops any time. [deleted] and [hidden] are only present when requested with
 * `showDeleted` and `showHidden`.
 */
@Serializable
data class GTask(
    val id: String,
    val etag: String? = null,
    val title: String? = null,
    val notes: String? = null,
    val status: String? = null,
    val due: String? = null,
    val completed: String? = null,
    val updated: String? = null,
    val deleted: Boolean = false,
    val hidden: Boolean = false,
    val parent: String? = null,
)

/** One page of `lists/{list}/tasks`. */
@Serializable
data class GTaskPage(
    val items: List<GTask> = emptyList(),
    val nextPageToken: String? = null,
)

/** Decoding of API responses; unknown fields (kind, selfLink, links …) are ignored. */
object GTaskJson {
    val json: Json = Json { ignoreUnknownKeys = true }

    fun list(text: String): GTaskList = json.decodeFromString(text)

    fun listPage(text: String): GTaskListPage = json.decodeFromString(text)

    fun task(text: String): GTask = json.decodeFromString(text)

    fun taskPage(text: String): GTaskPage = json.decodeFromString(text)
}
