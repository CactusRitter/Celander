package com.celander.app.account

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Background runs of the account sync; each run covers every connected account. */
interface AccountSyncScheduler {
    /** Runs the sync once after [delayMillis] when online. A run already waiting is kept: a burst of changes leads to one run. */
    fun requestSoon(delayMillis: Long)

    /** The sync every [PERIODIC_MINUTES] minutes (the least Android allows) while at least one account is connected. */
    fun schedulePeriodic()

    fun cancelAll()

    companion object {
        /** Short enough to feel immediate, long enough to gather the changes of one edit. */
        const val LOCAL_CHANGE_DELAY = 3_000L
        const val PERIODIC_MINUTES = 15L
    }
}

@Singleton
class WorkManagerAccountSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : AccountSyncScheduler {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun requestSoon(delayMillis: Long) {
        val request = OneTimeWorkRequestBuilder<AccountSyncWorker>()
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.KEEP, request)
    }

    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<AccountSyncWorker>(AccountSyncScheduler.PERIODIC_MINUTES, TimeUnit.MINUTES)
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun cancelAll() {
        workManager.cancelUniqueWork(ONE_TIME)
        workManager.cancelUniqueWork(PERIODIC)
    }

    private companion object {
        const val ONE_TIME = "account_sync_once"
        const val PERIODIC = "account_sync_periodic"
        const val BACKOFF_SECONDS = 30L
    }
}

/** Hilt access for [AccountSyncWorker], which WorkManager's default factory creates. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AccountSyncEntryPoint {
    fun accounts(): AccountRepository
}

/**
 * Syncs every connected account in the background, with the app closed too. Widgets and reminders follow by themselves:
 * they watch the database that the sync writes to. A passing problem (offline, server error) makes WorkManager retry; an
 * account that must sign in again does not (its status says so in Settings).
 */
class AccountSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val accounts = EntryPointAccessors.fromApplication(applicationContext, AccountSyncEntryPoint::class.java).accounts()
        return if (accounts.syncAll()) Result.retry() else Result.success()
    }
}
