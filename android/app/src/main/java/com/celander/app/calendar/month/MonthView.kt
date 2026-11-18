package com.celander.app.calendar.month

import androidx.compose.foundation.background
import com.ce.kit.ui.glass.GlassKind
import com.ce.kit.ui.glass.glass
import com.ce.kit.ui.glass.fadingBottomEdge
import com.ce.kit.ui.glass.glassTile
import androidx.compose.material3.MaterialTheme
import com.celander.app.ui.isDark
import com.celander.app.ui.today
import com.celander.app.ui.onToday
import com.celander.app.ui.sunday
import com.celander.app.ui.LocalWindowLayout
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.celander.app.R
import com.celander.app.calendar.CalendarWindow
import com.celander.app.calendar.DayContent
import com.celander.app.calendar.PeriodPager
import com.celander.app.calendar.components.OccurrenceRow
import com.celander.app.calendar.components.TaskRow
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.TaskItem
import com.celander.app.core.time.CalendarMath
import com.celander.app.core.ui.Formats
import com.celander.app.ui.CalendarColor
import com.ce.kit.ui.theme.CeTheme
import com.celander.app.ui.ChipTextStyle
import com.celander.app.ui.EmptyState
import com.celander.app.ui.LimitedFontScale
import com.celander.app.ui.PlainIconButton
import com.celander.app.ui.Sizes
import com.celander.app.ui.SkeletonRows
import com.celander.app.ui.elastic
import com.ce.kit.ui.glass.fadingBottomEdge
import com.ce.kit.ui.glass.pressScale
import com.celander.app.ui.pulseOn
import com.celander.app.ui.rememberEntranceWindow
import com.ce.kit.ui.rememberHaptics
import com.celander.app.ui.staggeredEntrance
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt

private val DayHeaderHeight = 26.dp
private val ChipLane = 18.dp
private val ChipHeight = 16.dp
private val BarLane = 6.dp
private val BarHeight = 4.dp
private val CellGap = 3.dp
private val WeekNumberWidth = 22.dp
private val EventMarkerWidth = 3.dp

/** Bars start to turn into chips once a row has room for this many chip lanes. */
private const val CHIP_FADE_FROM = 1.25f

/** Of the way from bar to chip: a bar has drawn together into its marker by here … */
private const val BAR_SHRINK_END = 0.5f

/** … and the title starts to fade in here. */
private const val TITLE_FADE_START = 0.45f

/** The day list is fully visible after a quarter of its way in. */
private const val PANEL_FADE_SPEED = 4f

/** How far past open the rubber band may enlarge the list. */
private const val MAX_STRETCH = 1.25f

/** Below this share of the screen the list is not composed at all, unless a finger still moves it. */
private const val MIN_PANEL_FRACTION = 0.01f
private val LongMoreLabelMinWidth = 64.dp

@Composable
fun MonthView(
    selectedDate: LocalDate,
    today: LocalDate,
    now: Instant,
    zone: ZoneId,
    firstDay: DayOfWeek,
    showWeekNumbers: Boolean,
    panelOpen: Boolean,
    todayPulse: Int,
    formats: Formats,
    contentPadding: PaddingValues,
    windowFor: @Composable (start: LocalDate, endExclusive: LocalDate) -> CalendarWindow?,
    onMonthChange: (YearMonth) -> Unit,
    onDayTap: (LocalDate) -> Unit,
    onDayLongPress: (LocalDate) -> Unit,
    onClosePanel: () -> Unit,
    onOpenPanel: () -> Unit,
    onOpenOccurrence: (EventOccurrence) -> Unit,
    onOpenTask: (TaskItem) -> Unit,
    onToggleTask: (TaskItem, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Wider than tall (landscape phones and tablets, wide split screens): the day list sits beside the grid.
    val sideBySide = LocalWindowLayout.current.isLandscape
    val openFraction = if (sideBySide) 0.42f else 0.36f
    val month = YearMonth.from(selectedDate)
    // The list's share of the height follows the finger while swiping and a fast spring otherwise.
    val swipe = rememberDayPanelSwipe()
    swipe.onOpen = onOpenPanel
    swipe.onClose = onClosePanel
    LaunchedEffect(panelOpen, swipe) { swipe.sync(panelOpen) }
    // The swipe's progress is read only while measuring, placing and drawing: a drag measures and
    // draws again but composes nothing anew. Only showing or dropping the list composes. A list
    // pulled shut stays until the finger lifts: the end of its drag arrives through it.
    val panelShown by remember(swipe, openFraction) {
        derivedStateOf { swipe.dragging || openFraction * swipe.progress > MIN_PANEL_FRACTION }
    }

    val grid: @Composable (Modifier) -> Unit = { gridModifier ->
        LimitedFontScale {
            Column(gridModifier) {
                WeekdayHeader(firstDay, showWeekNumbers, formats)
                PeriodPager(
                    index = month.year * 12L + month.monthValue - 1,
                    onIndexChange = { index -> onMonthChange(YearMonth.of((index / 12).toInt(), (index % 12).toInt() + 1)) },
                    modifier = Modifier.fillMaxSize(),
                ) { index ->
                    val pageMonth = YearMonth.of((index / 12).toInt(), (index % 12).toInt() + 1)
                    val pageStart = CalendarMath.monthGridStart(pageMonth, firstDay)
                    MonthPage(
                        month = pageMonth,
                        window = windowFor(pageStart, pageStart.plusDays(CalendarMath.GRID_DAYS.toLong())),
                        selectedDate = selectedDate.takeIf { panelOpen },
                        today = today,
                        zone = zone,
                        firstDay = firstDay,
                        showWeekNumbers = showWeekNumbers,
                        todayPulse = todayPulse,
                        formats = formats,
                        onDayTap = onDayTap,
                        onDayLongPress = onDayLongPress,
                    )
                }
            }
        }
    }
    val panel: @Composable (Modifier) -> Unit = { panelModifier ->
        // Fades in over the first part of the way, so a small list never shows squeezed content.
        Box(panelModifier.graphicsLayer { alpha = (swipe.progress * PANEL_FADE_SPEED).coerceIn(0f, 1f) }) {
            DayPanel(
                date = selectedDate,
                content = CalendarMath.monthGridStart(month, firstDay).let { start ->
                    windowFor(start, start.plusDays(CalendarMath.GRID_DAYS.toLong()))?.day(selectedDate)
                },
                now = now,
                zone = zone,
                formats = formats,
                onClose = onClosePanel,
                onAdd = { onDayLongPress(selectedDate) },
                onOpenOccurrence = onOpenOccurrence,
                onOpenTask = onOpenTask,
                onToggleTask = onToggleTask,
                swipe = swipe,
            )
        }
    }

    // The day list ends above the pill, so it stands as a card of its own with all corners visible.
    val verticalInsets = Modifier.padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding())
    if (sideBySide) {
        // The list takes its share of the width up to a readable width; on tablets the grid keeps the rest.
        val maxPanelWidth = Sizes.maxDayPanelWidth
        PanelSplit(
            vertical = false,
            panelShown = panelShown,
            panelSize = { total -> (minOf(total * openFraction, maxPanelWidth.toPx()) * swipe.progress).roundToInt() },
            gap = { if (panelShown) 12.dp.roundToPx() else 0 },
            grid = { grid(Modifier) },
            panel = { panel(Modifier) },
            modifier = modifier
                .fillMaxSize()
                .then(verticalInsets)
                .padding(horizontal = 12.dp),
        )
    } else {
        PanelSplit(
            vertical = true,
            panelShown = panelShown,
            panelSize = { total -> (total * (openFraction * swipe.progress).coerceAtMost(openFraction * MAX_STRETCH)).roundToInt() },
            gap = { (12.dp.toPx() * swipe.progress.coerceIn(0f, 1f)).roundToInt() },
            grid = { grid(Modifier) },
            panel = { panel(Modifier) },
            modifier = modifier
                .fillMaxSize()
                .then(verticalInsets)
                .padding(horizontal = 8.dp)
                .onSizeChanged { swipe.panelHeight = it.height * openFraction }
                // Swipe up opens the day list, swipe down closes it; both follow the finger.
                .draggable(
                    rememberDraggableState { swipe.drag(it) },
                    Orientation.Vertical,
                    startDragImmediately = false,
                    onDragStopped = { swipe.release(it) },
                ),
        )
    }
}

/**
 * The grid and the day list, stacked ([vertical]) or side by side. [panelSize] and [gap] are read
 * while measuring, so the list can follow the finger every frame without composing anything.
 */
@Composable
private fun PanelSplit(
    vertical: Boolean,
    panelShown: Boolean,
    panelSize: Density.(total: Int) -> Int,
    gap: Density.() -> Int,
    grid: @Composable () -> Unit,
    panel: @Composable () -> Unit,
    modifier: Modifier,
) {
    Layout(contents = listOf(grid, if (panelShown) panel else ({})), modifier = modifier) { (gridMeasurables, panelMeasurables), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val total = if (vertical) height else width
        val panelPx = if (panelShown) panelSize(total).coerceIn(0, total) else 0
        val gapPx = gap().coerceIn(0, total - panelPx)
        val gridPx = total - panelPx - gapPx
        val gridPlaceable = gridMeasurables.firstOrNull()?.measure(if (vertical) Constraints.fixed(width, gridPx) else Constraints.fixed(gridPx, height))
        val panelPlaceable = panelMeasurables.firstOrNull()?.measure(if (vertical) Constraints.fixed(width, panelPx) else Constraints.fixed(panelPx, height))
        layout(width, height) {
            gridPlaceable?.place(0, 0)
            if (vertical) panelPlaceable?.place(0, gridPx + gapPx) else panelPlaceable?.place(gridPx + gapPx, 0)
        }
    }
}

@Composable
private fun WeekdayHeader(firstDay: DayOfWeek, showWeekNumbers: Boolean, formats: Formats) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp).clearAndSetSemantics { }) {
        if (showWeekNumbers) Spacer(Modifier.width(WeekNumberWidth))
        for (i in 0 until 7) {
            val day = firstDay.plus(i.toLong())
            Text(
                formats.weekdayShort(day),
                style = MaterialTheme.typography.labelMedium,
                color = if (day == DayOfWeek.SUNDAY) colors.sunday else colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MonthPage(
    month: YearMonth,
    window: CalendarWindow?,
    selectedDate: LocalDate?,
    today: LocalDate,
    zone: ZoneId,
    firstDay: DayOfWeek,
    showWeekNumbers: Boolean,
    todayPulse: Int,
    formats: Formats,
    onDayTap: (LocalDate) -> Unit,
    onDayLongPress: (LocalDate) -> Unit,
) {
    val gridStart = remember(month, firstDay) { CalendarMath.monthGridStart(month, firstDay) }
    // Rows share the height by weight, so a grid that resizes with the day list only measures again.
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(CellGap)) {
        for (week in 0 until CalendarMath.GRID_WEEKS) {
            val weekStart = gridStart.plusWeeks(week.toLong())
            val layout = remember(window, weekStart) {
                window?.let { MonthLayout.layoutWeek(weekStart, it.occurrences, it.tasks, zone) }
            }
            WeekRow(
                weekStart = weekStart,
                month = month,
                layout = layout,
                window = window,
                selectedDate = selectedDate,
                today = today,
                firstDay = firstDay,
                showWeekNumbers = showWeekNumbers,
                todayPulse = todayPulse,
                formats = formats,
                onDayTap = onDayTap,
                onDayLongPress = onDayLongPress,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun WeekRow(
    weekStart: LocalDate,
    month: YearMonth,
    layout: WeekLayout?,
    window: CalendarWindow?,
    selectedDate: LocalDate?,
    today: LocalDate,
    firstDay: DayOfWeek,
    showWeekNumbers: Boolean,
    todayPulse: Int,
    formats: Formats,
    onDayTap: (LocalDate) -> Unit,
    onDayLongPress: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth()) {
        if (showWeekNumbers) {
            Text(
                CalendarMath.weekNumber(weekStart, firstDay).toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .width(WeekNumberWidth)
                    .padding(top = 4.dp + 2.dp)
                    .clearAndSetSemantics { },
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(CellGap)) {
                for (col in 0 until 7) {
                    val date = weekStart.plusDays(col.toLong())
                    DayCell(
                        date = date,
                        inMonth = YearMonth.from(date) == month,
                        isToday = date == today,
                        isSelected = date == selectedDate,
                        count = window?.day(date)?.count ?: 0,
                        todayPulse = todayPulse,
                        formats = formats,
                        onTap = { onDayTap(date) },
                        onLongPress = { onDayLongPress(date) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
            if (layout != null) SegmentOverlay(layout)
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    count: Int,
    todayPulse: Int,
    formats: Formats,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(12.dp)
    val todayLabel = stringResource(R.string.today)
    val countLabel = pluralStringResource(R.plurals.entries_count, count, count)
    val description = listOfNotNull(formats.capitalize(date.format(formats.dayMonthLong)), todayLabel.takeIf { isToday }, countLabel)
        .joinToString(", ")
    val newEventLabel = stringResource(R.string.action_new_event)
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(0.95f)
            .alpha(if (inMonth) 1f else 0.5f)
            .glassTile(shape)
            .then(if (isSelected) Modifier.border(1.5.dp, colors.primary, shape) else Modifier)
            .combinedClickable(
                interactionSource = source,
                indication = ripple(),
                onClick = onTap,
                onLongClick = {
                    haptics.longPress()
                    onLongPress()
                },
                onLongClickLabel = newEventLabel,
            )
            .clearAndSetSemantics {
                contentDescription = description
                onClick { onTap(); true }
                customActions = listOf(CustomAccessibilityAction(newEventLabel) { onLongPress(); true })
            },
    ) {
        Box(Modifier.fillMaxWidth().height(DayHeaderHeight), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(22.dp)
                    .then(if (isToday) Modifier.pulseOn(todayPulse).background(colors.today, RoundedCornerShape(7.dp)) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        isToday -> colors.onToday
                        date.dayOfWeek == DayOfWeek.SUNDAY -> colors.sunday
                        else -> colors.onSurface
                    },
                )
            }
        }
    }
}

/**
 * Draws the entries of one week over the day cells, spanning columns where needed: as bars in a low
 * row, as titled chips once the row has room for two chip lanes, in between on their way from one
 * to the other (lane, height and color move with the row's height, the title fades in). The look
 * follows from the row's size alone ([WeekGeometry]), which measuring and drawing both know in the
 * same frame: a row that grows or shrinks with the day list is measured and drawn again, never
 * composed again and never a frame behind. With at least two lanes, the last one shows "+N more"
 * when entries do not fit.
 */
@Composable
private fun SegmentOverlay(layout: WeekLayout) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val styles = remember(layout, colors) { layout.segments.map { SegmentStyle.of(it, colors) } }
    // These change only when a lane comes or goes, which recomposes the "+N" labels and nothing else.
    var visibleLanes by remember(layout) { mutableIntStateOf(layout.laneCount) }
    var showMoreRow by remember(layout) { mutableStateOf(false) }
    var longMoreLabels by remember { mutableStateOf(true) }
    val hidden = remember(layout, visibleLanes) { layout.hiddenPerColumn(visibleLanes) }
    val moreColumns = if (showMoreRow) (0 until 7).filter { hidden[it] > 0 } else emptyList()
    Layout(
        content = {
            layout.segments.forEach { segment -> SegmentLabel(segment) }
            moreColumns.forEach { col -> MoreLabel(hidden[col], longMoreLabels) }
        },
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val geometry = WeekGeometry(size.width, size.height, layout.laneCount, density)
                layout.segments.forEachIndexed { index, segment ->
                    if (segment.lane < geometry.lanes) drawSegment(segment, styles[index], geometry)
                }
            }
            .clearAndSetSemantics { },
    ) { measurables, constraints ->
        val geometry = WeekGeometry(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), layout.laneCount, this)
        if (geometry.lanes != visibleLanes) visibleLanes = geometry.lanes
        if (geometry.showMore != showMoreRow) showMoreRow = geometry.showMore
        val long = geometry.column.toDp() >= LongMoreLabelMinWidth
        if (long != longMoreLabels) longMoreLabels = long

        val segments = layout.segments
        val itemHeight = geometry.itemHeight.roundToInt().coerceAtLeast(1)
        val placeables = measurables.mapIndexed { index, measurable ->
            if (index < segments.size) {
                val segment = segments[index]
                if (segment.lane >= geometry.lanes) return@mapIndexed null
                measurable.measure(Constraints.fixed(geometry.width(segment).toInt().coerceAtLeast(1), itemHeight))
            } else {
                measurable.measure(Constraints.fixed((geometry.column - geometry.inset * 2).toInt().coerceAtLeast(1), ChipHeight.roundToPx()))
            }
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                if (placeable == null) return@forEachIndexed
                // Titles, rings and "+N" fade in with the chips; the alpha is set right here, in the same pass.
                if (index < segments.size) {
                    val segment = segments[index]
                    placeable.placeRelativeWithLayer(geometry.left(segment).toInt(), geometry.top(segment).toInt()) { alpha = geometry.reveal }
                } else {
                    // Labels composed for the previous lane count wait for the next frame.
                    val col = moreColumns.getOrNull(index - segments.size) ?: return@forEachIndexed
                    val x = col * (geometry.column + geometry.gap) + geometry.inset
                    placeable.placeRelativeWithLayer(x.toInt(), (geometry.headerTop + geometry.lanes * geometry.laneHeight).toInt()) { alpha = geometry.reveal }
                }
            }
        }
    }
}

/**
 * Where a week's entries go and how far its bars are on their way to chips, from the row's size
 * in px: the same numbers for measuring and for drawing.
 */
private class WeekGeometry(width: Float, height: Float, laneCount: Int, density: Density) {
    val gap: Float
    val column: Float
    val inset: Float
    val headerTop: Float
    val chipness: Float
    val laneHeight: Float
    val itemHeight: Float
    val lanes: Int
    val showMore: Boolean

    init {
        with(density) {
            gap = CellGap.toPx()
            column = (width - gap * 6) / 7
            inset = 2.dp.toPx()
            headerTop = DayHeaderHeight.toPx()
            val room = height - headerTop - 2.dp.toPx()
            chipness = ((room / ChipLane.toPx() - CHIP_FADE_FROM) / (2f - CHIP_FADE_FROM)).coerceIn(0f, 1f)
            laneHeight = lerp(BarLane.toPx(), ChipLane.toPx(), chipness)
            itemHeight = lerp(BarHeight.toPx(), ChipHeight.toPx(), chipness)
            val maxLanes = (room / laneHeight).toInt().coerceAtLeast(1)
            showMore = chipness > 0f && maxLanes >= 2 && laneCount > maxLanes
            lanes = if (showMore) maxLanes - 1 else maxLanes
        }
    }

    /** How far a bar has drawn together into its marker. */
    val shrink: Float get() = (chipness / BAR_SHRINK_END).coerceAtMost(1f)

    /** How far titles have faded in; they start once their bar is nearly a marker, so the two never overlap. */
    val reveal: Float get() = ((chipness - TITLE_FADE_START) / (1f - TITLE_FADE_START)).coerceIn(0f, 1f)

    fun left(segment: WeekSegment): Float = segment.startCol * (column + gap) + inset

    fun top(segment: WeekSegment): Float = headerTop + segment.lane * laneHeight

    fun width(segment: WeekSegment): Float = (segment.endCol - segment.startCol + 1) * column + (segment.endCol - segment.startCol) * gap - inset * 2
}

/** The colors an entry is drawn in, worked out once per composition. */
private class SegmentStyle(val bar: Color, val chip: Color, val marker: Color, val task: Boolean) {
    companion object {
        fun of(segment: WeekSegment, colors: androidx.compose.material3.ColorScheme): SegmentStyle {
            val calendarColor = CalendarColor.fromKey(segment.entry.colorKey)
            val base = calendarColor.base(colors.isDark)
            val task = segment.entry is MonthEntry.Task
            return SegmentStyle(
                bar = if (task) base.copy(alpha = 0.45f) else base.copy(alpha = 0.85f),
                chip = calendarColor.container(colors.isDark, colors.surface),
                marker = base,
                task = task,
            )
        }
    }
}

/**
 * The colored part of one entry, [WeekGeometry.chipness] of the way from bar to chip. Entries over
 * several days keep their shape and change color; single-day events pull their bar together into
 * the marker before the title; tasks fade their bar out for the ring that reads as their checkbox.
 */
private fun DrawScope.drawSegment(segment: WeekSegment, style: SegmentStyle, geometry: WeekGeometry) {
    val ltr = layoutDirection == LayoutDirection.Ltr
    val width = geometry.width(segment)
    val start = geometry.left(segment)
    val x = if (ltr) start else size.width - start - width
    val y = geometry.top(segment)
    val height = geometry.itemHeight
    if (segment.bar) {
        val radius = CornerRadius(lerp(2.dp.toPx(), 6.dp.toPx(), geometry.chipness))
        // Square ends where the entry goes on in the week before or after.
        val startRadius = if (segment.continuesBefore) CornerRadius.Zero else radius
        val endRadius = if (segment.continuesAfter) CornerRadius.Zero else radius
        val rect = RoundRect(
            left = x, top = y, right = x + width, bottom = y + height,
            topLeftCornerRadius = if (ltr) startRadius else endRadius,
            topRightCornerRadius = if (ltr) endRadius else startRadius,
            bottomRightCornerRadius = if (ltr) endRadius else startRadius,
            bottomLeftCornerRadius = if (ltr) startRadius else endRadius,
        )
        drawPath(Path().apply { addRoundRect(rect) }, lerp(style.bar, style.chip, geometry.chipness))
        return
    }
    val shrink = geometry.shrink
    if (style.task) {
        if (shrink < 1f) drawRoundRect(style.bar, Offset(x, y), Size(width, height), CornerRadius(2.dp.toPx()), alpha = 1f - shrink)
        return
    }
    // The bar narrows to the marker at the entry's start and takes on its full color.
    val marker = EventMarkerWidth.toPx()
    val barWidth = lerp(width, marker, shrink)
    val inset = lerp(0f, 1.dp.toPx(), shrink)
    drawRoundRect(
        color = lerp(style.bar, style.marker, shrink),
        topLeft = Offset(if (ltr) x else x + width - barWidth, y + inset),
        size = Size(barWidth, height - inset * 2),
        cornerRadius = CornerRadius(lerp(2.dp.toPx(), marker / 2, shrink)),
    )
}

/** Title (and for tasks the ring) of one entry; the overlay draws the colors and sets the fade. */
@Composable
private fun SegmentLabel(segment: WeekSegment) {
    val colors = MaterialTheme.colorScheme
    val title = segment.entry.title.ifBlank { stringResource(R.string.untitled) }
    if (segment.bar) {
        Box(Modifier.fillMaxSize().padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
            Text(title, style = ChipTextStyle, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Clip)
        }
    } else {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (segment.entry is MonthEntry.Task) {
                // Tasks keep a small ring that reads as their checkbox.
                Box(Modifier.size(6.dp).border(1.2.dp, CalendarColor.fromKey(segment.entry.colorKey).base(colors.isDark), CircleShape))
            } else {
                Spacer(Modifier.width(EventMarkerWidth))
            }
            Spacer(Modifier.width(3.dp))
            Text(title, style = ChipTextStyle, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Clip)
        }
    }
}

@Composable
private fun MoreLabel(count: Int, long: Boolean, modifier: Modifier = Modifier) {
    val text = if (long) pluralStringResource(R.plurals.more_entries, count, count) else "+$count"
    Text(
        text,
        style = ChipTextStyle.copy(fontSize = 10.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier.padding(start = 2.dp),
    )
}

/** List of the selected day's events and tasks below the grid. */
@Composable
private fun DayPanel(
    date: LocalDate,
    content: DayContent?,
    now: Instant,
    zone: ZoneId,
    formats: Formats,
    onClose: () -> Unit,
    onAdd: () -> Unit,
    onOpenOccurrence: (EventOccurrence) -> Unit,
    onOpenTask: (TaskItem) -> Unit,
    onToggleTask: (TaskItem, Boolean) -> Unit,
    swipe: DayPanelSwipe,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().glass(RoundedCornerShape(28.dp), GlassKind.Card, elevation = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                Text(formats.weekdayLong(date.dayOfWeek), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                Text(formats.capitalize(date.format(formats.monthYear)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            PlainIconButton(Icons.Rounded.Add, stringResource(R.string.action_new_event), onAdd, tint = colors.primary)
            PlainIconButton(Icons.Rounded.Close, stringResource(R.string.action_close_day), onClose)
        }
        when {
            content == null -> SkeletonRows(3, Modifier.padding(16.dp))
            content.isEmpty -> Column(Modifier.fillMaxSize().elastic().nestedScroll(swipe.nestedScroll).verticalScroll(rememberScrollState())) {
                EndsPanelDragWhenGone(swipe)
                EmptyState(
                    icon = Icons.Outlined.EventAvailable,
                    title = stringResource(R.string.day_empty_title),
                    message = stringResource(R.string.day_empty_message),
                    primaryAction = stringResource(R.string.action_new_event) to onAdd,
                )
            }
            else -> {
                EndsPanelDragWhenGone(swipe)
                val entrance by rememberEntranceWindow(date)
                val events = content.allDay + content.timed
                val listState = rememberLazyListState()
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .then(if (listState.canScrollForward) Modifier.fadingBottomEdge(24.dp) else Modifier)
                        .elastic()
                        // Inside the elastic edge, so a pull down at the top moves the panel instead of stretching.
                        .nestedScroll(swipe.nestedScroll),
                    state = listState,
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 12.dp),
                ) {
                    itemsIndexed(events, key = { _, it -> "e:" + it.key }) { index, occurrence ->
                        OccurrenceRow(
                            occurrence, date, formats, now, zone,
                            onClick = { onOpenOccurrence(occurrence) },
                            modifier = Modifier.animateItem().staggeredEntrance(index, entrance),
                        )
                    }
                    itemsIndexed(content.tasks, key = { _, it -> "t:" + it.task.id }) { index, item ->
                        TaskRow(
                            item = item,
                            formats = formats,
                            overdue = item.task.isOverdue(now.atZone(zone)),
                            onToggle = { done -> onToggleTask(item, done) },
                            onClick = { onOpenTask(item) },
                            modifier = Modifier.animateItem().staggeredEntrance(events.size + index, entrance),
                        )
                    }
                }
            }
        }
    }
}

/** A list that moves the panel and leaves mid-gesture (it emptied, the month closed) ends the drag. */
@Composable
private fun EndsPanelDragWhenGone(swipe: DayPanelSwipe) {
    DisposableEffect(swipe) {
        onDispose { swipe.listGone() }
    }
}
