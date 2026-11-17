package com.celander.app.task.google

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Background runs of the Google Tasks sync; each run covers every connected account. */
interface TaskSyncScheduler {
    /**
     * Runs the sync once after [delayMillis] when online. A run already waiting is kept, so a burst of
     * local changes leads to a single upload.
     */
    fun requestSoon(delayMillis: Long)

    /** Runs the sync again after [delayMillis], also when called from within a running sync. */
    fun requestFollowUp(delayMillis: Long)

    /** The hourly sync while at least one account is connected. */
    fun schedulePeriodic()

    fun cancelAll()

    companion object {
        /** Longer than the 8 s undo snackbar, so an undone change is never sent. */
        const val LOCAL_CHANGE_DELAY = 10_000L
    }
}

@Singleton
class WorkManagerTaskSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : TaskSyncScheduler {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun requestSoon(delayMillis: Long) = enqueue(delayMillis, ExistingWorkPolicy.KEEP)

    // KEEP would drop a request made while the sync is running; appending runs it afterwards.
    override fun requestFollowUp(delayMillis: Long) = enqueue(delayMillis, ExistingWorkPolicy.APPEND_OR_REPLACE)

    private fun enqueue(delayMillis: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<GoogleTasksSyncWorker>()
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(ONE_TIME, policy, request)
    }

    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<GoogleTasksSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun cancelAll() {
        workManager.cancelUniqueWork(ONE_TIME)
        workManager.cancelUniqueWork(PERIODIC)
    }

    companion object {
        const val ONE_TIME = "google-tasks-sync"
        const val PERIODIC = "google-tasks-periodic"
        private const val BACKOFF_SECONDS = 30L
    }
}
