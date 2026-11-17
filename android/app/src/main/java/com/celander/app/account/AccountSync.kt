package com.celander.app.account

import androidx.room.withTransaction
import com.celander.app.core.database.AccountDao
import com.celander.app.core.database.AccountEntity
import com.celander.app.core.database.CalendarDao
import com.celander.app.core.database.CalendarEntity
import com.celander.app.core.database.CelanderDatabase
import com.celander.app.core.database.EventDao
import com.celander.app.core.database.EventLinkEntity
import com.celander.app.core.database.ExceptionDao
import com.celander.app.core.database.ExceptionLinkEntity
import com.celander.app.core.database.RemoteCalendarEntity
import com.celander.app.core.database.toEntity
import com.celander.app.core.database.toModel
import com.celander.app.core.model.AccessRole
import com.celander.app.event.zone
import com.celander.app.core.ical.ICalEvent
import com.celander.app.core.ical.IcsMapper
import com.celander.app.core.model.AccountStatus
import com.celander.app.core.model.CalendarSource
import com.celander.app.core.model.SyncState
import com.celander.app.ui.CalendarColor

/** How a sync of an account ended. */
sealed interface SyncOutcome {
    data object Done : SyncOutcome

    /** The sign-in is no longer valid: nothing was changed, the user has to sign in again. */
    data object NeedsSignIn : SyncOutcome

    data class Failed(val reason: String) : SyncOutcome
}

/**
 * Brings what a provider knows into the database: the calendars of an account, then the changes of each calendar's
 * events. The screens read only the database, so everything the user sees of an account comes through here.
 * Local changes that have not reached the server (links not [SyncState.CLEAN]) are never overwritten.
 */
class AccountSync(
    private val db: CelanderDatabase,
    private val accountDao: AccountDao,
    private val calendarDao: CalendarDao,
    private val eventDao: EventDao,
    private val exceptionDao: ExceptionDao,
    private val providers: Map<com.celander.app.core.model.AccountType, CalendarProvider>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun sync(accountId: Long): SyncOutcome {
        val account = accountDao.account(accountId) ?: return SyncOutcome.Failed("no such account")
        val provider = providers[com.celander.app.core.model.AccountType.valueOf(account.type)] ?: return SyncOutcome.Failed("no provider")
        return try {
            val calendars = reconcileCalendars(account, provider)
            for (calendar in calendars) {
                push(account, calendar, provider)
                // Pulled after sending, so the server's answer to what was just sent is what ends up stored.
                pull(account, calendar, provider)
            }
            accountDao.updateAccount(account.copy(status = AccountStatus.OK.name, lastSyncAt = now(), lastError = null))
            SyncOutcome.Done
        } catch (_: NeedsSignInException) {
            accountDao.updateAccount(account.copy(status = AccountStatus.NEEDS_SIGN_IN.name))
            SyncOutcome.NeedsSignIn
        } catch (e: Exception) {
            accountDao.updateAccount(account.copy(status = AccountStatus.ERROR.name, lastError = e.message?.take(200)))
            SyncOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Makes the account's calendars in the database match the provider's list; returns them. */
    private suspend fun reconcileCalendars(account: AccountEntity, provider: CalendarProvider): List<RemoteCalendarEntity> {
        val listed = provider.listCalendars(account)
        return db.withTransaction {
            val known = accountDao.remoteCalendars(account.id).associateBy { it.remoteId }
            listed.forEach { remote ->
                val existing = known[remote.remoteId]
                if (existing == null) {
                    val calendarId = calendarDao.insert(
                        CalendarEntity(
                            name = remote.name,
                            colorKey = colorKey(remote.color),
                            visible = remote.selected,
                            sortOrder = calendarDao.maxSortOrder() + 1,
                            createdAt = now(),
                            source = CalendarSource.ACCOUNT.name,
                        ),
                    )
                    accountDao.upsertRemoteCalendar(
                        RemoteCalendarEntity(
                            calendarId, account.id, remote.remoteId, remote.accessRole.name, syncToken = null,
                            timeZone = remote.timeZone, remoteColor = remote.color, defaultReminders = remote.defaultReminders,
                        ),
                    )
                } else {
                    // The name follows the server (the user renames it there); color and visibility are the user's own here.
                    calendarDao.getById(existing.calendarId)?.let { calendarDao.update(it.copy(name = remote.name)) }
                    accountDao.upsertRemoteCalendar(
                        existing.copy(
                            accessRole = remote.accessRole.name, timeZone = remote.timeZone, remoteColor = remote.color,
                            defaultReminders = remote.defaultReminders,
                        ),
                    )
                }
            }
            val listedIds = listed.map { it.remoteId }.toSet()
            known.values.filter { it.remoteId !in listedIds }.forEach { gone ->
                // Removed at the server (or no longer shared with the user): it goes with its events.
                accountDao.eventLinks(gone.calendarId).forEach { accountDao.deleteEventLink(it.eventId) }
                accountDao.deleteExceptionLinksOfCalendar(gone.calendarId)
                calendarDao.delete(gone.calendarId)
            }
            accountDao.remoteCalendars(account.id)
        }
    }

    private fun colorKey(hex: String?): String {
        val argb = hex?.removePrefix("#")?.toLongOrNull(16)?.toInt()?.let { 0xFF000000.toInt() or it } ?: return CalendarColor.DEFAULT.key
        return CalendarColor.nearest(argb).key
    }

    /**
     * Sends what changed here since the last sync (the links that are not [SyncState.CLEAN]): new events are created,
     * changed ones updated, deleted ones deleted. A change that fails stays pending and is tried again at the next sync.
     * If the server's version moved on in the meantime, the change made here wins: this is one person's calendar.
     */
    private suspend fun push(account: AccountEntity, calendar: RemoteCalendarEntity, provider: CalendarProvider) {
        if (!provider.capabilities.canWrite || !AccessRole.valueOf(calendar.accessRole).canWrite) return
        for (link in accountDao.pendingEventLinks(calendar.calendarId)) {
            try {
                pushOne(account, calendar, provider, link)
            } catch (e: NeedsSignInException) {
                throw e
            } catch (e: Exception) {
                accountDao.upsertEventLink(link.copy(lastError = e.message?.take(200)))
            }
        }
    }

    private suspend fun pushOne(account: AccountEntity, calendar: RemoteCalendarEntity, provider: CalendarProvider, link: EventLinkEntity) {
        if (link.syncState == SyncState.DELETED.name) {
            link.remoteId?.let { provider.delete(account, calendar, it, link.etag) }
            accountDao.deleteEventLink(link.eventId)
            accountDao.deleteExceptionLinks(link.eventId)
            return
        }
        val entity = eventDao.getById(link.eventId)
        if (entity == null) {
            accountDao.deleteEventLink(link.eventId)
            return
        }
        val event = entity.toModel()
        val exceptions = exceptionDao.getForEvent(entity.id).map { it.toModel(event.zone) }
        val parts = IcsMapper.toIcal(event, exceptions)
        val master = parts.first()
        val write = if (link.remoteId == null) {
            provider.create(account, calendar, master, event.reminders)
        } else {
            try {
                provider.update(account, calendar, link.remoteId, master, event.reminders, link.etag)
            } catch (_: ConflictException) {
                provider.update(account, calendar, link.remoteId, master, event.reminders, null)
            }
        }
        // The server gives a series its own UID; the occurrences that come back later are matched by it.
        if (write.uid != null && write.uid != entity.uid) eventDao.update(entity.copy(uid = write.uid))
        for (occurrence in parts.drop(1)) {
            val done = provider.writeOccurrence(account, calendar, write.remoteId, occurrence.copy(uid = write.uid ?: occurrence.uid), event.reminders)
            val original = exceptions.firstOrNull { it.originalStart == keyOf(event, occurrence.recurrenceId) }?.originalStart ?: continue
            val existing = accountDao.exceptionLink(entity.id, original)
            accountDao.upsertExceptionLink(ExceptionLinkEntity(existing?.id ?: 0, entity.id, original, done.remoteId, done.etag, SyncState.CLEAN.name))
        }
        accountDao.upsertEventLink(
            link.copy(remoteId = write.remoteId, etag = write.etag, remoteUpdated = write.updated, syncState = SyncState.CLEAN.name, lastError = null),
        )
    }

    /** The key of an occurrence (millis, or epoch day for all-day series) from an iCalendar recurrence id in millis. */
    private fun keyOf(event: com.celander.app.core.model.Event, recurrenceId: Long?): Long? =
        recurrenceId?.let { if (event.allDay) Math.floorDiv(it, 86_400_000L) else it }

    private suspend fun pull(account: AccountEntity, calendar: RemoteCalendarEntity, provider: CalendarProvider) {
        val result = provider.syncEvents(account, calendar)
        db.withTransaction {
            apply(calendar, result)
            accountDao.upsertRemoteCalendar(
                calendar.copy(syncToken = result.nextToken, lastFullSyncAt = if (result.full) now() else calendar.lastFullSyncAt),
            )
        }
    }

    private suspend fun apply(calendar: RemoteCalendarEntity, result: EventSyncResult) {
        val calendarId = calendar.calendarId
        val seen = mutableSetOf<String>()
        val (series, instances) = result.events.partition { it.ical?.recurrenceId == null }
        for (remote in series) {
            seen += remote.remoteId
            val ical = remote.ical
            if (ical == null || ical.cancelled) delete(calendarId, remote.remoteId) else applySeries(calendar, remote, ical)
        }
        for (remote in instances) {
            seen += remote.remoteId
            remote.ical?.let { applyInstance(calendarId, remote, it) }
        }
        if (result.full) removeUnseen(calendarId, seen)
    }

    private suspend fun delete(calendarId: Long, remoteId: String) {
        val link = accountDao.eventLinkByRemoteId(calendarId, remoteId) ?: return
        // A local edit that was not sent yet would be lost with the event; the user's change wins and is sent again.
        if (link.syncState != SyncState.CLEAN.name) return
        eventDao.delete(link.eventId)
        accountDao.deleteExceptionLinks(link.eventId)
        accountDao.deleteEventLink(link.eventId)
    }

    private suspend fun applySeries(calendar: RemoteCalendarEntity, remote: RemoteEvent, ical: ICalEvent) {
        val calendarId = calendar.calendarId
        val link = accountDao.eventLinkByRemoteId(calendarId, remote.remoteId)
        if (link != null && link.syncState != SyncState.CLEAN.name) {
            // Changed here and not sent yet: keep the local version, but remember the server's version so the send replaces it.
            accountDao.upsertEventLink(link.copy(etag = remote.etag, remoteUpdated = remote.updated))
            return
        }
        val (parsed, exdateExceptions) = IcsMapper.fromIcal(listOf(ical), calendarId).firstOrNull() ?: return
        val event = parsed.copy(reminders = remote.reminders ?: calendar.defaultReminders, updatedAt = remote.updated)
        val eventId = if (link == null) {
            eventDao.insert(event.copy(createdAt = now()).toEntity())
        } else {
            val current = eventDao.getById(link.eventId)
            eventDao.update(event.copy(id = link.eventId, createdAt = current?.createdAt ?: now()).toEntity())
            link.eventId
        }
        // Occurrences cancelled with EXDATE are rebuilt from the series; those changed or cancelled as separate
        // occurrences have their own link and stay.
        exceptionDao.getForEvent(eventId).filter { accountDao.exceptionLink(eventId, it.originalStart) == null }
            .forEach { exceptionDao.delete(eventId, it.originalStart) }
        exdateExceptions.forEach { exceptionDao.upsert(it.copy(eventId = eventId).toEntity()) }
        accountDao.upsertEventLink(
            EventLinkEntity(eventId, calendarId, remote.remoteId, remote.etag, remote.updated, SyncState.CLEAN.name),
        )
    }

    private suspend fun applyInstance(calendarId: Long, remote: RemoteEvent, ical: ICalEvent) {
        val master = accountDao.eventByUid(calendarId, ical.uid) ?: return // its series is not here (yet)
        val model = master.toModel()
        val masterIcal = IcsMapper.toIcal(model, emptyList()).first()
        val exception = IcsMapper.fromIcal(listOf(masterIcal, ical), calendarId).firstOrNull()?.second?.singleOrNull() ?: return
        val link = accountDao.exceptionLink(master.id, exception.originalStart)
        if (link != null && link.syncState != SyncState.CLEAN.name) return
        val masterLink = accountDao.eventLink(master.id)
        exceptionDao.upsert(exception.copy(eventId = master.id).toEntity())
        // The trigger on exceptions marks the series as changed here; this change came from the server.
        masterLink?.let { accountDao.upsertEventLink(it) }
        accountDao.upsertExceptionLink(
            ExceptionLinkEntity(link?.id ?: 0, master.id, exception.originalStart, remote.remoteId, remote.etag, SyncState.CLEAN.name),
        )
    }

    /** After a full fetch: what is in the database but no longer at the server was deleted there. */
    private suspend fun removeUnseen(calendarId: Long, seen: Set<String>) {
        accountDao.eventLinks(calendarId)
            .filter { it.syncState == SyncState.CLEAN.name && it.remoteId != null && it.remoteId !in seen }
            .forEach {
                eventDao.delete(it.eventId)
                accountDao.deleteExceptionLinks(it.eventId)
                accountDao.deleteEventLink(it.eventId)
            }
    }

    /** Removes an account with its calendars, events and links, and what the provider keeps for it. Nothing is sent to the server. */
    suspend fun remove(accountId: Long) {
        val account = accountDao.account(accountId) ?: return
        db.withTransaction {
            accountDao.deleteEventLinksOfAccount(accountId)
            accountDao.deleteExceptionLinksOfAccount(accountId)
            accountDao.deleteCalendarsOfAccount(accountId)
            accountDao.deleteAccount(accountId)
        }
        providers[com.celander.app.core.model.AccountType.valueOf(account.type)]?.signOut(account)
    }

}
