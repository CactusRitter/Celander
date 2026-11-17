package com.celander.app.account.google

import com.celander.app.account.NeedsSignInException
import com.celander.app.core.database.AccountEntity
import com.celander.app.core.database.RemoteCalendarEntity
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GoogleCalendarProviderTest {
    private lateinit var server: MockWebServer
    private val account = AccountEntity(id = 1, type = "GOOGLE", identifier = "a@example.com", displayName = "A", createdAt = 1)
    private val calendar = RemoteCalendarEntity(calendarId = 5, accountId = 1, remoteId = "primary", accessRole = "OWNER", syncToken = null, timeZone = "Europe/Berlin")
    private val tokensGiven = mutableListOf<Boolean>()

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun stop() = server.shutdown()

    private fun provider(token: (Boolean) -> String? = { "t1" }) = GoogleCalendarProvider(
        GoogleCalendarApi(OkHttpClient(), Dispatchers.IO, server.url("/calendar/v3/")),
        { _, force -> tokensGiven += force; token(force) },
        now = { Instant.parse("2026-11-17T12:00:00Z") },
    )

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    private fun event(id: String) = """{"id":"$id","summary":"E$id","start":{"dateTime":"2026-11-20T15:00:00Z"},"end":{"dateTime":"2026-11-20T16:00:00Z"}}"""

    @Test
    fun theFirstSyncFollowsThePagesAndReturnsTheSyncToken() = runBlocking {
        server.enqueue(json("""{"items":[${event("1")}],"nextPageToken":"p2"}"""))
        server.enqueue(json("""{"items":[${event("2")},{"id":"3","status":"cancelled"}],"nextSyncToken":"S1"}"""))
        val result = provider().syncEvents(account, calendar)
        assertEquals(listOf("1", "2", "3"), result.events.map { it.remoteId })
        assertNull(result.events.last().ical)
        assertEquals("S1", result.nextToken)
        assertTrue(result.full)
        val first = server.takeRequest()
        assertEquals("Bearer t1", first.getHeader("Authorization"))
        assertTrue(first.path!!.contains("timeMin=2024-11-17"))
        assertTrue(first.path!!.contains("singleEvents=false"))
        assertTrue(first.path!!.contains("showDeleted=true"))
        assertTrue(server.takeRequest().path!!.contains("pageToken=p2"))
    }

    @Test
    fun laterSyncsAskForChangesSinceTheTokenAndNeverSendATimeMin() = runBlocking {
        server.enqueue(json("""{"items":[${event("9")}],"nextSyncToken":"S2"}"""))
        val result = provider().syncEvents(account, calendar.copy(syncToken = "S1"))
        assertFalse(result.full)
        assertEquals("S2", result.nextToken)
        val path = server.takeRequest().path!!
        assertTrue(path.contains("syncToken=S1"))
        assertFalse(path.contains("timeMin"))
    }

    @Test
    fun aRefusedSyncTokenMeansFetchingTheWholeCalendarAgain() = runBlocking {
        server.enqueue(json("""{"error":{"message":"Sync token is no longer valid"}}""", code = 410))
        server.enqueue(json("""{"items":[${event("1")}],"nextSyncToken":"S9"}"""))
        val result = provider().syncEvents(account, calendar.copy(syncToken = "old"))
        assertTrue(result.full)
        assertEquals("S9", result.nextToken)
        server.takeRequest()
        assertFalse(server.takeRequest().path!!.contains("syncToken"))
    }

    @Test
    fun aRefusedAccessTokenIsRefreshedOnceAndTheCallRepeated() = runBlocking {
        server.enqueue(json("{}", code = 401))
        server.enqueue(json("""{"items":[{"id":"primary","summary":"Me","accessRole":"owner","primary":true}]}"""))
        val calendars = provider { force -> if (force) "t2" else "t1" }.listCalendars(account)
        assertEquals(listOf("primary"), calendars.map { it.remoteId })
        assertEquals(listOf(false, true), tokensGiven)
        assertEquals("Bearer t1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer t2", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun noTokenOrTwoRefusalsMeanTheUserMustSignInAgain() = runBlocking {
        try {
            provider { null }.listCalendars(account)
            fail("expected NeedsSignInException")
        } catch (_: NeedsSignInException) {
        }
        server.enqueue(json("{}", code = 401))
        server.enqueue(json("{}", code = 401))
        try {
            provider().listCalendars(account)
            fail("expected NeedsSignInException")
        } catch (_: NeedsSignInException) {
        }
    }
}
