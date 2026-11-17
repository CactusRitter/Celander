package com.celander.app.widget

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LocalAppWidgetOptions
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.celander.app.R
import com.celander.app.calendar.CalendarWindow
import com.celander.app.calendar.HomeDestination
import com.celander.app.calendar.components.occurrenceTimeLabel
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.EventTiming
import com.celander.app.core.model.TaskItem
import com.celander.app.core.time.CalendarMath
import com.celander.app.core.ui.Formats
import com.celander.app.settings.AppSettings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Day widget: one day in focus. A week strip to jump between days, for today a large card with the
 * event that is on now or next, then the rest of that day. The agenda widget is the one that runs
 * on through the coming weeks.
 */
class DayWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = context.widgetDeps()
        WidgetUpdater.scheduleNextDay(context)
        val initial = deps.settings().current()
        val time = deps.time()
        val weekStart = CalendarMath.weekStart(time.today(), initial.firstDayOfWeek(Formats.of(context, initial.timeFormat).locale))
        WidgetSnapshots.preload(deps, listOf(weekStart to weekStart.plusDays(7)), time.zone(), tasks = true)
        provideContent {
            val settings by deps.settings().settings.collectAsState(initial)
            DayWidgetContent(context, deps, settings)
        }
    }

    companion object {
        /** The day picked in the week strip; absent means today. */
        val DayKey = stringPreferencesKey("shown_day")
    }
}

class DayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DayWidget()
}

/** Shows another day of the week strip; today clears the choice, so the widget follows the date. */
class SelectDayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val day = parameters[DayKey] ?: return
        val today = context.widgetDeps().time().today().toEpochDay()
        updateAppWidgetState(context, glanceId) { prefs ->
            if (day == today) prefs.remove(DayWidget.DayKey) else prefs[DayWidget.DayKey] = LocalDate.ofEpochDay(day).toString()
        }
        DayWidget().update(context, glanceId)
    }

    companion object {
        val DayKey = ActionParameters.Key<Long>("day")
    }
}

/** Checks a task off (or on again) right from a widget. */
class ToggleTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TaskIdKey] ?: return
        val done = parameters[DoneKey] ?: return
        context.widgetDeps().tasks().setDone(id, done)
        WidgetUpdater.updateAll(context)
    }

    companion object {
        val TaskIdKey = ActionParameters.Key<Long>("task_id")
        val DoneKey = ActionParameters.Key<Boolean>("done")
    }
}

/** One line of the day widget's list. */
private sealed interface DayRow {
    data class Event(val occurrence: EventOccurrence) : DayRow

    data class Task(val item: TaskItem) : DayRow

    data object Nothing : DayRow

    /** Entries past the widget's row limit. */
    data class More(val count: Int) : DayRow
}

/** The event on now, or else the next one to start, among today's timed events. */
internal fun nowOrNext(timed: List<EventOccurrence>, now: Instant): EventOccurrence? =
    timed.filter { (it.timing as? EventTiming.Timed)?.end?.isAfter(now) == true }.minByOrNull { (it.timing as EventTiming.Timed).start }

/** When the card for [timed] changes next: the coming start or end of one of them. */
internal fun nextChange(timed: List<EventOccurrence>, now: Instant): Instant? =
    timed.mapNotNull { it.timing as? EventTiming.Timed }.flatMap { listOf(it.start, it.end) }.filter { it.isAfter(now) }.minOrNull()

/**
 * The day's entries below the card: entries without a start that day (all-day or continuing from
 * before) first, then everything by start time, then tasks; [featured] is already in the card.
 * At most [limit] rows, the last one for the entries that did not fit.
 */
private fun dayRows(window: CalendarWindow, date: LocalDate, zone: ZoneId, openTasks: List<TaskItem>, featured: EventOccurrence?, limit: Int): List<DayRow> {
    val rows = buildList<DayRow> {
        val day = window.day(date)
        val (untimed, timed) = (day.allDay + day.timed).partition { startOn(it, date, zone) == null }
        untimed.forEach { add(DayRow.Event(it)) }
        timed.sortedBy { startOn(it, date, zone) }.filter { it.key != featured?.key }.forEach { add(DayRow.Event(it)) }
        (day.tasks + openTasks).distinctBy { it.task.id }.forEach { add(DayRow.Task(it)) }
        if (isEmpty() && featured == null) add(DayRow.Nothing)
    }
    val capped = capRows(rows, (limit - 1).coerceAtLeast(1)) { it is DayRow.Event || it is DayRow.Task }
    return if (capped.hidden > 0) capped.rows + DayRow.More(capped.hidden) else capped.rows
}

/**
 * Open tasks without a date or past due, which the day and agenda widgets list under today so they
 * can be checked off there (Google Tasks often have no date).
 */
@Composable
internal fun openUndatedTasks(deps: WidgetEntryPoint, today: LocalDate): List<TaskItem> {
    return rememberTaskItems(deps).orEmpty()
        .filter { !it.task.done && (it.task.dueDate == null || it.task.dueDate.isBefore(today)) }
        .sortedWith(compareBy<TaskItem>({ it.task.dueDate == null }, { it.task.dueDate }, { it.task.title.lowercase() }))
}

/** When [occurrence] starts on [date], or null if it is all-day or began earlier. */
internal fun startOn(occurrence: EventOccurrence, date: LocalDate, zone: ZoneId): ZonedDateTime? =
    (occurrence.timing as? EventTiming.Timed)?.takeIf { it.startDate(zone) == date }?.start?.atZone(zone)

@Composable
private fun DayWidgetContent(context: Context, deps: WidgetEntryPoint, settings: AppSettings) {
    val time = deps.time()
    val today = time.today()
    val zone = time.zone()
    val formats = remember(settings.timeFormat) { Formats.of(context, settings.timeFormat) }
    val firstDay = settings.firstDayOfWeek(formats.locale)
    val weekStart = CalendarMath.weekStart(today, firstDay)
    // A day picked in an earlier week (yesterday's choice) falls back to today.
    val shown = currentState(DayWidget.DayKey)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?.takeIf { !it.isBefore(weekStart) && it.isBefore(weekStart.plusDays(7)) } ?: today
    val window = rememberWindow(deps, weekStart, weekStart.plusDays(7), zone)
    val colors = WidgetColors(settings)
    val resources = context.resources

    val openTasks = if (shown == today) openUndatedTasks(deps, today) else emptyList()
    val timedToday = window?.takeIf { shown == today }?.day(today)?.timed.orEmpty()
    val now = time.now()
    val featured = nowOrNext(timedToday, now)
    val change = nextChange(timedToday, now)
    LaunchedEffect(change) { if (change != null) WidgetUpdater.scheduleRefresh(context, change) }

    WidgetCard(colors) {
        Column(GlanceModifier.fillMaxSize().padding(horizontal = WidgetTokens.padding, vertical = 6.dp)) {
            WidgetHeader(
                title = if (shown == today) resources.getString(R.string.today) else formats.capitalize(formats.weekdayLong(shown.dayOfWeek)),
                subtitle = formats.capitalize(shown.format(if (shown == today) formats.dayLong else formats.dayMonthName)),
                colors = colors,
                sideSlots = 1,
                onTitle = actionStartActivity(WidgetIntents.view(context, HomeDestination.DAY, shown)),
                trailing = { WidgetAddButton(resources.getString(R.string.action_new_event), colors, actionStartActivity(WidgetIntents.newEvent(context, shown))) },
            )
            if (LocalSize.current.height >= WEEK_STRIP_MIN_HEIGHT) WeekStrip(weekStart, today, shown, window, formats, colors)
            // The card and the "more" row count against the limit; every reported size gets a list of its own.
            val limit = listRowLimit(reportedSizes(LocalAppWidgetOptions.current).size) - if (featured != null) 1 else 0
            val rows = window?.let { dayRows(it, shown, zone, openTasks, featured, limit) }.orEmpty()
            LazyColumn(GlanceModifier.fillMaxSize()) {
                if (featured != null) item(itemId = FEATURED_ID) { NowOrNextCard(context, featured, today, formats, zone, colors, now) }
                items(rows, itemId = { rowId(it) }) { row ->
                    when (row) {
                        is DayRow.Event -> EventLine(context, row.occurrence, shown, formats, zone, colors)
                        is DayRow.Task -> TaskLine(context, row.item, colors, formats, showBar = true)
                        DayRow.Nothing -> Text(
                            resources.getString(if (shown == today) R.string.widget_day_empty else R.string.widget_day_nothing),
                            style = TextStyle(color = colors.secondary, fontSize = WidgetTokens.bodySize),
                            modifier = GlanceModifier.padding(vertical = 10.dp),
                        )
                        is DayRow.More -> MoreLine(
                            resources.getQuantityString(R.plurals.widget_more, row.count, row.count),
                            colors,
                            actionStartActivity(WidgetIntents.view(context, HomeDestination.DAY, shown)),
                        )
                    }
                }
            }
        }
    }
}

/** The seven days of this week; a tap shows that day, today is marked in the accent color. */
@Composable
private fun WeekStrip(weekStart: LocalDate, today: LocalDate, shown: LocalDate, window: CalendarWindow?, formats: Formats, colors: WidgetColors) {
    Row(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (offset in 0 until 7) {
            val date = weekStart.plusDays(offset.toLong())
            val selected = date == shown
            val busy = window?.day(date)?.isEmpty == false
            val description = formats.capitalize(date.format(formats.dayMonthLong))
            Column(
                // Every cell sets its background: the launcher reuses views, so one left out keeps the old one.
                GlanceModifier.defaultWeight().height(WEEK_DAY_HEIGHT).cornerRadius(12.dp)
                    .background(if (selected) colors.accent else colors.transparent)
                    .clickable(actionRunCallback<SelectDayAction>(actionParametersOf(SelectDayAction.DayKey to date.toEpochDay())))
                    .semantics { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ink = when {
                    selected -> colors.onAccent
                    date == today -> colors.accent
                    else -> colors.ink
                }
                Text(formats.weekdayNarrow(date.dayOfWeek), style = TextStyle(color = if (selected) colors.onAccent else colors.secondary, fontSize = WidgetTokens.smallSize), maxLines = 1)
                Text(date.dayOfMonth.toString(), style = TextStyle(color = ink, fontSize = WidgetTokens.bodySize, fontWeight = FontWeight.Bold), maxLines = 1)
                // A small mark under days with entries, so the strip shows where something is planned.
                Box(GlanceModifier.size(4.dp).cornerRadius(2.dp).background(if (busy) ink else colors.transparent)) {}
            }
        }
    }
}

/** The event on now or next: label, title in large type, time range and place, in its calendar's tint. */
@Composable
private fun NowOrNextCard(context: Context, occurrence: EventOccurrence, today: LocalDate, formats: Formats, zone: ZoneId, colors: WidgetColors, now: Instant) {
    val resources = context.resources
    val timing = occurrence.timing as EventTiming.Timed
    val label = resources.getString(if (timing.start.isAfter(now)) R.string.widget_next else R.string.widget_now)
    val detail = listOf(occurrenceTimeLabel(resources, occurrence, today, formats, zone), occurrence.location).filter { it.isNotBlank() }.joinToString(" · ")
    Box(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            GlanceModifier.fillMaxWidth().cornerRadius(16.dp).background(colors.calendarSoft(occurrence.colorKey)).padding(12.dp)
                .clickable(actionStartActivity(WidgetIntents.event(context, occurrence))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(GlanceModifier.width(WidgetTokens.bar).height(44.dp).background(colors.calendar(occurrence.colorKey)).cornerRadius(2.dp)) {}
            Spacer(GlanceModifier.width(10.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(label, style = TextStyle(color = colors.ink, fontSize = WidgetTokens.smallSize, fontWeight = FontWeight.Bold), maxLines = 1)
                Text(
                    occurrence.title.ifBlank { resources.getString(R.string.untitled) },
                    style = TextStyle(color = colors.ink, fontSize = WidgetTokens.titleSize, fontWeight = FontWeight.Bold),
                    maxLines = 2,
                )
                if (detail.isNotEmpty()) Text(detail, style = TextStyle(color = colors.ink, fontSize = WidgetTokens.smallSize), maxLines = 1)
            }
        }
    }
}

private fun rowId(row: DayRow): Long = when (row) {
    is DayRow.Event -> row.occurrence.key.hashCode().toLong() shl 1
    is DayRow.Task -> (row.item.task.id shl 1) + 1
    DayRow.Nothing -> -1L
    is DayRow.More -> MORE_ROW_ID
}

@Composable
internal fun SectionHeader(text: String, colors: WidgetColors, color: ColorProvider = colors.ink) {
    Column(GlanceModifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
        Box(GlanceModifier.fillMaxWidth().height(1.dp).background(colors.divider)) {}
        Text(
            text,
            style = TextStyle(color = color, fontSize = WidgetTokens.sectionSize, fontWeight = FontWeight.Bold),
            modifier = GlanceModifier.padding(top = 8.dp),
        )
    }
}

/** Event: color bar, bold start time, title and a second line with the time range and place. */
@Composable
internal fun EventLine(context: Context, occurrence: EventOccurrence, date: LocalDate, formats: Formats, zone: ZoneId, colors: WidgetColors) {
    val resources = context.resources
    val timing = occurrence.timing
    val start = startOn(occurrence, date, zone)?.format(formats.time)
    val detail = listOf(if (timing is EventTiming.Timed) occurrenceTimeLabel(resources, occurrence, date, formats, zone) else "", occurrence.location)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    Row(
        GlanceModifier.fillMaxWidth().padding(vertical = 5.dp).clickable(actionStartActivity(WidgetIntents.event(context, occurrence))),
        verticalAlignment = Alignment.Top,
    ) {
        ColorBar(colors.calendar(occurrence.colorKey))
        Spacer(GlanceModifier.width(10.dp))
        if (start != null) TimeColumn(start, colors, formats)
        Column(GlanceModifier.defaultWeight()) {
            Text(occurrence.title.ifBlank { resources.getString(R.string.untitled) }, style = TextStyle(color = colors.ink, fontSize = WidgetTokens.bodySize), maxLines = 1)
            if (detail.isNotEmpty()) Text(detail, style = TextStyle(color = colors.secondary, fontSize = WidgetTokens.smallSize), maxLines = 1)
        }
    }
}

/**
 * Task: check circle in the calendar color (tap toggles) and title. In the day list it starts with
 * the color bar and the time like an event ([showBar]); in the task list it shows [due] below.
 */
@Composable
internal fun TaskLine(
    context: Context,
    item: TaskItem,
    colors: WidgetColors,
    formats: Formats,
    showBar: Boolean = false,
    due: String? = null,
    overdue: Boolean = false,
) {
    val resources = context.resources
    val task = item.task
    val time = task.dueTime?.takeIf { showBar }?.format(formats.time)
    Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showBar) {
            ColorBar(colors.calendar(item.colorKey))
            Spacer(GlanceModifier.width(10.dp))
            if (time != null) TimeColumn(time, colors, formats)
        }
        val toggle = resources.getString(if (task.done) R.string.widget_task_reopen else R.string.widget_task_done, task.title)
        Box(
            GlanceModifier.size(WidgetTokens.checkTarget)
                .clickable(actionRunCallback<ToggleTaskAction>(actionParametersOf(ToggleTaskAction.TaskIdKey to task.id, ToggleTaskAction.DoneKey to !task.done)))
                .semantics { contentDescription = toggle },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                ImageProvider(if (task.done) R.drawable.widget_ic_task_done else R.drawable.widget_ic_task_open),
                contentDescription = null,
                modifier = GlanceModifier.size(WidgetTokens.check),
                colorFilter = ColorFilter.tint(colors.calendar(item.colorKey)),
            )
        }
        Column(GlanceModifier.defaultWeight().clickable(actionStartActivity(WidgetIntents.task(context, task.id)))) {
            Text(
                task.title.ifBlank { resources.getString(R.string.untitled) },
                style = TextStyle(
                    color = if (task.done) colors.secondary else colors.ink,
                    fontSize = WidgetTokens.bodySize,
                    textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None,
                ),
                maxLines = 2,
            )
            if (due != null) {
                Text(due, style = TextStyle(color = if (overdue) colors.danger else colors.secondary, fontSize = WidgetTokens.smallSize), maxLines = 1)
            }
        }
    }
}

/** Start time in a column of fixed width, so the titles below each other line up. */
@Composable
private fun TimeColumn(time: String, colors: WidgetColors, formats: Formats) {
    Text(
        time,
        style = TextStyle(color = colors.ink, fontSize = WidgetTokens.bodySize, fontWeight = FontWeight.Bold),
        maxLines = 1,
        modifier = GlanceModifier.width(if (formats.use24h) TimeWidth24 else TimeWidth12),
    )
}

private val TimeWidth24 = 52.dp
private val TimeWidth12 = 74.dp

@Composable
private fun ColorBar(color: ColorProvider) {
    Box(GlanceModifier.padding(top = 2.dp)) {
        Box(GlanceModifier.width(WidgetTokens.bar).height(WidgetTokens.barHeight).background(color).cornerRadius(2.dp)) {}
    }
}

/** "Today", "Tomorrow" or the date, with the time when the task has one. */
internal fun dueLabel(resources: Resources, item: TaskItem, today: LocalDate, formats: Formats): String? {
    val date = item.task.dueDate ?: return null
    val day = when (date) {
        today -> resources.getString(R.string.today)
        today.plusDays(1) -> resources.getString(R.string.tomorrow)
        else -> formats.capitalize(date.format(formats.dayMonthShort))
    }
    return item.task.dueTime?.let { "$day, ${it.format(formats.time)}" } ?: day
}

private const val FEATURED_ID = -2L

/** Below this the widget has room for the day only, without the week strip. */
private val WEEK_STRIP_MIN_HEIGHT = 170.dp
private val WEEK_DAY_HEIGHT = 50.dp
