package com.celander.app.task.google

import com.celander.app.account.google.AccessTokens
import com.celander.app.core.database.AccountDao
import com.celander.app.core.model.AccountType
import java.io.IOException
import java.util.Collections

/** [GoogleAuthorizer] over the tokens of the Google accounts in the accounts table. */
class AccountAuthorizer(
    private val accounts: AccountDao,
    private val tokens: AccessTokens,
) : GoogleAuthorizer {
    /** Tokens Google rejected: the next request for the account refreshes instead of reusing them. */
    private val rejected: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    override suspend fun backgroundToken(email: String): AuthResult {
        val account = accounts.account(AccountType.GOOGLE.name, email) ?: return AuthResult.Failed(GoogleTasksStatus.NeedsReauth)
        return try {
            val current = tokens.token(account, forceRefresh = false)
            val token = if (current != null && current in rejected) tokens.token(account, forceRefresh = true) else current
            token?.let { AuthResult.Granted(it) } ?: AuthResult.Failed(GoogleTasksStatus.NeedsReauth)
        } catch (_: IOException) {
            AuthResult.Failed(GoogleTasksStatus.Offline)
        }
    }

    override suspend fun clearToken(token: String) {
        rejected += token
    }
}
