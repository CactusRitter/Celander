package com.celander.app.account.google

import com.celander.app.core.database.AccountEntity
import com.celander.app.core.database.RemoteCalendarEntity
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BirthdaysTest {
    private lateinit var server: MockWebServer
    private val account = AccountEntity(id = 1, type = "GOOGLE", identifier = "a@example.com", displayName = "A", createdAt = 1)
    private val calendar = RemoteCalendarEntity(calendarId = 7, accountId = 1, remoteId = BirthdayEvents.CALENDAR_ID, accessRole = "READER", syncToken = null)
    private val now = Instant.parse("2026-11-22T12:00:00Z")

    private class Source(var on: Boolean = true) : BirthdaySource {
        val failures = mutableListOf<Int>()
        override fun enabled(accountId: Long) = on
        override fun calendarName() = "Birthdays"
        override fun failed(accountId: Long, code: Int) {
            failures += code
            on = false
        }
    }

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun stop() = server.shutdown()

    private fun provider(source: Source) = GoogleCalendarProvider(
        GoogleCalendarApi(OkHttpClient(), Dispatchers.IO, server.url("/calendar/v3/")),
        { _, _ -> "t1" },
        now = { now },
        birthdays = source,
        people = PeopleApi(OkHttpClient(), Dispatchers.IO, server.url("/v1/")),
        zone = java.time.ZoneOffset.UTC,
    )

    private fun person(json: String) = Json.parseToJsonElement(json) as JsonObject

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    @Test
    fun aContactWithADateBecomesABirthday() {
        val b = PeopleApi.birthday(person("""{"resourceName":"people/c1","names":[{"displayName":"Ana Lopez"}],"birthdays":[{"date":{"year":1990,"month":3,"day":5}}]}"""))
        assertEquals(ContactBirthday("people/c1", "Ana Lopez", 1990, 3, 5), b)
    }

    @Test
    fun aContactWithoutYearOrNameOrDayIsHandledSafely() {
        assertNull(PeopleApi.birthday(person("""{"resourceName":"people/c1","names":[{"displayName":"Ana"}],"birthdays":[{"date":{"month":3,"day":5}}]}"""))!!.year)
        assertNull(PeopleApi.birthday(person("""{"resourceName":"people/c1","birthdays":[{"date":{"month":3,"day":5}}]}""")))
        assertNull(PeopleApi.birthday(person("""{"resourceName":"people/c1","names":[{"displayName":"Ana"}],"birthdays":[{"text":"someday"}]}""")))
        assertNull(PeopleApi.birthday(person("""{"resourceName":"people/c1","names":[{"displayName":"Ana"}]}""")))
    }

    @Test
    fun theTitleCarriesTheAgeOnlyWhenTheYearIsKnown() {
        val withYear = ContactBirthday("people/c1", "Ana", 1990, 3, 5)
        assertEquals("Ana · 36", BirthdayEvents.title(withYear, 2026))
        assertEquals("Ana", BirthdayEvents.title(withYear.copy(year = null), 2026))
        assertEquals("Ana", BirthdayEvents.title(withYear.copy(year = 2030), 2026))
    }

    @Test
    fun the29thOfFebruaryFallsOnThe28thInOrdinaryYears() {
        val leap = ContactBirthday("people/c1", "Ana", 2000, 2, 29)
        assertEquals(LocalDate.of(2027, 2, 28), BirthdayEvents.date(leap, 2027))
        assertEquals(LocalDate.of(2028, 2, 29), BirthdayEvents.date(leap, 2028))
    }

    @Test
    fun eachContactGetsAllDayEventsForAWindowOfYears() {
        val events = BirthdayEvents.build(listOf(ContactBirthday("people/c1", "Ana", 1990, 11, 22)), LocalDate.of(2026, 11, 22), 5)
        assertEquals((2025..2029).map { "celander-birthday:people/c1:$it" }, events.map { it.remoteId })
        val ical = events[1].ical!!
        assertTrue(ical.allDay)
        assertEquals("Ana · 36", ical.summary)
        assertEquals(LocalDate.of(2026, 11, 22).toEpochDay() * 86_400_000L, ical.start)
        assertEquals(ical.start + 86_400_000L, ical.end)
        assertNull(ical.rrule)
    }

    @Test
    fun theCalendarIsListedOnlyWhenSwitchedOn() = runBlocking {
        server.enqueue(json("""{"items":[{"id":"primary","summary":"A","accessRole":"owner","primary":true}]}"""))
        val on = provider(Source(true)).listCalendars(account)
        assertEquals(listOf("primary", BirthdayEvents.CALENDAR_ID), on.map { it.remoteId })
        assertFalse(on.last().accessRole.canWrite)
        server.enqueue(json("""{"items":[{"id":"primary","summary":"A","accessRole":"owner","primary":true}]}"""))
        assertEquals(listOf("primary"), provider(Source(false)).listCalendars(account).map { it.remoteId })
    }

    @Test
    fun syncReadsTheContactsOnceADay() = runBlocking {
        server.enqueue(json("""{"connections":[{"resourceName":"people/c1","names":[{"displayName":"Ana"}],"birthdays":[{"date":{"year":1990,"month":3,"day":5}}]}],"nextPageToken":"p2"}"""))
        server.enqueue(json("""{"connections":[{"resourceName":"people/c2","names":[{"displayName":"Bo"}],"birthdays":[{"date":{"month":12,"day":1}}]}]}"""))
        val result = provider(Source()).syncEvents(account, calendar)
        assertTrue(result.full)
        assertEquals(10, result.events.size)
        val first = server.takeRequest()
        assertEquals("Bearer t1", first.getHeader("Authorization"))
        assertTrue(first.path!!.contains("personFields=names%2Cbirthdays") || first.path!!.contains("personFields=names,birthdays"))
        assertTrue(server.takeRequest().path!!.contains("pageToken=p2"))
        // Read less than a day ago: no request at all.
        val recent = provider(Source()).syncEvents(account, calendar.copy(lastFullSyncAt = now.toEpochMilli() - 3_600_000L))
        assertFalse(recent.full)
        assertTrue(recent.events.isEmpty())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aRefusedPermissionSwitchesTheBirthdaysOffWithoutBreakingTheSync() = runBlocking {
        val source = Source()
        server.enqueue(json("""{"error":{"message":"insufficient scopes"}}""", code = 403))
        val result = provider(source).syncEvents(account, calendar)
        assertFalse(result.full)
        assertEquals(listOf(403), source.failures)
        assertFalse(source.on)
    }

    @Test
    fun aConnectionProblemLeavesEverythingAsItIsAndTriesAgainLater() = runBlocking {
        val source = Source()
        server.enqueue(json("oops", code = 500))
        val result = provider(source).syncEvents(account, calendar)
        assertFalse(result.full)
        assertTrue(source.failures.isEmpty())
    }

    @Test
    fun googlesOwnBirthdayEventsAreLeftOutWhileTheContactsAreTheSource() = runBlocking {
        val body = """{"items":[
            {"id":"b1","eventType":"birthday","birthdayProperties":{"contact":"people/c1","type":"birthday"},"summary":"Ana","start":{"date":"2026-03-05"},"end":{"date":"2026-03-06"}},
            {"id":"e1","summary":"Meeting","start":{"date":"2026-03-05"},"end":{"date":"2026-03-06"}}],"nextSyncToken":"S"}"""
        val primary = calendar.copy(remoteId = "primary", accessRole = "OWNER")
        server.enqueue(json(body))
        val off = provider(Source(false)).syncEvents(account, primary)
        assertNotNull(off.events[0].ical)
        assertEquals("birthday", off.events[0].eventType)
        assertEquals("people/c1", off.events[0].birthdayContact)
        server.enqueue(json(body))
        val on = provider(Source(true)).syncEvents(account, primary)
        assertNull(on.events[0].ical)
        assertNotNull(on.events[1].ical)
    }
}
