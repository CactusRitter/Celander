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
import com.celander.app.core.model.AccessRole
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
    /** The user's own birthdays from the contacts (People API); null where that is not offered (tests, Tasks). */
    private val birthdays: BirthdaySource? = null,
    private val people: PeopleApi? = null,
    private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
) : CalendarProvider {
    override val type = AccountType.GOOGLE

    override val capabilities = ProviderCapabilities(canWrite = true, maxReminders = null, hasCalendarList = true)

    override suspend fun listCalendars(account: AccountEntity): List<RemoteCalendar> {
        val listed = authorized(account) { api.calendarList(it) }.mapNotNull(GoogleCalendarMapper::calendar)
        val source = birthdays ?: return listed
        if (!source.enabled(account.id)) return listed
        // Not a Google calendar: it lives only here, read only, and is filled from the contacts.
        return listed + RemoteCalendar(
            remoteId = BirthdayEvents.CALENDAR_ID, name = source.calendarName(), color = BirthdayEvents.COLOR, accessRole = AccessRole.READER,
            isPrimary = false, selected = true, defaultReminders = emptyList(), timeZone = null,
        )
    }

    override suspend fun syncEvents(account: AccountEntity, calendar: RemoteCalendarEntity): EventSyncResult {
        if (calendar.remoteId == BirthdayEvents.CALENDAR_ID) return syncBirthdays(account, calendar)
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
            val ownBirthdays = birthdays?.enabled(account.id) == true
            result.items.mapNotNullTo(events) { item ->
                GoogleCalendarMapper.event(item, calendar.timeZone)?.let { event ->
                    // Google's own birthday events would show every contact twice: the contacts are the source here.
                    if (ownBirthdays && event.eventType == "birthday") event.copy(ical = null) else event
                }
            }
            page = result.nextPageToken
            next = result.nextSyncToken
        } while (page != null)
        return EventSyncResult(events, next, full = syncToken == null)
    }

    /**
     * The birthdays calendar: the contacts are read at most once a day (the first time, and after switching it on, straight away).
     * Any problem leaves what is stored as it is and does not stop the other calendars; a refusal by the People API (401, 403,
     * i.e. no permission or the API is not enabled) switches the birthdays off, see [BirthdaySource.failed].
     */
    private suspend fun syncBirthdays(account: AccountEntity, calendar: RemoteCalendarEntity): EventSyncResult {
        val unchanged = EventSyncResult(emptyList(), calendar.syncToken, full = false)
        val source = birthdays ?: return unchanged
        val api = people ?: return unchanged
        val at = now()
        if (calendar.lastFullSyncAt != 0L && at.toEpochMilli() - calendar.lastFullSyncAt < BIRTHDAYS_REFRESH_MILLIS) return unchanged
        return try {
            val contacts = peopleCall(account) { api.birthdays(it) }
            EventSyncResult(BirthdayEvents.build(contacts, at.atZone(zone).toLocalDate(), at.toEpochMilli()), null, full = true)
        } catch (_: TokenRejectedException) {
            source.failed(account.id, 401)
            unchanged
        } catch (e: CalendarApiException) {
            if (e.code == 403 || e.code == 401) source.failed(account.id, e.code)
            unchanged
        } catch (_: java.io.IOException) {
            unchanged // offline or a server problem: tried again at the next sync
        }
    }

    /** Like [authorized], but a token refused twice is an error of the People API (the permission is missing), not a lost sign-in. */
    private suspend fun <T> peopleCall(account: AccountEntity, call: suspend (String) -> T): T {
        val first = tokens.token(account, forceRefresh = false) ?: throw NeedsSignInException()
        return try {
            call(first)
        } catch (_: TokenRejectedException) {
            call(tokens.token(account, forceRefresh = true) ?: throw NeedsSignInException())
        }
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

        /** The contacts' birthdays are read again after this long. */
        const val BIRTHDAYS_REFRESH_MILLIS = 24 * 60 * 60 * 1000L
    }
}
