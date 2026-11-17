package com.celander.app.task.google

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent

/** Result of asking Google for a Tasks access token. */
sealed interface AuthResult {
    data class Granted(val token: String) : AuthResult

    /**
     * Google needs the user to pick or confirm the grant. [consent] shows that screen; launch it with
     * `ActivityResultContracts.StartIntentSenderForResult` and pass the result to
     * [GoogleAuthorizer.consentResult]. It is null for background requests, which never show UI.
     */
    data class NeedsConsent(val consent: PendingIntent?) : AuthResult

    /** The user backed out; nothing to report. */
    data object Cancelled : AuthResult

    data class Failed(val status: GoogleTasksStatus) : AuthResult
}

/**
 * Google sign-in for the Tasks scope, per account. A screen drives the connect flow:
 * 1. [playServicesAvailable], else show [GoogleTasksStatus.NoPlayServices];
 * 2. launch [chooseAccountIntent] with `StartActivityForResult` and read [accountFromChooser];
 * 3. [authorize]; on [AuthResult.NeedsConsent] launch its intent and read [consentResult];
 * 4. on [AuthResult.Granted] call `GoogleTasksConnection.connected(email)`.
 * The sync engine only uses [backgroundToken] and [clearToken].
 */
interface GoogleAuthorizer {
    fun playServicesAvailable(): Boolean

    /** The system account chooser limited to Google accounts; needs no GET_ACCOUNTS from API 26. */
    fun chooseAccountIntent(preselect: String?): Intent

    /** The account picked in [chooseAccountIntent], or null when cancelled. */
    fun accountFromChooser(resultCode: Int, data: Intent?): String?

    /** Interactive authorization from an activity; may need consent. */
    suspend fun authorize(activity: Activity, email: String): AuthResult

    /** Reads the result of the consent screen from [AuthResult.NeedsConsent]. */
    fun consentResult(resultCode: Int, data: Intent?): AuthResult

    /** Token for the sync on the application context; consent needed means [GoogleTasksStatus.NeedsReauth]. */
    suspend fun backgroundToken(email: String): AuthResult

    /** Drops a token Google rejected (HTTP 401) from the cache, so the next request fetches a fresh one. */
    suspend fun clearToken(token: String)

    companion object {
        const val TASKS_SCOPE = "https://www.googleapis.com/auth/tasks"
        const val ACCOUNT_TYPE = "com.google"
    }
}
