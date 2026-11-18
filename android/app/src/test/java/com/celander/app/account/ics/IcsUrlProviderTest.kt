package com.celander.app.account.ics

import com.celander.app.core.database.AccountEntity
import com.celander.app.core.database.RemoteCalendarEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class IcsUrlProviderTest {
    private lateinit var server: MockWebServer
    private val feed = "BEGIN:VCALENDAR\r\nX-WR-CALNAME:Holidays\r\nBEGIN:VEVENT\r\nUID:a1\r\nDTSTART;VALUE=DATE:20261225\r\nSUMMARY:Christmas\r\nEND:VEVENT\r\n" +
        "BEGIN:VEVENT\r\nUID:s1\r\nDTSTART:20261120T100000Z\r\nDTEND:20261120T110000Z\r\nRRULE:FREQ=WEEKLY\r\nSUMMARY:Class\r\nEND:VEVENT\r\n" +
        "BEGIN:VEVENT\r\nUID:s1\r\nRECURRENCE-ID:20261127T100000Z\r\nDTSTART:20261127T120000Z\r\nDTEND:20261127T130000Z\r\nSUMMARY:Class late\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

    @Before fun start() { server = MockWebServer().also { it.start() } }

    @After fun stop() = server.shutdown()

    private val provider get() = IcsUrlProvider(OkHttpClient(), Dispatchers.IO)

    @Test
    fun aSyncReadsEverythingAsAFullReadOnlyListWithOneIdPerOccurrence() = runBlocking {
        server.enqueue(MockResponse().setBody(feed))
        val url = server.url("/cal.ics").toString()
        val account = AccountEntity(id = 1, type = "ICS_URL", identifier = url, displayName = "Holidays", createdAt = 1)
        val calendar = provider.listCalendars(account).single()
        assertEquals(url, calendar.remoteId)
        assertFalse(calendar.accessRole.canWrite)
        assertFalse(provider.capabilities.canWrite)
        val result = provider.syncEvents(account, RemoteCalendarEntity(calendarId = 2, accountId = 1, remoteId = url, accessRole = "READER", syncToken = null, timeZone = null))
        assertTrue(result.full)
        assertEquals(3, result.events.size)
        assertEquals(3, result.events.map { it.remoteId }.toSet().size)
        assertEquals("s1", result.events[1].ical!!.uid)
    }

    @Test
    fun somethingThatIsNotACalendarOrAnErrorIsRefused() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>hello</html>"))
        try {
            provider.fetch(server.url("/x").toString())
            fail("not a calendar")
        } catch (e: IcsFeedException) {
            assertEquals(0, e.code)
        }
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            provider.fetch(server.url("/y").toString())
            fail("404")
        } catch (e: IcsFeedException) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun webcalLinksAreHttps() {
        assertEquals("https://example.com/a.ics", IcsUrlProvider.normalize(" webcal://example.com/a.ics "))
        assertEquals("https://example.com/a.ics", IcsUrlProvider.normalize("webcals://example.com/a.ics"))
    }
}
