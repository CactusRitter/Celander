package com.celander.app.account.google

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A contact with a birthday, as the People API gives it. [year] is null when the contact has no year of birth. */
data class ContactBirthday(val contact: String, val name: String, val year: Int?, val month: Int, val day: Int)

/**
 * Google People API (v1), only to read the names and birthdays of the user's contacts; needs the
 * `contacts.readonly` scope, which the user grants when switching on "Birthdays from contacts".
 * Errors are the same as the Calendar API's ([TokenRejectedException] for 401, [CalendarApiException] for the rest).
 */
class PeopleApi(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher,
    private val baseUrl: HttpUrl = BASE_URL.toHttpUrl(),
) {
    /** Every contact that has a birthday (pages followed). */
    suspend fun birthdays(token: String): List<ContactBirthday> {
        val all = mutableListOf<ContactBirthday>()
        var page: String? = null
        do {
            val url = baseUrl.newBuilder().addPathSegments("people/me/connections")
                .addQueryParameter("personFields", "names,birthdays")
                .addQueryParameter("pageSize", "1000")
                .apply { page?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = get(token, url)
            (json["connections"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNullTo(all, ::birthday)
            page = (json["nextPageToken"] as? JsonPrimitive)?.contentOrNull
        } while (page != null)
        return all
    }

    private suspend fun get(token: String, url: HttpUrl): JsonObject = withContext(io) {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> Json.parseToJsonElement(body) as JsonObject
                response.code == 401 -> throw TokenRejectedException()
                else -> throw CalendarApiException(response.code)
            }
        }
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    companion object {
        private const val BASE_URL = "https://people.googleapis.com/v1/"

        /** The contact's id and the date of the birthday: the primary one if there are several. Null without a name or a day and month. */
        fun birthday(person: JsonObject): ContactBirthday? {
            val resource = (person["resourceName"] as? JsonPrimitive)?.contentOrNull ?: return null
            val names = (person["names"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val name = (names.firstOrNull(::primary) ?: names.firstOrNull())?.let { (it["displayName"] as? JsonPrimitive)?.contentOrNull }
                ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val dates = (person["birthdays"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val date = ((dates.firstOrNull(::primary) ?: dates.firstOrNull())?.get("date") as? JsonObject) ?: return null
            val month = (date["month"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..12 } ?: return null
            val day = (date["day"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..31 } ?: return null
            val year = (date["year"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 1 }
            return ContactBirthday(resource, name, year, month, day)
        }

        private fun primary(json: JsonObject): Boolean =
            ((json["metadata"] as? JsonObject)?.get("primary") as? JsonPrimitive)?.contentOrNull == "true"
    }
}
