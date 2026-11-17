package com.celander.app.account

import android.content.Context
import com.celander.app.account.auth.GoogleAuth
import com.celander.app.account.auth.TokenStore
import com.celander.app.account.google.GoogleCalendarApi
import com.celander.app.account.google.GoogleCalendarProvider
import com.celander.app.core.database.CelanderDatabase
import com.celander.app.core.di.IoDispatcher
import com.celander.app.core.model.AccountType
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object AccountModule {
    @Provides @Singleton
    fun tokenStore(@ApplicationContext context: Context) = TokenStore(context)

    @Provides @Singleton
    fun googleAuth(@ApplicationContext context: Context) = GoogleAuth(context)

    @Provides @Singleton
    fun accountSync(
        db: CelanderDatabase,
        client: OkHttpClient,
        tokens: TokenStore,
        google: GoogleAuth,
        @IoDispatcher io: CoroutineDispatcher,
    ): AccountSync = AccountSync(
        db, db.accountDao(), db.calendarDao(), db.eventDao(), db.exceptionDao(),
        providers = mapOf(AccountType.GOOGLE to GoogleCalendarProvider(GoogleCalendarApi(client, io), AccountTokens(tokens, google))),
    )
}
