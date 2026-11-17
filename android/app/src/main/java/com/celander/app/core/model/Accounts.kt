package com.celander.app.core.model

/** The kinds of account (or feed) whose calendars Celander keeps in its own database. */
enum class AccountType {
    GOOGLE,
    MICROSOFT,

    /** A calendar published as an .ics link: read only, fetched again every so often. */
    ICS_URL,
}

/** How the last contact with an account went. */
enum class AccountStatus {
    OK,

    /** The sign-in no longer works (password changed, access withdrawn): the user must sign in again. */
    NEEDS_SIGN_IN,

    /** The last sync failed for another reason (network, server); it is tried again. */
    ERROR,
}

/** What a calendar of an account lets the user do with it, from the provider's own roles. */
enum class AccessRole {
    OWNER,
    WRITER,
    READER,
    FREE_BUSY,
    ;

    val canWrite: Boolean get() = this == OWNER || this == WRITER
}

/**
 * Where a synced event stands against its server. [NEW] and [DIRTY] wait to be sent, [DELETED] is a
 * tombstone for a deletion that still has to reach the server; [CLEAN] agrees with it.
 */
enum class SyncState { CLEAN, NEW, DIRTY, DELETED }
