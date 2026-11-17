package com.celander.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An account or feed whose calendars are synced into the database ([com.celander.app.core.model.AccountType]).
 * [identifier] is the e-mail address of an account or the address of a feed; with the type it is unique.
 * Tokens are not kept here: they live encrypted with the Android Keystore.
 */
@Entity(tableName = "accounts", indices = [Index(value = ["type", "identifier"], unique = true)])
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val identifier: String,
    val displayName: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "OK") val status: String = "OK",
    @ColumnInfo(defaultValue = "0") val lastSyncAt: Long = 0,
    val lastError: String? = null,
)

/**
 * The server side of a calendar of an account: [calendarId] is the row of `calendars` the screens show,
 * [remoteId] the provider's own id. Events of these calendars are stored like local ones, in `events`.
 */
@Entity(
    tableName = "remote_calendars",
    foreignKeys = [
        ForeignKey(entity = CalendarEntity::class, parentColumns = ["id"], childColumns = ["calendarId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["accountId", "remoteId"], unique = true), Index("accountId")],
)
data class RemoteCalendarEntity(
    @PrimaryKey val calendarId: Long,
    val accountId: Long,
    val remoteId: String,
    /** [com.celander.app.core.model.AccessRole] name. */
    val accessRole: String,
    /** The provider's token for "changes since the last sync"; null before the first sync or after it was refused. */
    val syncToken: String?,
    @ColumnInfo(defaultValue = "0") val lastFullSyncAt: Long = 0,
    val timeZone: String? = null,
    /** The calendar's own color at the provider (hex), if it has one. */
    val remoteColor: String? = null,
    /** Reminders (minutes before, comma separated) for events that use the calendar's defaults. */
    @ColumnInfo(defaultValue = "") val defaultReminders: List<Int> = emptyList(),
)

/**
 * Sync state of an event. Deliberately without a foreign key to `events`: a link whose event is gone is
 * the tombstone that makes the next sync delete the event at the server. [remoteId] is null while a new
 * event has not been uploaded yet.
 */
@Entity(
    tableName = "event_links",
    indices = [Index(value = ["calendarId", "remoteId"], unique = true), Index("calendarId")],
)
data class EventLinkEntity(
    @PrimaryKey val eventId: Long,
    val calendarId: Long,
    val remoteId: String?,
    val etag: String?,
    /** The server's last-modified time of the event, in ms. */
    @ColumnInfo(defaultValue = "0") val remoteUpdated: Long = 0,
    /** [com.celander.app.core.model.SyncState] name. */
    val syncState: String,
    val lastError: String? = null,
)

/** Sync state of a changed or cancelled occurrence of a series (the same idea as [EventLinkEntity]). */
@Entity(
    tableName = "exception_links",
    indices = [Index(value = ["eventId", "originalStart"], unique = true)],
)
data class ExceptionLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long,
    val originalStart: Long,
    val remoteId: String?,
    val etag: String?,
    val syncState: String,
)
