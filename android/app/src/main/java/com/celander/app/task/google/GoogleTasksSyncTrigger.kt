package com.celander.app.task.google

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.celander.app.core.database.GoogleTasksDao
import com.celander.app.core.time.TimeSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Starts background syncs: shortly after local changes and when the app comes to the foreground
 * after a while. Also removes mirrored lists restored from a backup without their account.
 */
@Singleton
class GoogleTasksSyncTrigger @Inject constructor(
    private val accounts: GoogleTasksAccounts,
    private val dao: GoogleTasksDao,
    private val connection: GoogleTasksConnection,
    private val scheduler: TaskSyncScheduler,
    private val time: TimeSource,
) {
    /** Called from `Application.onCreate` on the main thread. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope, lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle) {
        scope.launch {
            connection.purgeAfterRestore()
            combine(accounts.connected, dao.observePendingCount()) { connected, pending -> connected to pending }
                .debounce(DEBOUNCE)
                .distinctUntilChanged()
                .collect { (connected, pending) -> if (connected && pending > 0) scheduler.requestSoon(TaskSyncScheduler.LOCAL_CHANGE_DELAY) }
        }
        lifecycle.addObserver(LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) onForeground() })
    }

    /** One run syncs every account, so the account synced longest ago decides. */
    private fun onForeground() {
        val list = accounts.state.value
        if (list.isEmpty()) return
        val oldest = list.minOf { it.lastSyncAt ?: 0 }
        if (time.now().toEpochMilli() - oldest > FOREGROUND_STALE) scheduler.requestSoon(0)
    }

    companion object {
        const val DEBOUNCE = 2_000L

        /** Coming back to the app syncs when the last sync is older than this. */
        const val FOREGROUND_STALE = 5 * 60 * 1000L
    }
}
