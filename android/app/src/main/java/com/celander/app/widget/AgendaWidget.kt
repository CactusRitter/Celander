package com.celander.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.celander.app.R
import com.celander.app.calendar.CalendarWindow
import com.celander.app.calendar.HomeDestination
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.TaskItem
import com.celander.app.core.ui.Formats
import com.celander.app.settings.AppSettings
import java.time.LocalDate
import java.time.ZoneId

/**
 * Agenda widget: like the agenda in the app, every day with entries from today on, under its date,
 * with events and tasks that can be checked off right there.
 */
class AgendaWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = context.widgetDeps()
        WidgetUpdater.scheduleNextDay(context)
        val initial = deps.settings().current()
        val time = deps.time()
        val today = time.today()
        WidgetSnapshots.preload(deps, listOf(today to today.plusMonths(AGENDA_MONTHS)), time.zone(), tasks = true)
        provideContent {
            val settings by deps.settings().settings.collectAsState(initial)
            AgendaWidgetContent(context, deps, settings)
        }
    }
}

class AgendaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgendaWidget()
}

/** One line of the agenda widget. */
private sealed interface AgendaLine {
    data class Header(val date: LocalDate, val isToday: Boolean) : AgendaLine

    data class Event(val occurrence: EventOccurrence, val date: LocalDate) : AgendaLine

    data class Task(val item: TaskItem, val date: LocalDate) : AgendaLine

    data object Empty : AgendaLine

    /** Entries past the widget's row limit, from [date] on. */
    data class More(val count: Int, val date: LocalDate) : AgendaLine
}

/**
 * Today always (with open tasks without a date or past due), then every day with entries until [end];
 * per day untimed entries first, then by start.
 */
private fun agendaLines(window: CalendarWindow, today: LocalDate, end: LocalDate, zone: ZoneId, openTasks: List<TaskItem>): List<AgendaLine> = buildList {
    var date = today
    while (date.isBefore(end)) {
        val day = window.day(date)
        val isToday = date == today
        if (isToday || !day.isEmpty) {
            add(AgendaLine.Header(date, isToday))
            val (untimed, timed) = (day.allDay + day.timed).partition { startOn(it, date, zone) == null }
            untimed.forEach { add(AgendaLine.Event(it, date)) }
            timed.sortedBy { startOn(it, date, zone) }.forEach { add(AgendaLine.Event(it, date)) }
            val tasks = if (isToday) day.tasks + openTasks else day.tasks
            tasks.distinctBy { it.task.id }.forEach { add(AgendaLine.Task(it, date)) }
        }
        date = date.plusDays(1)
    }
    if (none { it !is AgendaLine.Header }) add(AgendaLine.Empty)
}

private fun lineId(line: AgendaLine): Long = when (line) {
    is AgendaLine.Header -> line.date.toEpochDay() * 4
    is AgendaLine.Event -> ((line.occurrence.key + line.date).hashCode().toLong() shl 2) + 1
    is AgendaLine.Task -> ((line.item.task.id * 31 + line.date.toEpochDay()) shl 2) + 2
    AgendaLine.Empty -> -1L
    is AgendaLine.More -> MORE_ROW_ID
}

/** The date a line belongs to; null for the empty note. */
private fun lineDate(line: AgendaLine): LocalDate? = when (line) {
    is AgendaLine.Header -> line.date
    is AgendaLine.Event -> line.date
    is AgendaLine.Task -> line.date
    is AgendaLine.More -> line.date
    AgendaLine.Empty -> null
}

/** [lines] within the row limit, ending in a [AgendaLine.More] when entries did not fit. */
private fun cappedLines(lines: List<AgendaLine>, today: LocalDate): List<AgendaLine> {
    val capped = capRows(lines, MAX_LIST_ROWS - 1) { it is AgendaLine.Event || it is AgendaLine.Task }
    if (capped.hidden == 0) return capped.rows
    val firstHidden = lines.getOrNull(capped.rows.size)?.let(::lineDate) ?: today
    return capped.rows + AgendaLine.More(capped.hidden, firstHidden)
}

@Composable
private fun AgendaWidgetContent(context: Context, deps: WidgetEntryPoint, settings: AppSettings) {
    val time = deps.time()
    val today = time.today()
    val zone = time.zone()
    val formats = remember(settings.timeFormat) { Formats.of(context, settings.timeFormat) }
    val end = today.plusMonths(AGENDA_MONTHS)
    val window = rememberWindow(deps, today, end, zone)
    val openTasks = openUndatedTasks(deps, today)
    val colors = WidgetColors(settings)
    val resources = context.resources

    WidgetCard(colors) {
        Column(GlanceModifier.fillMaxSize().padding(horizontal = WidgetTokens.padding, vertical = 6.dp)) {
            WidgetHeader(
                title = resources.getString(R.string.view_agenda),
                subtitle = formats.capitalize(today.format(formats.monthYear)),
                colors = colors,
                sideSlots = 1,
                onTitle = actionStartActivity(WidgetIntents.view(context, HomeDestination.AGENDA, today)),
                trailing = { WidgetAddButton(resources.getString(R.string.action_new_event), colors, actionStartActivity(WidgetIntents.newEvent(context, today))) },
            )
            val lines = window?.let { cappedLines(agendaLines(it, today, end, zone, openTasks), today) }.orEmpty()
            LazyColumn(GlanceModifier.fillMaxSize()) {
                items(lines, itemId = ::lineId) { line ->
                    when (line) {
                        is AgendaLine.Header -> AgendaDayHeader(context, line, formats, colors, today)
                        is AgendaLine.Event -> EventLine(context, line.occurrence, line.date, formats, zone, colors)
                        is AgendaLine.Task -> TaskLine(context, line.item, colors, formats, showBar = true)
                        AgendaLine.Empty -> Text(
                            resources.getString(R.string.widget_agenda_empty),
                            style = TextStyle(color = colors.secondary, fontSize = WidgetTokens.bodySize),
                            modifier = GlanceModifier.padding(vertical = 6.dp),
                        )
                        is AgendaLine.More -> MoreLine(
                            resources.getQuantityString(R.plurals.widget_more, line.count, line.count),
                            colors,
                            actionStartActivity(WidgetIntents.view(context, HomeDestination.AGENDA, line.date)),
                        )
                    }
                }
            }
        }
    }
}

/** "Today · Saturday, October 24" in the app color for today, the date alone for other days; opens that day. */
@Composable
private fun AgendaDayHeader(context: Context, line: AgendaLine.Header, formats: Formats, colors: WidgetColors, today: LocalDate) {
    val date = formats.capitalize(line.date.format(formats.dayMonthLong))
    Column(
        GlanceModifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)
            .clickable(actionStartActivity(WidgetIntents.view(context, HomeDestination.DAY, line.date))),
    ) {
        if (line.date != today) Box(GlanceModifier.fillMaxWidth().height(1.dp).background(colors.divider)) {}
        Row(GlanceModifier.padding(top = 8.dp)) {
            if (line.isToday) {
                Text(
                    context.getString(R.string.today) + " · ",
                    style = TextStyle(color = colors.accent, fontSize = WidgetTokens.sectionSize, fontWeight = FontWeight.Bold),
                )
            }
            Text(date, style = TextStyle(color = colors.ink, fontSize = WidgetTokens.sectionSize, fontWeight = FontWeight.Bold), maxLines = 1)
        }
    }
}

/** How far ahead the agenda widget looks for entries. */
private const val AGENDA_MONTHS = 3L
