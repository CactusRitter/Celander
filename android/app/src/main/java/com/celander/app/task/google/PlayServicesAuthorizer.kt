package com.celander.app.task.google

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import com.celander.app.core.di.IoDispatcher
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** [GoogleAuthorizer] over Play services' AuthorizationClient (Identity API). */
@Singleton
class PlayServicesAuthorizer @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : GoogleAuthorizer {
    override fun playServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    override fun chooseAccountIntent(preselect: String?): Intent = AccountManager.newChooseAccountIntent(
        preselect?.let { Account(it, GoogleAuthorizer.ACCOUNT_TYPE) },
        null,
        arrayOf(GoogleAuthorizer.ACCOUNT_TYPE),
        null,
        null,
        null,
        null,
    )

    override fun accountFromChooser(resultCode: Int, data: Intent?): String? =
        if (resultCode == Activity.RESULT_OK) data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) else null

    override suspend fun authorize(activity: Activity, email: String): AuthResult =
        request(Identity.getAuthorizationClient(activity), email, interactive = true)

    override fun consentResult(resultCode: Int, data: Intent?): AuthResult {
        if (resultCode != Activity.RESULT_OK) return AuthResult.Cancelled
        return try {
            granted(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data))
        } catch (e: ApiException) {
            failure(e)
        }
    }

    override suspend fun backgroundToken(email: String): AuthResult =
        request(Identity.getAuthorizationClient(context), email, interactive = false)

    override suspend fun clearToken(token: String) = withContext(io) {
        try {
            GoogleAuthUtil.clearToken(context, token)
        } catch (_: GoogleAuthException) {
            // An unknown token is already gone from the cache.
        } catch (_: IOException) {
            // Offline: the next authorization still asks Google, which rejects the stale token again.
        }
    }

    private suspend fun request(client: AuthorizationClient, email: String, interactive: Boolean): AuthResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GoogleAuthorizer.TASKS_SCOPE)))
            .setAccount(Account(email, GoogleAuthorizer.ACCOUNT_TYPE))
            .build()
        return try {
            val result = client.authorize(request).await()
            if (result.hasResolution()) AuthResult.NeedsConsent(if (interactive) result.pendingIntent else null) else granted(result)
        } catch (e: ApiException) {
            failure(e)
        } catch (_: IOException) {
            AuthResult.Failed(GoogleTasksStatus.Offline)
        }
    }

    private fun granted(result: AuthorizationResult): AuthResult =
        result.accessToken?.let { AuthResult.Granted(it) } ?: AuthResult.Failed(GoogleTasksStatus.NeedsReauth)

    private fun failure(e: ApiException): AuthResult = when (e.statusCode) {
        CommonStatusCodes.CANCELED -> AuthResult.Cancelled
        CommonStatusCodes.DEVELOPER_ERROR -> AuthResult.Failed(GoogleTasksStatus.SetupError)
        CommonStatusCodes.NETWORK_ERROR -> AuthResult.Failed(GoogleTasksStatus.Offline)
        CommonStatusCodes.SIGN_IN_REQUIRED -> AuthResult.Failed(GoogleTasksStatus.NeedsReauth)
        else -> AuthResult.Failed(GoogleTasksStatus.Failed(e.statusCode))
    }
}
