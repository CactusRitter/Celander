package com.celander.app.task.google

import android.content.Context
import com.celander.app.ui.success
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Hilt access for [GoogleTasksSyncWorker], which WorkManager's default factory creates. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface GoogleTasksEntryPoint {
    fun sync(): GoogleTasksSync

    fun scheduler(): TaskSyncScheduler
}

/**
 * Runs [GoogleTasksSync] for every connected account in the background; never shows UI, problems end
 * up in the status of their account. One work for all accounts: the run isolates each account's
 * failures, and only a passing problem (offline, server errors) of any account makes WorkManager retry.
 */
class GoogleTasksSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, GoogleTasksEntryPoint::class.java)
        val outcome = entryPoint.sync().run()
        if (outcome is SyncOutcome.Done) outcome.nextRunIn?.let(entryPoint.scheduler()::requestFollowUp)
        return if (outcome is SyncOutcome.Retry) Result.retry() else Result.success()
    }
}
