package com.celander.app.account.microsoft

import com.celander.app.account.google.CalendarApiException
import com.celander.app.account.google.TokenRejectedException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Microsoft Graph's calendar API (v1.0) over OkHttp. Calls block on [io]; [baseUrl] is replaced in tests.
 * There is no "changes since" here: Graph's delta for events needs a time window, so a sync reads the whole calendar.
 */
class GraphCalendarApi(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher,
    private val baseUrl: HttpUrl = BASE_URL.toHttpUrl(),
) {
    /** All calendars of the account (pages followed). */
    suspend fun calendars(token: String): List<JsonObject> =
        pages(token, baseUrl.newBuilder().addPathSegment("calendars").addQueryParameter("\$top", "100").build())

    /** Every event of [calendarId]: series masters and single events, not the expanded occurrences. */
    suspend fun events(token: String, calendarId: String): List<JsonObject> =
        pages(token, baseUrl.newBuilder().addPathSegment("calendars").addPathSegment(calendarId).addPathSegment("events").addQueryParameter("\$top", "100").build())

    /** The occurrences and exceptions of the series [seriesId] between [fromIso] and [toIso] (UTC). */
    suspend fun instances(token: String, seriesId: String, fromIso: String, toIso: String): List<JsonObject> =
        pages(
            token,
            baseUrl.newBuilder().addPathSegment("events").addPathSegment(seriesId).addPathSegment("instances")
                .addQueryParameter("startDateTime", fromIso).addQueryParameter("endDateTime", toIso).addQueryParameter("\$top", "100").build(),
        )

    /** Creates an event in [calendarId]; returns Graph's answer (id, iCalUId, change key). */
    suspend fun insert(token: String, calendarId: String, body: JsonObject): JsonObject =
        send(token, "POST", baseUrl.newBuilder().addPathSegment("calendars").addPathSegment(calendarId).addPathSegment("events").build(), body)

    /** Changes an event, a series or one occurrence (by its own id). */
    suspend fun patch(token: String, eventId: String, body: JsonObject): JsonObject =
        send(token, "PATCH", eventUrl(eventId), body)

    /** Deletes an event, a series or one occurrence; one that is already gone (404) counts as deleted. */
    suspend fun delete(token: String, eventId: String) {
        try {
            send(token, "DELETE", eventUrl(eventId), null)
        } catch (e: CalendarApiException) {
            if (e.code != 404) throw e
        }
    }

    private fun eventUrl(eventId: String): HttpUrl = baseUrl.newBuilder().addPathSegment("events").addPathSegment(eventId).build()

    /** A collection read page after page through `@odata.nextLink`. */
    private suspend fun pages(token: String, first: HttpUrl): List<JsonObject> {
        val all = mutableListOf<JsonObject>()
        var url: HttpUrl? = first
        var guard = 0
        while (url != null && guard++ < MAX_PAGES) {
            val json = send(token, "GET", url, null)
            (json["value"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(all::add) }
            url = (json["@odata.nextLink"] as? JsonPrimitive)?.contentOrNull?.toHttpUrl()
        }
        return all
    }

    private suspend fun send(token: String, method: String, url: HttpUrl, body: JsonObject?): JsonObject {
        var attempt = 0
        while (true) {
            val outcome = withContext(io) { execute(token, method, url, body) }
            if (outcome is Retry && attempt++ < RETRIES) {
                delay(outcome.afterMillis)
                continue
            }
            return when (outcome) {
                is Done -> outcome.json
                is Retry -> throw CalendarApiException(outcome.code, "busy")
            }
        }
    }

    private sealed interface Outcome
    private class Done(val json: JsonObject) : Outcome
    private class Retry(val code: Int, val afterMillis: Long) : Outcome

    private fun execute(token: String, method: String, url: HttpUrl, body: JsonObject?): Outcome {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
            // Plain text bodies: the app has no HTML descriptions.
            .header("Prefer", "outlook.body-content-type=\"text\"")
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            return when {
                response.isSuccessful -> Done(if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text) as JsonObject)
                response.code == 401 -> throw TokenRejectedException()
                // Too many requests or a server hiccup: wait as long as the server says (at most a few seconds) and ask again.
                response.code == 429 || response.code == 503 || response.code == 504 ->
                    Retry(response.code, ((response.header("Retry-After")?.toLongOrNull() ?: 2L).coerceIn(1L, 10L)) * 1000L)
                else -> throw CalendarApiException(response.code, errorReason(text))
            }
        }
    }

    private fun errorReason(body: String): String? = runCatching {
        val error = (Json.parseToJsonElement(body) as JsonObject)["error"] as? JsonObject
        (error?.get("code") as? JsonPrimitive)?.contentOrNull
    }.getOrNull()

    private companion object {
        const val BASE_URL = "https://graph.microsoft.com/v1.0/me/"
        const val MAX_PAGES = 200
        const val RETRIES = 2
    }
}
