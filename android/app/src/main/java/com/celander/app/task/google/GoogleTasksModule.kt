package com.celander.app.task.google

import com.celander.app.account.AccountTokens
import com.celander.app.account.auth.GoogleAuth
import com.celander.app.account.auth.TokenStore
import com.celander.app.core.database.AccountDao
import com.celander.app.core.di.IoDispatcher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
abstract class GoogleTasksModule {
    @Binds
    abstract fun scheduler(impl: WorkManagerTaskSyncScheduler): TaskSyncScheduler

    companion object {
        @Provides
        @Singleton
        fun authorizer(accounts: AccountDao, store: TokenStore, google: GoogleAuth): GoogleAuthorizer =
            AccountAuthorizer(accounts, AccountTokens(store, google))

        @Provides
        @Singleton
        fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        @Provides
        @Singleton
        fun tasksRemote(client: OkHttpClient, @IoDispatcher io: CoroutineDispatcher): TasksRemote = OkHttpTasksRemote(client, io)
    }
}
