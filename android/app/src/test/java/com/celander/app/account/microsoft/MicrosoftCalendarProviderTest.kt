package com.celander.app.account.microsoft

import com.celander.app.account.NeedsSignInException
import com.celander.app.core.database.AccountEntity
import com.celander.app.core.database.RemoteCalendarEntity
import com.celander.app.core.ical.ICalEvent
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class MicrosoftCalendarProviderTest {
    private lateinit var server: MockWebServer
    private val account = AccountEntity(id = 1, type = "MICROSOFT", identifier = "a@example.com", displayName = "A", createdAt = 1)
    private val calendar = RemoteCalendarEntity(calendarId = 5, accountId = 1, remoteId = "cal1", accessRole = "OWNER", syncToken = null, timeZone = null)

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun stop() = server.shutdown()

    private fun provider(token: (Boolean) -> String? = { "t1" }) = MicrosoftCalendarProvider(
        GraphCalendarApi(OkHttpClient(), Dispatchers.IO, server.url("/v1.0/me/")),
        { _, force -> token(force) },
        now = { Instant.parse("2026-11-17T12:00:00Z") },
    )

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    private fun ms(text: String) = Instant.parse(text).toEpochMilli()

    private fun single(id: String, start: String = "2026-11-20T15:00:00") =
        """{"id":"$id","iCalUId":"u$id","type":"singleInstance","subject":"E$id","start":{"dateTime":"$start.0000000","timeZone":"UTC"},"end":{"dateTime":"${start.replace("15:00", "16:00")}.0000000","timeZone":"UTC"}}"""

    private val series = """{"id":"S1","iCalUId":"useries","type":"seriesMaster","subject":"Gym","originalStartTimeZone":"UTC",
        |"start":{"dateTime":"2026-11-20T10:00:00.0000000","timeZone":"UTC"},"end":{"dateTime":"2026-11-20T11:00:00.0000000","timeZone":"UTC"},
        |"recurrence":{"pattern":{"type":"weekly","interval":1,"daysOfWeek":["friday"],"firstDayOfWeek":"monday"},"range":{"type":"noEnd","startDate":"2026-11-20"}}}""".trimMargin()

    private val exception = """{"id":"I9","type":"exception","subject":"Gym late","originalStart":"2026-11-27T10:00:00Z","originalStartTimeZone":"UTC",
        |"start":{"dateTime":"2026-11-27T12:00:00.0000000","timeZone":"UTC"},"end":{"dateTime":"2026-11-27T13:00:00.0000000","timeZone":"UTC"}}""".trimMargin()

    @Test
    fun theCalendarsAreListedWithTheirRoles() = runBlocking {
        server.enqueue(json("""{"value":[{"id":"c1","name":"Calendar","canEdit":true,"isDefaultCalendar":true,"hexColor":"#0078d4"}]}"""))
        val list = provider().listCalendars(account)
        assertEquals("Calendar", list.single().name)
        assertTrue(list.single().isPrimary)
        assertEquals("Bearer t1", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun aSyncReadsEveryPageAndTheExceptionsOfEachSeries() = runBlocking {
        server.enqueue(json("""{"value":[${single("1")}],"@odata.nextLink":"${server.url("/v1.0/me/calendars/cal1/events?\$skip=1")}"}"""))
        server.enqueue(json("""{"value":[$series]}"""))
        server.enqueue(json("""{"value":[{"id":"I1","type":"occurrence"},$exception]}"""))
        val result = provider().syncEvents(account, calendar)
        assertEquals(listOf("1", "S1", "I9"), result.events.map { it.remoteId })
        assertTrue(result.full)
        assertNull(result.nextToken)
        val master = result.events[1].ical!!
        assertEquals("FREQ=WEEKLY;BYDAY=FR", master.rrule)
        val changed = result.events[2].ical!!
        assertEquals("useries", changed.uid)
        assertEquals(ms("2026-11-27T10:00:00Z"), changed.recurrenceId)
        assertTrue(server.takeRequest().path!!.contains("calendars/cal1/events"))
        server.takeRequest()
        val instances = server.takeRequest().path!!
        assertTrue(instances.contains("/events/S1/instances"))
        assertTrue(instances.contains("startDateTime=2024-11-17"))
    }

    @Test
    fun singleEventsThatEndedLongAgoAreLeftOut() = runBlocking {
        server.enqueue(json("""{"value":[${single("old", "2020-01-01T15:00:00")},${single("new")}]}"""))
        assertEquals(listOf("new"), provider().syncEvents(account, calendar).events.map { it.remoteId })
    }

    private fun ical(rrule: String? = null, recurrenceId: Long? = null, exdates: List<Long> = emptyList()) = ICalEvent(
        uid = "u", summary = "Gym", start = ms("2026-11-20T10:00:00Z"), end = ms("2026-11-20T11:00:00Z"), allDay = false, timeZone = "UTC",
        rrule = rrule, recurrenceId = recurrenceId, exdates = exdates,
    )

    @Test
    fun aNewEventIsPostedToItsCalendar() = runBlocking {
        server.enqueue(json("""{"id":"N1","iCalUId":"un","@odata.etag":"W/\"e\"","lastModifiedDateTime":"2026-11-17T12:00:00Z"}"""))
        val write = provider().create(account, calendar, ical(), listOf(10))
        assertEquals("N1", write.remoteId)
        assertEquals("un", write.uid)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1.0/me/calendars/cal1/events", request.path)
        assertTrue(request.body.readUtf8().contains("\"reminderMinutesBeforeStart\":10"))
    }

    @Test
    fun aChangedOccurrenceFindsItsInstanceAndPatchesIt() = runBlocking {
        server.enqueue(json("""{"value":[{"id":"I1","type":"occurrence","originalStart":"2026-11-20T10:00:00Z"},{"id":"I2","type":"occurrence","originalStart":"2026-11-27T10:00:00Z"}]}"""))
        server.enqueue(json("""{"id":"I2"}"""))
        provider().writeOccurrence(account, calendar, "S1", ical(recurrenceId = ms("2026-11-27T10:00:00Z")), emptyList())
        assertTrue(server.takeRequest().path!!.contains("/events/S1/instances"))
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/v1.0/me/events/I2", patch.path)
    }

    @Test
    fun aCancelledOccurrenceIsDeletedAndOneAlreadyGoneIsLeftAlone() = runBlocking {
        val gone = ms("2026-12-04T10:00:00Z")
        val there = ms("2026-11-27T10:00:00Z")
        server.enqueue(json("""{"id":"S1"}"""))
        server.enqueue(json("""{"value":[{"id":"I2","type":"occurrence","originalStart":"2026-11-27T10:00:00Z"}]}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        provider().update(account, calendar, "S1", ical(rrule = "FREQ=WEEKLY", exdates = listOf(there, gone)), emptyList(), etag = null)
        assertEquals("PATCH", server.takeRequest().method)
        server.takeRequest()
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/v1.0/me/events/I2", delete.path)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun deletingAnEventThatIsAlreadyGoneCountsAsDeleted() = runBlocking {
        server.enqueue(json("""{"error":{"code":"ErrorItemNotFound"}}""", code = 404))
        provider().delete(account, calendar, "X", null)
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun aRefusedTokenIsRenewedOnceThenMeansSignInAgain() = runBlocking {
        server.enqueue(json("""{}""", code = 401))
        server.enqueue(json("""{"value":[]}"""))
        assertTrue(provider { if (it) "t2" else "t1" }.listCalendars(account).isEmpty())
        assertEquals("Bearer t2", server.takeRequest().let { server.takeRequest().getHeader("Authorization") })
        server.enqueue(json("""{}""", code = 401))
        server.enqueue(json("""{}""", code = 401))
        try {
            provider().listCalendars(account)
            fail("should need a new sign-in")
        } catch (_: NeedsSignInException) {
        }
        assertEquals(null, provider { null }.let { runCatching { it.listCalendars(account) }.exceptionOrNull()?.takeIf { e -> e !is NeedsSignInException } })
    }
}
