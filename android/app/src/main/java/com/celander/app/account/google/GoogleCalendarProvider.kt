package com.celander.app.account.google

import com.celander.app.account.CalendarProvider
import com.celander.app.account.ConflictException
import com.celander.app.account.EventSyncResult
import com.celander.app.account.NeedsSignInException
import com.celander.app.account.ProviderCapabilities
import com.celander.app.account.RemoteCalendar
import com.celander.app.account.RemoteEvent
import com.celander.app.account.RemoteWrite
import com.celander.app.core.ical.ICalEvent
import kotlinx.serialization.json.JsonObject
import com.celander.app.core.database.AccountEntity
import com.celander.app.core.database.RemoteCalendarEntity
import com.celander.app.core.model.AccountType
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Gives a valid access token of an account; [forceRefresh] after the server refused the last one. */
fun interface AccessTokens {
    suspend fun token(account: AccountEntity, forceRefresh: Boolean): String?
}

/** [CalendarProvider] for Google accounts, over the Calendar API. Reading only for now. */
class GoogleCalendarProvider(
    private val api: GoogleCalendarApi,
    private val tokens: AccessTokens,
    private val now: () -> Instant = Instant::now,
) : CalendarProvider {
    override val type = AccountType.GOOGLE

    override val capabilities = ProviderCapabilities(canWrite = true, maxReminders = null, hasCalendarList = true)

    override suspend fun listCalendars(account: AccountEntity): List<RemoteCalendar> =
        authorized(account) { api.calendarList(it) }.mapNotNull(GoogleCalendarMapper::calendar)

    override suspend fun syncEvents(account: AccountEntity, calendar: RemoteCalendarEntity): EventSyncResult {
        val withToken = calendar.syncToken
        return try {
            fetch(account, calendar, withToken).copy(full = withToken == null)
        } catch (_: SyncTokenGoneException) {
            // The token expired (Google keeps them for a limited time): start over with the whole calendar.
            fetch(account, calendar, null).copy(full = true)
        }
    }

    private suspend fun fetch(account: AccountEntity, calendar: RemoteCalendarEntity, syncToken: String?): EventSyncResult {
        val events = mutableListOf<RemoteEvent>()
        var page: String? = null
        var next: String?
        // Without a token, events that ended long ago are left out; the token that comes back stays within that.
        val timeMin = if (syncToken == null) now().minus(HISTORY_DAYS, ChronoUnit.DAYS).toString() else null
        do {
            val result = authorized(account) { api.events(it, calendar.remoteId, syncToken, timeMin, page) }
            result.items.mapNotNullTo(events) { GoogleCalendarMapper.event(it, calendar.timeZone) }
            page = result.nextPageToken
            next = result.nextSyncToken
        } while (page != null)
        return EventSyncResult(events, next, full = syncToken == null)
    }

    override suspend fun create(account: AccountEntity, calendar: RemoteCalendarEntity, master: ICalEvent, reminders: List<Int>): RemoteWrite {
        val body = GoogleCalendarMapper.toJson(master, reminders)
        return written(authorized(account) { api.insert(it, calendar.remoteId, body) })
    }

    override suspend fun update(
        account: AccountEntity,
        calendar: RemoteCalendarEntity,
        remoteId: String,
        master: ICalEvent,
        reminders: List<Int>,
        etag: String?,
    ): RemoteWrite {
        val body = GoogleCalendarMapper.toJson(master, reminders)
        return try {
            written(authorized(account) { api.patch(it, calendar.remoteId, remoteId, body, etag) })
        } catch (_: PreconditionFailedException) {
            throw ConflictException()
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
        val id = GoogleCalendarMapper.instanceId(masterRemoteId, original, occurrence.recurrenceAllDay ?: occurrence.allDay)
        val body = GoogleCalendarMapper.toJson(occurrence, reminders)
        return written(authorized(account) { api.patch(it, calendar.remoteId, id, body, null) })
    }

    override suspend fun delete(account: AccountEntity, calendar: RemoteCalendarEntity, remoteId: String, etag: String?) {
        authorized(account) { api.delete(it, calendar.remoteId, remoteId, null) }
    }

    private fun written(json: JsonObject): RemoteWrite = GoogleCalendarMapper.written(json) ?: throw java.io.IOException("no id in the answer")

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
        /** How far back the first sync of a calendar reaches. */
        const val HISTORY_DAYS = 730L
    }
}
