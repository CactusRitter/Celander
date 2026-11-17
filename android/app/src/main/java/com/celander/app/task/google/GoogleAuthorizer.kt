package com.celander.app.task.google

/** Result of asking for a Tasks access token. */
sealed interface AuthResult {
    data class Granted(val token: String) : AuthResult

    /** No token: [GoogleTasksStatus.NeedsReauth] when the user has to sign in again, [GoogleTasksStatus.Offline] when there was no connection. */
    data class Failed(val status: GoogleTasksStatus) : AuthResult
}

/**
 * Access tokens of the Google accounts Celander is signed in to (the same sign-in as the calendars). The
 * sync engine only uses [backgroundToken] and [clearToken].
 */
interface GoogleAuthorizer {
    /** Token for [email], refreshed when about to expire; no account or sign-in means [GoogleTasksStatus.NeedsReauth]. */
    suspend fun backgroundToken(email: String): AuthResult

    /** Drops a token Google rejected (HTTP 401), so the next request fetches a fresh one. */
    suspend fun clearToken(token: String)
}
