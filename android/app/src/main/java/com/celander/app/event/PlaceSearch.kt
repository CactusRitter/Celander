package com.celander.app.event

import com.celander.app.BuildConfig
import com.celander.app.core.places.PhotonJson
import com.celander.app.core.places.PlaceSuggestion
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Places for the location field while typing; the field itself stays free text. */
interface PlaceSearch {
    suspend fun suggest(query: String, language: String): List<PlaceSuggestion>

    /** For tests and builds without suggestions. */
    object None : PlaceSearch {
        override suspend fun suggest(query: String, language: String): List<PlaceSuggestion> = emptyList()
    }
}

/**
 * [PlaceSearch] over Photon, komoot's free geocoder on OpenStreetMap data. Only the typed text and
 * the language go out, never a position. Requests run on OkHttp's threads and are cancelled with
 * the calling coroutine, so a newer query never waits for an older one; answers are parsed on
 * [io]. [baseUrl] is replaced in tests.
 */
class PhotonPlaceSearch(
    client: OkHttpClient,
    private val io: CoroutineDispatcher,
    private val baseUrl: HttpUrl = BASE_URL.toHttpUrl(),
) : PlaceSearch {
    // Suggestions that come late are of no use, so they get a short limit of their own.
    private val client = client.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    override suspend fun suggest(query: String, language: String): List<PlaceSuggestion> {
        val url = baseUrl.newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", LIMIT.toString())
            .apply { if (language in LANGUAGES) addQueryParameter("lang", language) }
            .build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val body = client.newCall(request).successfulBody() ?: return emptyList()
        return withContext(io) { PhotonJson.suggestions(body) }
    }

    /** The body of a successful answer, null for an error status; cancelling the coroutine cancels the call. */
    private suspend fun Call.successfulBody(): String? = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { this@successfulBody.cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = try {
                        response.use { if (it.isSuccessful) it.body?.string().orEmpty() else null }
                    } catch (e: IOException) {
                        continuation.resumeWithException(e)
                        return
                    }
                    continuation.resume(body)
                }
            },
        )
    }

    private companion object {
        const val BASE_URL = "https://photon.komoot.io/api/"
        const val LIMIT = 5
        const val TIMEOUT_SECONDS = 5L

        /** Languages Photon names places in; others get the names used on site. */
        val LANGUAGES = setOf("de", "en", "fr")
        val USER_AGENT = "Celander/${BuildConfig.VERSION_NAME} (Android calendar app)"
    }
}
