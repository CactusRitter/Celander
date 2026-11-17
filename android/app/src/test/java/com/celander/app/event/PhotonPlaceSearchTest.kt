package com.celander.app.event

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What goes to Photon: the typed text, a limit and the language, nothing about the phone's position. */
class PhotonPlaceSearchTest {
    private val server = MockWebServer()
    private val search = PhotonPlaceSearch(OkHttpClient(), Dispatchers.Unconfined, server.url("/api/"))

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun sendsOnlyTheTextLimitAndLanguage() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"features":[{"properties":{"name":"Marienplatz","city":"München"}}]}"""))
        val places = search.suggest("Marienpl", "de")
        assertEquals("Marienplatz, München", places.single().text)
        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals(setOf("q", "limit", "lang"), url.queryParameterNames)
        assertEquals("Marienpl", url.queryParameter("q"))
        assertEquals("de", url.queryParameter("lang"))
        assertTrue(request.getHeader("User-Agent")!!.startsWith("Celander/"))
    }

    @Test
    fun languagesPhotonDoesNotKnowAreLeftOut() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"features":[]}"""))
        search.suggest("Plaza Mayor", "es")
        assertNull(server.takeRequest().requestUrl!!.queryParameter("lang"))
    }

    @Test
    fun aFailedAnswerHasNoPlaces() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(search.suggest("Marienplatz", "de").isEmpty())
    }

    @Test
    fun aNewerQueryDoesNotWaitForTheOldRequest() = runBlocking {
        // Photon takes the request but never answers.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = OkHttpClient()
        val slow = PhotonPlaceSearch(client, Dispatchers.Unconfined, server.url("/api/"))
        val job = launch(Dispatchers.IO) { slow.suggest("Marienplatz", "de") }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))

        val started = System.nanoTime()
        job.cancelAndJoin()

        // Without cancelling the call, the job would only end with the 5 s call timeout.
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2))
        assertTrue(client.dispatcher.runningCalls().all { it.isCanceled() })
    }
}
