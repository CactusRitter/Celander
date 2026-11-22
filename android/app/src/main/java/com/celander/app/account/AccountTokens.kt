package com.celander.app.account

import com.ce.kit.auth.GoogleAuth
import com.ce.kit.auth.MicrosoftAuth
import com.ce.kit.auth.TokenProvider
import com.ce.kit.auth.TokenStore
import com.celander.app.account.google.AccessTokens
import com.celander.app.core.database.AccountEntity
import com.celander.app.core.model.AccountType

/** Gives the access token of an account from the encrypted store, refreshed when it is about to expire (the work is the kit's [TokenProvider]). */
class AccountTokens(
    store: TokenStore,
    private val google: GoogleAuth,
    /** Null where only Google accounts are used (Google Tasks). */
    private val microsoft: MicrosoftAuth? = null,
) : AccessTokens {
    private val provider = TokenProvider(store)

    override suspend fun token(account: AccountEntity, forceRefresh: Boolean): String? =
        provider.accessToken(key(account.id), forceRefresh) { tokens ->
            when (AccountType.valueOf(account.type)) {
                AccountType.GOOGLE -> google.freshAccessToken(tokens)
                AccountType.MICROSOFT -> microsoft?.freshAccessToken(tokens)
                else -> null
            }
        }

    companion object {
        /** The name the account's tokens are kept under. */
        fun key(accountId: Long) = "account.$accountId"
    }
}
