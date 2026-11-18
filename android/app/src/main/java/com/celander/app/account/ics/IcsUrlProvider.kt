package com.celander.app.account.ics

import com.celander.app.account.CalendarProvider
import com.celander.app.account.EventSyncResult
import com.celander.app.account.ProviderCapabilities
import com.celander.app.account.RemoteCalendar
import com.celander.app.account.RemoteEvent
import com.celander.app.core.database.AccountEntity
import com.celander.app.core.database.RemoteCalendarEntity
import com.celander.app.core.ical.ICalCalendar
import com.celander.app.core.ical.IcsParser
import com.celander.app.core.model.AccessRole
import com.celander.app.core.model.AccountType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** The feed could not be read: [code] is the HTTP status, or 0 when what came back is not an iCalendar file. */
class IcsFeedException(val code: Int) : java.io.IOException(if (code == 0) "not an iCalendar file" else "HTTP $code")

/**
 * [CalendarProvider] for a calendar published as an .ics link (a subscription): one calendar, read only. Every sync downloads the
 * whole file; what is no longer in it goes away ([EventSyncResult.full]). Nothing is sent anywhere, and no sign-in is needed.
 */
class IcsUrlProvider(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher,
    private val now: () -> Long = System::currentTimeMillis,
) : CalendarProvider {
    override val type = AccountType.ICS_URL

    override val capabilities = ProviderCapabilities(canWrite = false, maxReminders = 1, hasCalendarList = false)

    override suspend fun listCalendars(account: AccountEntity): List<RemoteCalendar> =
        listOf(
            RemoteCalendar(
                remoteId = account.identifier, name = account.displayName, color = null, accessRole = AccessRole.READER,
                isPrimary = false, selected = true, defaultReminders = emptyList(), timeZone = null,
            ),
        )

    override suspend fun syncEvents(account: AccountEntity, calendar: RemoteCalendarEntity): EventSyncResult {
        val feed = fetch(account.identifier)
        val at = now()
        val events = feed.events.map { ical ->
            // A series and its changed occurrences share the UID; each needs an id of its own.
            val id = if (ical.recurrenceId == null) ical.uid else "${ical.uid}#${ical.recurrenceId}"
            RemoteEvent(id, etag = null, updated = at, ical = ical.copy(extra = null), reminders = listOfNotNull(ical.reminderMinutes))
        }
        return EventSyncResult(events, nextToken = null, full = true)
    }

    /** Downloads and reads the feed at [url] (`webcal://` is `https://`); throws [IcsFeedException] when it is not one. */
    suspend fun fetch(url: String): ICalCalendar = withContext(io) {
        val request = Request.Builder().url(normalize(url)).header("Accept", "text/calendar, */*").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IcsFeedException(response.code)
            val text = response.body?.string().orEmpty()
            if (!text.contains("BEGIN:VCALENDAR", ignoreCase = true)) throw IcsFeedException(0)
            IcsParser.parseCalendar(text)
        }
    }

    companion object {
        /** The address as OkHttp wants it: `webcal://` and `webcals://` are `https://`. */
        fun normalize(url: String): String {
            val trimmed = url.trim()
            return when {
                trimmed.startsWith("webcal://", ignoreCase = true) -> "https://" + trimmed.substring(9)
                trimmed.startsWith("webcals://", ignoreCase = true) -> "https://" + trimmed.substring(10)
                else -> trimmed
            }
        }
    }
}
