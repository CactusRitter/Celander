package com.celander.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.celander.app.R
import com.celander.app.calendar.HomeDestination
import com.celander.app.core.model.TaskItem
import com.celander.app.core.ui.Formats
import com.celander.app.settings.AppSettings
import com.celander.app.task.TaskGroup
import com.celander.app.task.TaskGroups
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Tasks widget: open tasks grouped like in the app, checked off right on the home screen. */
class TasksWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = context.widgetDeps()
        WidgetUpdater.scheduleNextDay(context)
        val initial = deps.settings().current()
        WidgetSnapshots.preload(deps, emptyList(), deps.time().zone(), tasks = true)
        provideContent {
            val settings by deps.settings().settings.collectAsState(initial)
            TasksWidgetContent(context, deps, settings)
        }
    }
}

class TasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TasksWidget()
}

private sealed interface TaskRow {
    data class Header(val group: TaskGroup) : TaskRow

    data class Item(val item: TaskItem) : TaskRow

    data object Empty : TaskRow

    /** Tasks past the widget's row limit. */
    data class More(val count: Int) : TaskRow
}

/**
 * Open tasks in the app's groups; of the done ones only today's, so a tap by mistake can be undone.
 * Within the row limit, with a last row for the tasks that did not fit.
 */
private fun taskRows(items: List<TaskItem>, today: LocalDate, zone: ZoneId): List<TaskRow> {
    val recent = items.filter { !it.task.done || it.task.completedAt?.let { at -> Instant.ofEpochMilli(at).atZone(zone).toLocalDate() == today } == true }
    val sections = TaskGroups.build(recent, today)
    val grouped = sections.flatMap { section -> listOf(TaskRow.Header(section.group)) + section.items.map(TaskRow::Item) }
    val rows = if (sections.none { it.group != TaskGroup.DONE }) listOf(TaskRow.Empty) + grouped else grouped
    val capped = capRows(rows, MAX_LIST_ROWS - 1) { it is TaskRow.Item }
    return if (capped.hidden > 0) capped.rows + TaskRow.More(capped.hidden) else capped.rows
}

@Composable
private fun TasksWidgetContent(context: Context, deps: WidgetEntryPoint, settings: AppSettings) {
    val time = deps.time()
    val today = time.today()
    val zone = time.zone()
    val formats = remember(settings.timeFormat) { Formats.of(context, settings.timeFormat) }
    val items = rememberTaskItems(deps)
    val colors = WidgetColors(settings)
    val resources = context.resources
    val open = items?.count { !it.task.done } ?: 0

    WidgetCard(colors) {
        Column(GlanceModifier.fillMaxSize().padding(horizontal = WidgetTokens.padding, vertical = 6.dp)) {
            WidgetHeader(
                title = resources.getString(R.string.view_tasks),
                subtitle = resources.getQuantityString(R.plurals.widget_tasks_open, open, open),
                colors = colors,
                sideSlots = 1,
                onTitle = actionStartActivity(WidgetIntents.view(context, HomeDestination.TASKS, null)),
                trailing = { WidgetAddButton(resources.getString(R.string.editor_new_task), colors, actionStartActivity(WidgetIntents.newTask(context))) },
            )
            val rows = items?.let { taskRows(it, today, zone) }.orEmpty()
            LazyColumn(GlanceModifier.fillMaxSize()) {
                items(rows, itemId = { row ->
                    when (row) {
                        is TaskRow.Header -> -2L - row.group.ordinal
                        is TaskRow.Item -> row.item.task.id
                        TaskRow.Empty -> -1L
                        is TaskRow.More -> MORE_ROW_ID
                    }
                }) { row ->
                    when (row) {
                        is TaskRow.Header -> SectionHeader(
                            resources.getString(groupLabel(row.group)),
                            colors,
                            color = if (row.group == TaskGroup.OVERDUE) colors.danger else colors.ink,
                        )
                        is TaskRow.Item -> TaskLine(
                            context,
                            row.item,
                            colors = colors,
                            formats = formats,
                            due = dueLabel(resources, row.item, today, formats),
                            overdue = !row.item.task.done && row.item.task.dueDate?.isBefore(today) == true,
                        )
                        TaskRow.Empty -> Text(
                            resources.getString(R.string.widget_tasks_empty),
                            style = TextStyle(color = colors.secondary, fontSize = WidgetTokens.bodySize),
                            modifier = GlanceModifier.padding(vertical = 8.dp),
                        )
                        is TaskRow.More -> MoreLine(
                            resources.getQuantityString(R.plurals.widget_more, row.count, row.count),
                            colors,
                            actionStartActivity(WidgetIntents.view(context, HomeDestination.TASKS, null)),
                        )
                    }
                }
            }
        }
    }
}

private fun groupLabel(group: TaskGroup): Int = when (group) {
    TaskGroup.OVERDUE -> R.string.tasks_group_overdue
    TaskGroup.TODAY -> R.string.tasks_group_today
    TaskGroup.UPCOMING -> R.string.tasks_group_upcoming
    TaskGroup.NO_DATE -> R.string.tasks_group_no_date
    TaskGroup.DONE -> R.string.widget_tasks_done_today
}
