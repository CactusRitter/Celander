package com.celander.app.account.google

import com.celander.app.account.NeedsSignInException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** An answer of the Calendar API that is an error; [code] is the HTTP status. */
class CalendarApiException(val code: Int, message: String? = null) : java.io.IOException("HTTP $code" + (message?.let { " · $it" } ?: ""))

/** The access token was refused (401): ask for a fresh one and try again. */
class TokenRejectedException : java.io.IOException("401")

/** The sync token is no longer valid (410): fetch the whole calendar again. */
class SyncTokenGoneException : java.io.IOException("410")

/** One page of an events list. */
data class EventsPage(val items: List<JsonObject>, val nextPageToken: String?, val nextSyncToken: String?)

/** Google Calendar API v3 over OkHttp. Calls block on [io]; [baseUrl] is replaced in tests. */
class GoogleCalendarApi(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher,
    private val baseUrl: HttpUrl = BASE_URL.toHttpUrl(),
) {
    /** All calendars the account sees (pages followed). */
    suspend fun calendarList(token: String): List<JsonObject> {
        val all = mutableListOf<JsonObject>()
        var page: String? = null
        do {
            val url = baseUrl.newBuilder().addPathSegments("users/me/calendarList")
                .addQueryParameter("maxResults", "250")
                .apply { page?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = get(token, url)
            all += items(json)
            page = (json["nextPageToken"] as? JsonPrimitive)?.contentOrNull
        } while (page != null)
        return all
    }

    /**
     * One page of the events of [calendarId]: the changes since [syncToken] when given (deleted events included), else
     * everything that ends after [timeMinIso]. Series are not expanded (`singleEvents=false`): the app expands them itself.
     */
    suspend fun events(token: String, calendarId: String, syncToken: String?, timeMinIso: String?, pageToken: String?): EventsPage {
        val url = baseUrl.newBuilder().addPathSegments("calendars").addPathSegment(calendarId).addPathSegment("events")
            .addQueryParameter("maxResults", "2500")
            .addQueryParameter("showDeleted", "true")
            .addQueryParameter("singleEvents", "false")
            .apply {
                if (syncToken != null) addQueryParameter("syncToken", syncToken) else timeMinIso?.let { addQueryParameter("timeMin", it) }
                pageToken?.let { addQueryParameter("pageToken", it) }
            }
            .build()
        val json = get(token, url)
        return EventsPage(
            items(json),
            (json["nextPageToken"] as? JsonPrimitive)?.contentOrNull,
            (json["nextSyncToken"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    private fun items(json: JsonObject): List<JsonObject> = (json["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    private suspend fun get(token: String, url: HttpUrl): JsonObject = withContext(io) {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> Json.parseToJsonElement(body) as JsonObject
                response.code == 401 -> throw TokenRejectedException()
                response.code == 410 -> throw SyncTokenGoneException()
                else -> throw CalendarApiException(response.code, errorReason(body))
            }
        }
    }

    private fun errorReason(body: String): String? = runCatching {
        val error = (Json.parseToJsonElement(body) as JsonObject)["error"] as? JsonObject
        (error?.get("message") as? JsonPrimitive)?.contentOrNull
    }.getOrNull()

    private companion object {
        const val BASE_URL = "https://www.googleapis.com/calendar/v3/"
    }
}
