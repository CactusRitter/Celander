package com.celander.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
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
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import com.celander.app.R
import com.celander.app.calendar.HomeDestination
import com.celander.app.core.ui.Formats
import com.celander.app.designsystem.MAX_GRID_FONT_SCALE
import com.celander.app.settings.AppSettings
import java.time.LocalDate
import java.time.YearMonth

/** Year widget: twelve small months, the current one highlighted; a month per tap. */
class YearWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = context.widgetDeps()
        WidgetUpdater.scheduleNextDay(context)
        val initial = deps.settings().current()
        val time = deps.time()
        val start = LocalDate.of(getAppWidgetState<Preferences>(context, id)[YearKey] ?: time.today().year, 1, 1)
        WidgetSnapshots.preload(deps, listOf(start to start.plusYears(1)), time.zone(), tasks = false)
        provideContent {
            val settings by deps.settings().settings.collectAsState(initial)
            YearWidgetContent(context, deps, settings)
        }
    }

    companion object {
        /** The year shown when the user paged away from the current one; absent means "now". */
        val YearKey = intPreferencesKey("shown_year")
    }
}

class YearWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = YearWidget()
}

/** Pages the year widget; a shift of 0 goes back to the current year. */
class ShiftYearAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val shift = parameters[ShiftKey] ?: 0
        val current = context.widgetDeps().time().today().year
        updateAppWidgetState(context, glanceId) { prefs ->
            val target = if (shift == 0) current else (prefs[YearWidget.YearKey] ?: current) + shift
            if (target == current) prefs.remove(YearWidget.YearKey) else prefs[YearWidget.YearKey] = target
        }
        YearWidget().update(context, glanceId)
    }

    companion object {
        val ShiftKey = ActionParameters.Key<Int>("shift")
    }
}

@Composable
private fun YearWidgetContent(context: Context, deps: WidgetEntryPoint, settings: AppSettings) {
    val time = deps.time()
    val today = time.today()
    val zone = time.zone()
    val year = currentState(YearWidget.YearKey) ?: today.year
    val formats = remember(settings.timeFormat) { Formats.of(context, settings.timeFormat) }
    val firstDay = settings.firstDayOfWeek(formats.locale)
    val start = LocalDate.of(year, 1, 1)
    val window = rememberWindow(deps, start, start.plusYears(1), zone)
    val colors = WidgetColors(settings)
    val resources = context.resources

    WidgetCard(colors) {
        Column(GlanceModifier.fillMaxSize().padding(horizontal = PaddingH, vertical = PaddingV)) {
            val fontScale = resources.configuration.fontScale.coerceAtMost(MAX_GRID_FONT_SCALE)
            val headerHeight = gridHeaderHeight(fontScale)
            WidgetHeader(
                title = year.toString(),
                colors = colors,
                sideSlots = 2,
                height = headerHeight,
                onTitle = actionStartActivity(WidgetIntents.view(context, HomeDestination.YEAR, if (year == today.year) today else start)),
                leading = {
                    if (year != today.year) {
                        WidgetIconButton(R.drawable.widget_ic_today, resources.getString(R.string.action_today), colors.ink, actionRunCallback<ShiftYearAction>(actionParametersOf(ShiftYearAction.ShiftKey to 0)))
                    } else {
                        Spacer(GlanceModifier.width(WidgetTokens.iconTarget))
                    }
                    WidgetIconButton(R.drawable.widget_ic_chevron_left, resources.getString(R.string.action_previous_year), colors.ink, actionRunCallback<ShiftYearAction>(actionParametersOf(ShiftYearAction.ShiftKey to -1)))
                },
                trailing = {
                    WidgetIconButton(R.drawable.widget_ic_chevron_right, resources.getString(R.string.action_next_year), colors.ink, actionRunCallback<ShiftYearAction>(actionParametersOf(ShiftYearAction.ShiftKey to 1)))
                    Spacer(GlanceModifier.width(WidgetTokens.iconTarget))
                },
            )
            val size = LocalSize.current
            val memory = rememberPictureMemory(context)
            val canvas = gridCanvas(size.width - PaddingH * 2, size.height - PaddingV * 2 - headerHeight, resources.displayMetrics.density, memory, withAccent = true)
            val spec = YearGridSpec(
                year = year,
                today = today,
                firstDay = firstDay,
                window = window,
                monthNames = List(12) { formats.capitalize(YearMonth.of(year, it + 1).format(formats.monthShort)).trimEnd('.') },
            )
            val layers = remember(spec, canvas, fontScale) { YearGridRenderer.render(spec, canvas.width, canvas.height, WidgetPaints(canvas.density, fontScale)) }
            val columns = YearGridRenderer.columns(canvas.width, canvas.height)
            Box(GlanceModifier.fillMaxWidth().defaultWeight()) {
                GridLayers(layers, colors)
                Column(GlanceModifier.fillMaxSize()) {
                    for (row in 0 until 12 / columns) {
                        Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                            for (col in 0 until columns) {
                                val month = YearMonth.of(year, row * columns + col + 1)
                                val description = formats.capitalize(month.format(formats.monthYear))
                                Box(
                                    GlanceModifier.defaultWeight().fillMaxHeight()
                                        .clickable(actionStartActivity(WidgetIntents.view(context, HomeDestination.MONTH, if (month == YearMonth.from(today)) today else month.atDay(1))))
                                        .semantics { contentDescription = description },
                                ) {}
                            }
                        }
                    }
                }
            }
        }
    }
}

private val PaddingH = 10.dp
private val PaddingV = 6.dp
