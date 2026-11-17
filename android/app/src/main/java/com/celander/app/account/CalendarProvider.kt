package com.celander.app.account

import com.celander.app.core.database.AccountEntity
import com.celander.app.core.database.RemoteCalendarEntity
import com.celander.app.core.model.AccessRole
import com.celander.app.core.model.AccountType
import com.celander.app.core.ical.ICalEvent

/**
 * What one kind of account can do. The screens ask this instead of the type, so a provider that cannot
 * do something (an .ics feed cannot write; Microsoft has one reminder per event) simply says so.
 */
data class ProviderCapabilities(
    val canWrite: Boolean,
    /** How many reminders one event can carry; 0 for none, null for any number. */
    val maxReminders: Int?,
    /** Whether the provider lists several calendars per account (an .ics feed is one calendar). */
    val hasCalendarList: Boolean,
)

/** A calendar of an account as the provider lists it. */
data class RemoteCalendar(
    val remoteId: String,
    val name: String,
    /** The calendar's own color as #RRGGBB, if the provider has one. */
    val color: String?,
    val accessRole: AccessRole,
    val isPrimary: Boolean,
    val timeZone: String?,
)

/**
 * An event, or one changed occurrence of a series, as a provider sends it, already in the form of an
 * iCalendar component ([ICalEvent]): every provider converts to that one form, and the app's own converter
 * ([com.celander.app.core.ical.IcsMapper.fromIcal]) turns it into events and exceptions. A series and its
 * changed occurrences share [ICalEvent.uid].
 */
data class RemoteEvent(
    val remoteId: String,
    val etag: String?,
    /** Last modified at the server, in ms. */
    val updated: Long,
    /** Null when the event was deleted at the server (there is nothing left to show). */
    val ical: ICalEvent?,
    /** Minutes before the start, as the event carries them; null when the calendar's defaults apply. */
    val reminders: List<Int>? = null,
)

/** What a sync of one calendar brought. */
data class EventSyncResult(
    val events: List<RemoteEvent>,
    /** Token for the next "changes since" request; null when the provider has none. */
    val nextToken: String?,
    /**
     * The provider refused the old token (or there was none): [events] is everything in the calendar, and
     * what is stored locally but not in it must be removed.
     */
    val full: Boolean,
)

/** What the provider says about an event that was sent. */
data class RemoteWrite(val remoteId: String, val etag: String?, val updated: Long)

/** The server refused a write because the event changed there in the meantime. */
class ConflictException(message: String? = null) : Exception(message)

/** The sign-in of the account no longer works; the user has to sign in again. */
class NeedsSignInException(message: String? = null) : Exception(message)

/**
 * One kind of account. The rest of the app reads only the database; a provider fills it (see the sync) and
 * sends local changes. Adding a kind of account means writing one of these and registering it, nothing else.
 */
interface CalendarProvider {
    val type: AccountType
    val capabilities: ProviderCapabilities

    /** The calendars of [account]. */
    suspend fun listCalendars(account: AccountEntity): List<RemoteCalendar>

    /**
     * Changes to the events of [calendar] since [calendar]'s sync token, or everything if it has none or the
     * provider refuses it. Throws [NeedsSignInException] when the sign-in is no longer valid.
     */
    suspend fun syncEvents(account: AccountEntity, calendar: RemoteCalendarEntity): EventSyncResult

    /** Sends a new event; throws [UnsupportedOperationException] where [ProviderCapabilities.canWrite] is false. */
    suspend fun create(account: AccountEntity, calendar: RemoteCalendarEntity, event: RemoteEvent): RemoteWrite =
        throw UnsupportedOperationException()

    /** Sends a change; [ConflictException] when the server's version moved on ([etag] no longer matches). */
    suspend fun update(account: AccountEntity, calendar: RemoteCalendarEntity, event: RemoteEvent, etag: String?): RemoteWrite =
        throw UnsupportedOperationException()

    suspend fun delete(account: AccountEntity, calendar: RemoteCalendarEntity, remoteId: String, etag: String?): Unit =
        throw UnsupportedOperationException()

    /** Forgets what the app keeps for [account] (its tokens); the server is not touched. */
    suspend fun signOut(account: AccountEntity) = Unit
}
