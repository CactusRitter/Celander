package com.celander.app.task.google

import com.celander.app.core.googletasks.GTask
import com.celander.app.core.googletasks.GTaskJson
import com.celander.app.core.googletasks.GTaskList
import com.celander.app.core.googletasks.GTaskListPage
import com.celander.app.core.googletasks.GTaskPage
import com.celander.app.core.googletasks.GoogleTaskMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** [TasksRemote] over OkHttp. Calls block on [io]; [baseUrl] is replaced in tests. */
class OkHttpTasksRemote(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher,
    private val baseUrl: HttpUrl = BASE_URL.toHttpUrl(),
) : TasksRemote {
    override suspend fun lists(token: String, pageToken: String?): GTaskListPage {
        val url = url("users/@me/lists") {
            addQueryParameter("maxResults", PAGE_SIZE)
            pageToken?.let { addQueryParameter("pageToken", it) }
        }
        return GTaskJson.listPage(send(token, get(url)))
    }

    override suspend fun defaultList(token: String): GTaskList = GTaskJson.list(send(token, get(url("users/@me/lists/@default"))))

    override suspend fun tasks(token: String, listId: String, updatedMin: Long?, pageToken: String?): GTaskPage {
        val url = url("lists", listId, "tasks") {
            addQueryParameter("showCompleted", "true")
            // Tasks completed in Google's own apps are hidden; without this they would look deleted.
            addQueryParameter("showHidden", "true")
            addQueryParameter("maxResults", PAGE_SIZE)
            if (updatedMin != null) {
                addQueryParameter("showDeleted", "true")
                addQueryParameter("updatedMin", GoogleTaskMapper.formatTime(updatedMin))
            }
            pageToken?.let { addQueryParameter("pageToken", it) }
        }
        return GTaskJson.taskPage(send(token, get(url)))
    }

    override suspend fun insert(token: String, listId: String, body: JsonObject): GTask =
        GTaskJson.task(send(token, Request.Builder().url(url("lists", listId, "tasks")).post(body.toBody())))

    override suspend fun patch(token: String, listId: String, taskId: String, body: JsonObject): GTask =
        GTaskJson.task(send(token, Request.Builder().url(url("lists", listId, "tasks", taskId)).patch(body.toBody())))

    override suspend fun delete(token: String, listId: String, taskId: String) {
        try {
            send(token, Request.Builder().url(url("lists", listId, "tasks", taskId)).delete())
        } catch (e: TasksApiException) {
            if (e.code != 404 && e.code != 410) throw e
        }
    }

    override suspend fun move(token: String, listId: String, taskId: String, destinationListId: String): GTask {
        val url = url("lists", listId, "tasks", taskId, "move") { addQueryParameter("destinationTasklist", destinationListId) }
        return GTaskJson.task(send(token, Request.Builder().url(url).post(EMPTY_BODY)))
    }

    private fun url(vararg segments: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl {
        val builder = baseUrl.newBuilder()
        segments.forEach { segment -> if (segment.contains('/')) builder.addPathSegments(segment) else builder.addPathSegment(segment) }
        return builder.apply(query).build()
    }

    private fun get(url: HttpUrl): Request.Builder = Request.Builder().url(url).get()

    private suspend fun send(token: String, request: Request.Builder): String = withContext(io) {
        val call = client.newCall(request.header("Authorization", "Bearer $token").header("Accept", "application/json").build())
        call.execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw apiError(response.code, text)
            text
        }
    }

    private fun JsonObject.toBody(): RequestBody = toString().toRequestBody(JSON)

    companion object {
        const val BASE_URL = "https://tasks.googleapis.com/tasks/v1/"
        private const val PAGE_SIZE = "100"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

        /**
         * Reads Google's error body: `error.errors[].reason` (classic) and `error.details[].reason`
         * (ErrorInfo); an unreadable body gives an exception without reasons.
         */
        fun apiError(code: Int, body: String): TasksApiException {
            val error = try {
                GTaskJson.json.parseToJsonElement(body).jsonObject["error"] as? JsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            val reasons = listOf("errors", "details").flatMap { key ->
                (error?.get(key) as? JsonArray).orEmpty().mapNotNull { item ->
                    ((item as? JsonObject)?.get("reason") as? JsonPrimitive)?.contentOrNull
                }
            }
            val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull
            return TasksApiException(code, reasons, message)
        }
    }
}
