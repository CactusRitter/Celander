package com.celander.app.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.celander.app.calendar.CalendarWindow
import com.celander.app.core.model.TaskItem
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the widgets read last, by what they asked for. Every data change starts a new widget
 * session, and its first picture would show empty lists until the database answers – seconds on a
 * busy phone. With these the first picture already shows the entries: the widgets read through
 * them, and [preload] fills them before a session composes anything (until then the launcher keeps
 * the old picture).
 */
internal object WidgetSnapshots {
    private data class WindowKey(val start: LocalDate, val endExclusive: LocalDate, val zone: ZoneId)

    private val windows = ConcurrentHashMap<WindowKey, CalendarWindow>()

    @Volatile
    private var taskItems: List<TaskItem>? = null

    fun window(deps: WidgetEntryPoint, start: LocalDate, endExclusive: LocalDate, zone: ZoneId): Flow<CalendarWindow> {
        val key = WindowKey(start, endExclusive, zone)
        return deps.content().window(start, endExclusive, zone).onEach { window ->
            // Pages the widgets no longer show (months paged away from) are not kept forever.
            if (windows.size >= MAX_WINDOWS && !windows.containsKey(key)) windows.clear()
            windows[key] = window
        }
    }

    fun lastWindow(start: LocalDate, endExclusive: LocalDate, zone: ZoneId): CalendarWindow? = windows[WindowKey(start, endExclusive, zone)]

    /** Tasks with their calendars, in the order the repository gives them. */
    fun taskItems(deps: WidgetEntryPoint): Flow<List<TaskItem>> =
        combine(deps.tasks().visibleTasks, deps.calendars().calendars) { tasks, calendars ->
            val byId = calendars.associateBy { it.id }
            tasks.mapNotNull { task -> byId[task.calendarId]?.let { TaskItem(task, it) } }
        }.onEach { taskItems = it }

    fun lastTaskItems(): List<TaskItem>? = taskItems

    /**
     * Reads the windows a widget is about to show (and the tasks, when it shows them) before its
     * session composes. A slow phone calendar does not hold the widget back longer than a few
     * seconds; then it shows what it has and fills in later.
     */
    suspend fun preload(deps: WidgetEntryPoint, ranges: List<Pair<LocalDate, LocalDate>>, zone: ZoneId, tasks: Boolean) {
        withTimeoutOrNull(PRELOAD_MILLIS) {
            ranges.forEach { (start, end) -> window(deps, start, end, zone).first() }
            if (tasks) taskItems(deps).first()
        }
    }

    private const val MAX_WINDOWS = 24
    private const val PRELOAD_MILLIS = 5_000L
}

/** The window from [start] to [endExclusive], starting with what the widget showed last. */
@Composable
internal fun rememberWindow(deps: WidgetEntryPoint, start: LocalDate, endExclusive: LocalDate, zone: ZoneId): CalendarWindow? {
    val flow = remember(start, endExclusive, zone) { WidgetSnapshots.window(deps, start, endExclusive, zone) }
    return flow.collectAsState(WidgetSnapshots.lastWindow(start, endExclusive, zone)).value
}

/** All visible tasks with their calendars, starting with what the widgets showed last. */
@Composable
internal fun rememberTaskItems(deps: WidgetEntryPoint): List<TaskItem>? {
    val flow = remember { WidgetSnapshots.taskItems(deps) }
    return flow.collectAsState(WidgetSnapshots.lastTaskItems()).value
}
