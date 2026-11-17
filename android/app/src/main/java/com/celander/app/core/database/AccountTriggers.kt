package com.celander.app.core.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Marks local changes to the events of synced calendars as waiting to be sent, whoever makes them (the editor, a
 * series split, undo): triggers on `events` and `event_exceptions` keep `event_links` up to date, so no write
 * path can forget it. They only act for calendars that have a row in `remote_calendars`.
 *
 * The sync itself writes to the same tables; it puts the links back right after (see AccountSync).
 * Created every time the database opens: `IF NOT EXISTS` makes that harmless, and no migration is needed.
 */
object AccountTriggers {
    private val STATEMENTS = listOf(
        // A new event in a synced calendar waits to be created at the server; one that comes back (undo of a deletion) is changed again.
        """
        CREATE TRIGGER IF NOT EXISTS account_event_insert AFTER INSERT ON events
        WHEN EXISTS (SELECT 1 FROM remote_calendars WHERE calendarId = NEW.calendarId)
        BEGIN
            INSERT OR IGNORE INTO event_links (eventId, calendarId, remoteId, etag, remoteUpdated, syncState)
                VALUES (NEW.id, NEW.calendarId, NULL, NULL, 0, 'NEW');
            UPDATE event_links SET syncState = 'DIRTY' WHERE eventId = NEW.id AND syncState = 'DELETED';
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS account_event_update AFTER UPDATE ON events
        BEGIN
            UPDATE event_links SET syncState = 'DIRTY' WHERE eventId = NEW.id AND syncState = 'CLEAN';
        END
        """,
        // A deleted event that exists at the server leaves a tombstone; one that never reached it just disappears.
        """
        CREATE TRIGGER IF NOT EXISTS account_event_delete AFTER DELETE ON events
        BEGIN
            UPDATE event_links SET syncState = 'DELETED' WHERE eventId = OLD.id AND remoteId IS NOT NULL;
            DELETE FROM event_links WHERE eventId = OLD.id AND remoteId IS NULL;
        END
        """,
        // A changed, cancelled or restored occurrence changes the series.
        """
        CREATE TRIGGER IF NOT EXISTS account_exception_insert AFTER INSERT ON event_exceptions
        BEGIN
            UPDATE event_links SET syncState = 'DIRTY' WHERE eventId = NEW.eventId AND syncState = 'CLEAN';
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS account_exception_update AFTER UPDATE ON event_exceptions
        BEGIN
            UPDATE event_links SET syncState = 'DIRTY' WHERE eventId = NEW.eventId AND syncState = 'CLEAN';
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS account_exception_delete AFTER DELETE ON event_exceptions
        BEGIN
            UPDATE event_links SET syncState = 'DIRTY' WHERE eventId = OLD.eventId AND syncState = 'CLEAN';
        END
        """,
    )

    /** For the database builder: installs the triggers whenever the database opens. */
    val callback = object : androidx.room.RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) = install(db)
    }

    fun install(db: SupportSQLiteDatabase) {
        STATEMENTS.forEach { db.execSQL(it.trimIndent()) }
    }
}
