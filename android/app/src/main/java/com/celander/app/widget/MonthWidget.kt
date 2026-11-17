package com.celander.app.widget

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.datastore.preferences.core.Preferences
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
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import com.celander.app.R
import com.celander.app.calendar.HomeDestination
import com.celander.app.core.time.CalendarMath
import com.celander.app.core.ui.Formats
import com.celander.app.designsystem.MAX_GRID_FONT_SCALE
import com.celander.app.settings.AppSettings
import java.time.YearMonth

/** Month widget: the month grid of the reference Samsung widget, with paging and a day per tap. */
class MonthWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = context.widgetDeps()
        WidgetUpdater.scheduleNextDay(context)
        val initial = deps.settings().current()
        val time = deps.time()
        val shown = getAppWidgetState<Preferences>(context, id)[MonthKey]?.let(::parseMonth) ?: YearMonth.from(time.today())
        val firstDay = initial.firstDayOfWeek(Formats.of(context, initial.timeFormat).locale)
        val gridStart = CalendarMath.monthGridStart(shown, firstDay)
        WidgetSnapshots.preload(deps, listOf(gridStart to gridStart.plusWeeks(MonthGridRenderer.weeks(shown, firstDay).toLong())), time.zone(), tasks = false)
        provideContent {
            val settings by deps.settings().settings.collectAsState(initial)
            MonthWidgetContent(context, deps, settings)
        }
    }

    companion object {
        /** The month shown when the user paged away from the current one; absent means "now". */
        val MonthKey = stringPreferencesKey("shown_month")
    }
}

class MonthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MonthWidget()
}

/** Pages the month widget; a shift of 0 goes back to the current month. */
class ShiftMonthAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val shift = parameters[ShiftKey] ?: 0
        val current = YearMonth.from(context.widgetDeps().time().today())
        updateAppWidgetState(context, glanceId) { prefs ->
            val shown = prefs[MonthWidget.MonthKey]?.let(::parseMonth) ?: current
            val target = if (shift == 0) current else shown.plusMonths(shift.toLong())
            if (target == current) prefs.remove(MonthWidget.MonthKey) else prefs[MonthWidget.MonthKey] = target.toString()
        }
        MonthWidget().update(context, glanceId)
    }

    companion object {
        val ShiftKey = ActionParameters.Key<Int>("shift")
    }
}

private fun parseMonth(value: String): YearMonth? = runCatching { YearMonth.parse(value) }.getOrNull()

@Composable
private fun MonthWidgetContent(context: Context, deps: WidgetEntryPoint, settings: AppSettings) {
    val time = deps.time()
    val today = time.today()
    val zone = time.zone()
    val current = YearMonth.from(today)
    val shown = currentState(MonthWidget.MonthKey)?.let(::parseMonth) ?: current
    val formats = remember(settings.timeFormat) { Formats.of(context, settings.timeFormat) }
    val firstDay = settings.firstDayOfWeek(formats.locale)
    val gridStart = CalendarMath.monthGridStart(shown, firstDay)
    val weeks = MonthGridRenderer.weeks(shown, firstDay)
    val window = rememberWindow(deps, gridStart, gridStart.plusWeeks(weeks.toLong()), zone)
    val colors = WidgetColors(settings)
    val resources = context.resources

    WidgetCard(colors) {
        Column(GlanceModifier.fillMaxSize().padding(horizontal = GridPaddingH, vertical = GridPaddingV)) {
            val fontScale = resources.configuration.fontScale.coerceAtMost(MAX_GRID_FONT_SCALE)
            val headerHeight = gridHeaderHeight(fontScale)
            WidgetHeader(
                title = formats.capitalize(shown.format(formats.monthYear)),
                colors = colors,
                sideSlots = 2,
                height = headerHeight,
                onTitle = actionStartActivity(WidgetIntents.view(context, HomeDestination.MONTH, if (shown == current) today else shown.atDay(1))),
                leading = {
                    if (shown != current) {
                        WidgetIconButton(R.drawable.widget_ic_today, resources.getString(R.string.action_today), colors.ink, actionRunCallback<ShiftMonthAction>(actionParametersOf(ShiftMonthAction.ShiftKey to 0)))
                    } else {
                        Spacer(GlanceModifier.width(WidgetTokens.iconTarget))
                    }
                    WidgetIconButton(R.drawable.widget_ic_chevron_left, resources.getString(R.string.action_previous_month), colors.ink, actionRunCallback<ShiftMonthAction>(actionParametersOf(ShiftMonthAction.ShiftKey to -1)))
                },
                trailing = {
                    WidgetIconButton(R.drawable.widget_ic_chevron_right, resources.getString(R.string.action_next_month), colors.ink, actionRunCallback<ShiftMonthAction>(actionParametersOf(ShiftMonthAction.ShiftKey to 1)))
                    WidgetAddButton(resources.getString(R.string.action_new_event), colors, actionStartActivity(WidgetIntents.newEvent(context, if (shown == current) today else shown.atDay(1))))
                },
            )
            val size = LocalSize.current
            val memory = rememberPictureMemory(context)
            val canvas = gridCanvas(size.width - GridPaddingH * 2, size.height - GridPaddingV * 2 - headerHeight, resources.displayMetrics.density, memory, withAccent = false)
            val spec = MonthGridSpec(
                month = shown,
                firstDay = firstDay,
                showWeekNumbers = settings.showWeekNumbers,
                today = today,
                window = window,
                zone = zone,
                weekdayLabels = List(7) { formats.weekdayShort(firstDay.plus(it.toLong())).uppercase(formats.locale) },
                untitled = resources.getString(R.string.untitled),
            )
            val layers = remember(spec, canvas, fontScale) { MonthGridRenderer.render(spec, canvas.width, canvas.height, WidgetPaints(canvas.density, fontScale)) }
            val weekNumbers = MonthGridRenderer.showsWeekNumbers(settings.showWeekNumbers, canvas.width / canvas.density)
            Box(GlanceModifier.fillMaxWidth().defaultWeight()) {
                GridLayers(layers, colors)
                Column(GlanceModifier.fillMaxSize()) {
                    Spacer(GlanceModifier.height(MonthGridRenderer.HEADER_DP.dp))
                    for (row in 0 until weeks) {
                        Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                            if (weekNumbers) Spacer(GlanceModifier.width(MonthGridRenderer.WEEK_COLUMN_DP.dp))
                            for (col in 0 until 7) {
                                val date = gridStart.plusDays(row * 7L + col)
                                val count = window?.day(date)?.count ?: 0
                                val description = formats.capitalize(date.format(formats.dayMonthLong)) + ", " +
                                    resources.getQuantityString(R.plurals.entries_count, count, count)
                                Box(
                                    GlanceModifier.defaultWeight().fillMaxHeight()
                                        .clickable(actionStartActivity(WidgetIntents.view(context, HomeDestination.DAY, date)))
                                        .semantics { contentDescription = description },
                                ) {
                                    TaskTargets(layers.tasks.filter { it.row == row && it.col == col }, canvas.density, resources)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Tap areas over the drawn tasks of one day: a tap checks the task off. */
@Composable
private fun TaskTargets(targets: List<TaskTarget>, density: Float, resources: Resources) {
    if (targets.isEmpty()) return
    Column(GlanceModifier.fillMaxSize()) {
        var bottom = 0f
        for (target in targets.sortedBy { it.top }.take(MAX_TASK_TARGETS)) {
            Spacer(GlanceModifier.height(((target.top - bottom) / density).coerceAtLeast(0f).dp))
            val label = resources.getString(R.string.widget_task_done, target.title)
            Box(
                GlanceModifier.fillMaxWidth().height((target.height / density).dp)
                    .clickable(actionRunCallback<ToggleTaskAction>(actionParametersOf(ToggleTaskAction.TaskIdKey to target.taskId, ToggleTaskAction.DoneKey to !target.done)))
                    .semantics { contentDescription = label },
            ) {}
            bottom = target.top + target.height
        }
    }
}

/** Glance allows ten children per column: a spacer and a target each. */
private const val MAX_TASK_TARGETS = 5

/** The drawn layers on top of each other, the masks tinted with the theme's day or night colors. */
@Composable
internal fun GridLayers(layers: WidgetLayers, colors: WidgetColors) {
    val fill = GlanceModifier.fillMaxSize()
    Image(ImageProvider(layers.color), contentDescription = null, modifier = fill, contentScale = ContentScale.FillBounds)
    Image(ImageProvider(layers.ink), contentDescription = null, modifier = fill, contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(colors.ink))
    Image(ImageProvider(layers.sunday), contentDescription = null, modifier = fill, contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(colors.sunday))
    layers.accent?.let {
        Image(ImageProvider(it), contentDescription = null, modifier = fill, contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(colors.accent))
    }
}

private val GridPaddingH = 10.dp
private val GridPaddingV = 6.dp
