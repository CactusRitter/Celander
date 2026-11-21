package com.celander.app.account

import com.ce.kit.auth.GoogleAuth
import com.ce.kit.auth.MicrosoftAuth
import com.ce.kit.auth.TokenStore
import com.celander.app.account.google.AccessTokens
import com.celander.app.core.database.AccountEntity
import com.celander.app.core.model.AccountType

/** Gives the access token of an account from the encrypted store, refreshed when it is about to expire. */
class AccountTokens(
    private val store: TokenStore,
    private val google: GoogleAuth,
    /** Null where only Google accounts are used (Google Tasks). */
    private val microsoft: MicrosoftAuth? = null,
) : AccessTokens {
    override suspend fun token(account: AccountEntity, forceRefresh: Boolean): String? {
        val current = store.load(key(account.id)) ?: return null
        // After the server refused the token it is thrown away so that a new one is asked for.
        val base = if (forceRefresh) current.copy(accessToken = null) else current
        val fresh = when (AccountType.valueOf(account.type)) {
            AccountType.GOOGLE -> google.freshAccessToken(base)
            AccountType.MICROSOFT -> microsoft?.freshAccessToken(base)
            else -> null
        } ?: return null
        if (fresh !== current) store.save(key(account.id), fresh)
        return fresh.accessToken
    }

    companion object {
        /** The name the account's tokens are kept under. */
        fun key(accountId: Long) = "account.$accountId"
    }
}
