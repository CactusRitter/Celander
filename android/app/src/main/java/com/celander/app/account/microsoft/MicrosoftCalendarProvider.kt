package com.celander.app.account.microsoft

import com.celander.app.account.CalendarProvider
import com.celander.app.account.EventSyncResult
import com.celander.app.account.NeedsSignInException
import com.celander.app.account.ProviderCapabilities
import com.celander.app.account.RemoteCalendar
import com.celander.app.account.RemoteEvent
import com.celander.app.account.RemoteWrite
import com.celander.app.account.google.AccessTokens
import com.celander.app.account.google.TokenRejectedException
import com.celander.app.core.database.AccountEntity
import com.celander.app.core.database.RemoteCalendarEntity
import com.celander.app.core.ical.ICalEvent
import com.celander.app.core.model.AccountType
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * [CalendarProvider] for Microsoft accounts (Outlook.com, Hotmail, Microsoft 365) over Graph. A sync reads the whole calendar
 * (Graph's delta for events needs a time window), asks each series for its changed occurrences, and says [EventSyncResult.full].
 * Outlook has one reminder per event.
 *
 * Known gaps, to check against a real account: occurrences cancelled at Outlook do not show up in a series' instances, so they
 * stay visible here; whole-day events are read as the dates Graph sends.
 */
class MicrosoftCalendarProvider(
    private val api: GraphCalendarApi,
    private val tokens: AccessTokens,
    private val now: () -> Instant = Instant::now,
) : CalendarProvider {
    override val type = AccountType.MICROSOFT

    override val capabilities = ProviderCapabilities(canWrite = true, maxReminders = 1, hasCalendarList = true)

    override suspend fun listCalendars(account: AccountEntity): List<RemoteCalendar> =
        authorized(account) { api.calendars(it) }.mapNotNull(GraphCalendarMapper::calendar)

    override suspend fun syncEvents(account: AccountEntity, calendar: RemoteCalendarEntity): EventSyncResult {
        val cutoff = now().minus(HISTORY_DAYS, ChronoUnit.DAYS)
        val events = mutableListOf<RemoteEvent>()
        for (json in authorized(account) { api.events(it, calendar.remoteId) }) {
            val event = GraphCalendarMapper.event(json) ?: continue
            val ical = event.ical
            // Like Google's first sync: single events that ended long ago are left out.
            if (ical != null && ical.rrule == null && ical.end < cutoff.toEpochMilli()) continue
            events += event
            if (ical?.rrule != null) events += exceptionsOf(account, event, ical)
        }
        return EventSyncResult(events, nextToken = null, full = true)
    }

    /** The changed occurrences of a series; each carries the series' UID and the start it replaced. */
    private suspend fun exceptionsOf(account: AccountEntity, series: RemoteEvent, ical: ICalEvent): List<RemoteEvent> {
        val from = now().minus(HISTORY_DAYS, ChronoUnit.DAYS).toString()
        val to = now().plus(FUTURE_DAYS, ChronoUnit.DAYS).toString()
        return authorized(account) { api.instances(it, series.remoteId, from, to) }
            .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "exception" }
            .mapNotNull { GraphCalendarMapper.event(it, seriesUid = ical.uid) }
            .filter { it.ical?.recurrenceId != null }
    }

    override suspend fun create(account: AccountEntity, calendar: RemoteCalendarEntity, master: ICalEvent, reminders: List<Int>): RemoteWrite =
        written(authorized(account) { api.insert(it, calendar.remoteId, GraphCalendarMapper.toJson(master, reminders)) })

    override suspend fun update(
        account: AccountEntity,
        calendar: RemoteCalendarEntity,
        remoteId: String,
        master: ICalEvent,
        reminders: List<Int>,
        etag: String?,
    ): RemoteWrite {
        // Graph is told the change without a version check: what is done here wins (the app's rule for conflicts).
        val result = written(authorized(account) { api.patch(it, remoteId, GraphCalendarMapper.toJson(master, reminders)) })
        if (master.rrule != null && master.exdates.isNotEmpty()) cancelOccurrences(account, remoteId, master.exdates, master.allDay)
        return result
    }

    /** Deletes the occurrences of the series [seriesId] that start at [starts] (one query for all of them). */
    private suspend fun cancelOccurrences(account: AccountEntity, seriesId: String, starts: List<Long>, allDay: Boolean) {
        val from = Instant.ofEpochMilli(starts.min()).minus(1, ChronoUnit.DAYS).toString()
        val to = Instant.ofEpochMilli(starts.max()).plus(2, ChronoUnit.DAYS).toString()
        val instances = authorized(account) { api.instances(it, seriesId, from, to) }
        for (start in starts) {
            val id = instanceId(instances, start, allDay) ?: continue // not there: already cancelled
            authorized(account) { api.delete(it, id) }
        }
    }

    override suspend fun writeOccurrence(
        account: AccountEntity,
        calendar: RemoteCalendarEntity,
        masterRemoteId: String,
        occurrence: ICalEvent,
        reminders: List<Int>,
    ): RemoteWrite {
        val original = occurrence.recurrenceId ?: error("not an occurrence")
        val allDay = occurrence.recurrenceAllDay ?: occurrence.allDay
        val from = Instant.ofEpochMilli(original).minus(1, ChronoUnit.DAYS).toString()
        val to = Instant.ofEpochMilli(original).plus(2, ChronoUnit.DAYS).toString()
        val instances = authorized(account) { api.instances(it, masterRemoteId, from, to) }
        val id = instanceId(instances, original, allDay) ?: throw java.io.IOException("occurrence not found")
        return written(authorized(account) { api.patch(it, id, GraphCalendarMapper.toJson(occurrence, reminders)) })
    }

    override suspend fun delete(account: AccountEntity, calendar: RemoteCalendarEntity, remoteId: String, etag: String?) {
        authorized(account) { api.delete(it, remoteId) }
    }

    /** The id of the instance that replaced [original] (a moment, or a UTC midnight for whole-day series). */
    private fun instanceId(instances: List<JsonObject>, original: Long, allDay: Boolean): String? {
        val tolerance = if (allDay) 12 * HOUR_MS else MINUTE_MS
        return instances.firstOrNull { json ->
            val start = (json["originalStart"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            start != null && Math.abs(start - original) <= tolerance
        }?.let { (it["id"] as? JsonPrimitive)?.contentOrNull }
    }

    private fun written(json: JsonObject): RemoteWrite = GraphCalendarMapper.written(json) ?: throw java.io.IOException("no id in the answer")

    /** Runs [call] with a token; once more with a fresh one if the server refuses it; no token at all means sign in again. */
    private suspend fun <T> authorized(account: AccountEntity, call: suspend (String) -> T): T {
        val first = tokens.token(account, forceRefresh = false) ?: throw NeedsSignInException()
        return try {
            call(first)
        } catch (_: TokenRejectedException) {
            val second = tokens.token(account, forceRefresh = true) ?: throw NeedsSignInException()
            try {
                call(second)
            } catch (_: TokenRejectedException) {
                throw NeedsSignInException("the access token was refused twice")
            }
        }
    }

    private companion object {
        const val HISTORY_DAYS = 730L
        const val FUTURE_DAYS = 1100L
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 3_600_000L
    }
}
