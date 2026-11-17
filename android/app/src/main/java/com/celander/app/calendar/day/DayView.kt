package com.celander.app.calendar.day

import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celander.app.R
import com.celander.app.calendar.CalendarWindow
import com.celander.app.calendar.DayContent
import com.celander.app.calendar.PeriodPager
import com.celander.app.calendar.components.LocalCompletingTasks
import com.celander.app.calendar.components.occurrenceTimeLabel
import com.celander.app.calendar.month.MonthEntry
import com.celander.app.calendar.month.MonthLayout
import com.celander.app.calendar.month.WeekSegment
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.TaskItem
import com.celander.app.core.time.CalendarMath
import com.celander.app.core.ui.Formats
import com.celander.app.designsystem.CalendarColor
import com.celander.app.designsystem.CelanderTheme
import com.celander.app.designsystem.LimitedFontScale
import com.celander.app.designsystem.ChipTextStyle
import com.celander.app.designsystem.Radii
import com.celander.app.designsystem.Sizes
import com.celander.app.designsystem.SkeletonBlock
import com.celander.app.designsystem.Spacing
import com.celander.app.designsystem.TaskCheckbox
import com.celander.app.designsystem.TextAction
import com.celander.app.designsystem.glassTile
import com.celander.app.designsystem.pulseOn
import com.celander.app.designsystem.rememberHaptics
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

private val Gutter = 52.dp
private val MinBlockMinutes = 24L
private val MinuteHeight = Sizes.hourHeight / 60
private val BandLane = 28.dp

/** Blocks narrower than this keep their title on one line instead of breaking words apart. */
private val NarrowBlock = 64.dp
private const val SLOT_MINUTES = 30
private const val ALL_DAY_VISIBLE = 3
private const val DEFAULT_SCROLL_HOUR = 8

/** Whether the day view shows the whole week in a view of [width]: seven columns of readable width. */
fun dayViewShowsWeek(width: Dp): Boolean = WeekColumns.fits(width - Gutter - Spacing.m, Sizes.minDayColumnWidth)

/**
 * One day on a timeline below the strip of its week; with [showsWeek] (wide windows, see
 * [dayViewShowsWeek]) the whole week side by side, paging by weeks.
 */
@Composable
fun DayView(
    selectedDate: LocalDate,
    today: LocalDate,
    now: Instant,
    zone: ZoneId,
    firstDay: DayOfWeek,
    showsWeek: Boolean,
    todayPulse: Int,
    formats: Formats,
    contentPadding: PaddingValues,
    windowFor: @Composable (start: LocalDate, endExclusive: LocalDate) -> CalendarWindow?,
    onSelectDate: (LocalDate) -> Unit,
    // The offset tells the repeated hour apart on the day the clocks go back; null on the wall-clock axis.
    onCreateAt: (LocalDate, LocalTime, ZoneOffset?) -> Unit,
    onOpenOccurrence: (EventOccurrence) -> Unit,
    onOpenTask: (TaskItem) -> Unit,
    onToggleTask: (TaskItem, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LimitedFontScale {
        Column(modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
            if (showsWeek) {
                val week = WeekColumns.index(selectedDate, firstDay)
                PeriodPager(
                    index = week,
                    onIndexChange = { onSelectDate(selectedDate.plusWeeks(it - week)) },
                    modifier = Modifier.fillMaxSize(),
                ) { index ->
                    val start = WeekColumns.start(index, firstDay)
                    WeekPage(
                        days = List(WeekColumns.DAYS) { start.plusDays(it.toLong()) },
                        window = windowFor(start, start.plusDays(WeekColumns.DAYS.toLong())),
                        selected = selectedDate,
                        today = today,
                        now = now,
                        zone = zone,
                        todayPulse = todayPulse,
                        formats = formats,
                        bottomPadding = contentPadding.calculateBottomPadding(),
                        onSelectDate = onSelectDate,
                        onCreateAt = onCreateAt,
                        onOpenOccurrence = onOpenOccurrence,
                        onOpenTask = onOpenTask,
                        onToggleTask = onToggleTask,
                    )
                }
            } else {
                val weekStart = CalendarMath.weekStart(selectedDate, firstDay)
                WeekStrip(
                    days = CalendarMath.weekDays(selectedDate, firstDay),
                    selected = selectedDate,
                    today = today,
                    window = windowFor(weekStart, weekStart.plusDays(7)),
                    todayPulse = todayPulse,
                    formats = formats,
                    onSelect = onSelectDate,
                    modifier = Modifier.padding(horizontal = Spacing.s),
                )
                PeriodPager(
                    index = selectedDate.toEpochDay(),
                    onIndexChange = { onSelectDate(LocalDate.ofEpochDay(it)) },
                    modifier = Modifier.fillMaxSize(),
                ) { epochDay ->
                    val date = LocalDate.ofEpochDay(epochDay)
                    val pageWeek = CalendarMath.weekStart(date, firstDay)
                    val window = windowFor(pageWeek, pageWeek.plusDays(7))
                    DayPage(
                        date = date,
                        content = window?.day(date),
                        isToday = date == today,
                        now = now,
                        zone = zone,
                        formats = formats,
                        bottomPadding = contentPadding.calculateBottomPadding(),
                        onCreateAt = { at -> onCreateAt(date, at.toLocalTime(), at.offset) },
                        onOpenOccurrence = onOpenOccurrence,
                        onOpenTask = onOpenTask,
                        onToggleTask = onToggleTask,
                    )
                }
            }
        }
    }
}

/**
 * The days of a week to pick from. Above a week of columns it is their header: without the
 * markers (the entries show below) and offering TalkBack a new event on each day.
 */
@Composable
private fun WeekStrip(
    days: List<LocalDate>,
    selected: LocalDate,
    today: LocalDate,
    window: CalendarWindow?,
    todayPulse: Int,
    formats: Formats,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    asHeader: Boolean = false,
    onCreateOn: (LocalDate) -> Unit = {},
) {
    val colors = CelanderTheme.colors
    val haptics = rememberHaptics()
    val resources = LocalResources.current
    val createLabel = stringResource(R.string.action_new_event)
    Row(modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        days.forEach { date ->
            val isSelected = date == selected
            val content = window?.day(date)
            val markers = content?.let { (it.allDay + it.timed).map { o -> o.colorKey } + it.tasks.map { t -> t.colorKey } }.orEmpty().distinct().take(3)
            val description = formats.capitalize(date.format(formats.dayMonthLong)) + ", " +
                resources.getQuantityString(R.plurals.entries_count, content?.count ?: 0, content?.count ?: 0)
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Radii.card))
                    .background(if (isSelected) colors.accentSoft else Color.Transparent)
                    .clickable {
                        if (date == today) haptics.tick()
                        onSelect(date)
                    }
                    .clearAndSetSemantics {
                        contentDescription = description
                        this.selected = isSelected
                        onClick { onSelect(date); true }
                        if (asHeader) customActions = listOf(CustomAccessibilityAction(createLabel) { onCreateOn(date); true })
                    }
                    .padding(vertical = Spacing.s),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    formats.weekdayShort(date.dayOfWeek),
                    style = CelanderTheme.typography.labelSmall,
                    color = if (date.dayOfWeek == DayOfWeek.SUNDAY) colors.sunday else colors.textSecondary,
                )
                Spacer(Modifier.height(Spacing.xxs))
                Box(
                    Modifier
                        .size(30.dp)
                        .then(if (date == today) Modifier.pulseOn(todayPulse).background(colors.today, RoundedCornerShape(9.dp)) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        date.dayOfMonth.toString(),
                        style = CelanderTheme.typography.titleSmall,
                        color = if (date == today) colors.onToday else colors.textPrimary,
                    )
                }
                if (!asHeader) {
                    Spacer(Modifier.height(Spacing.xs))
                    Row(Modifier.height(Sizes.dot), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        markers.forEach { key ->
                            Box(Modifier.size(Sizes.dot).background(CalendarColor.fromKey(key).base(colors.isDark), CircleShape))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayPage(
    date: LocalDate,
    content: DayContent?,
    isToday: Boolean,
    now: Instant,
    zone: ZoneId,
    formats: Formats,
    bottomPadding: Dp,
    onCreateAt: (ZonedDateTime) -> Unit,
    onOpenOccurrence: (EventOccurrence) -> Unit,
    onOpenTask: (TaskItem) -> Unit,
    onToggleTask: (TaskItem, Boolean) -> Unit,
) {
    val colors = CelanderTheme.colors
    val dayLength = remember(date, zone) { CalendarMath.dayLengthMinutes(date, zone) }
    val hourMarks = remember(date, zone) { CalendarMath.hourMarks(date, zone) }
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize()) {
        if (content == null) {
            SkeletonBlock(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s), height = 28.dp)
        } else {
            val untimedTasks = content.tasks.filter { it.task.dueTime == null }
            if (content.allDay.isNotEmpty() || untimedTasks.isNotEmpty()) {
                AllDayArea(date, content.allDay, untimedTasks, now, zone, formats, onOpenOccurrence, onOpenTask, onToggleTask)
            }
        }

        InitialScroll(
            scroll = scroll,
            page = date,
            ready = content != null,
            nowMinute = if (isToday) CalendarMath.minutesIntoDay(now, date, zone) else null,
            firstMinute = content?.timed?.minOfOrNull { CalendarMath.minutesIntoDay(it.timing.startInstant(zone), date, zone) },
        )

        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            val totalHeight = MinuteHeight * dayLength.toFloat()
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(totalHeight + bottomPadding + Spacing.l),
            ) {
                val lineColor = colors.divider
                // Hour labels and grid lines
                hourMarks.forEach { mark ->
                    if (mark.minuteOffset > 0) HourLabel(mark.label.format(formats.hour), mark.minuteOffset)
                }
                val createLabel = stringResource(R.string.action_new_event)
                Box(
                    Modifier
                        .padding(start = Gutter, end = Spacing.m)
                        .fillMaxWidth()
                        .height(totalHeight)
                        .drawBehind {
                            hourMarks.forEach { mark ->
                                val y = (MinuteHeight * mark.minuteOffset.toFloat()).toPx()
                                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = Sizes.hairline.toPx())
                            }
                        }
                        .pointerInput(date) {
                            detectTapGestures(
                                onTap = { offset -> onCreateAt(slotTime(offset.y, MinuteHeight.toPx(), date, zone)) },
                                onLongPress = { offset -> onCreateAt(slotTime(offset.y, MinuteHeight.toPx(), date, zone)) },
                            )
                        }
                        .semantics {
                            customActions = listOf(CustomAccessibilityAction(createLabel) { onCreateAt(date.atTime(DEFAULT_SCROLL_HOUR, 0).atZone(zone)); true })
                        },
                ) {
                    if (content != null) {
                        TimelineItems(
                            date = date,
                            content = content,
                            wallClock = false,
                            now = now,
                            zone = zone,
                            formats = formats,
                            onOpenOccurrence = onOpenOccurrence,
                            onOpenTask = onOpenTask,
                            onToggleTask = onToggleTask,
                        )
                    }
                    if (isToday) NowLine(CalendarMath.minutesIntoDay(now, date, zone))
                }
            }
        }
    }
}

/**
 * A week of timeline columns under their day headers, all-day entries in a band across them.
 * The columns share a wall-clock axis (see [WeekColumns.wallMinutes]).
 */
@Composable
private fun WeekPage(
    days: List<LocalDate>,
    window: CalendarWindow?,
    selected: LocalDate,
    today: LocalDate,
    now: Instant,
    zone: ZoneId,
    todayPulse: Int,
    formats: Formats,
    bottomPadding: Dp,
    onSelectDate: (LocalDate) -> Unit,
    onCreateAt: (LocalDate, LocalTime, ZoneOffset?) -> Unit,
    onOpenOccurrence: (EventOccurrence) -> Unit,
    onOpenTask: (TaskItem) -> Unit,
    onToggleTask: (TaskItem, Boolean) -> Unit,
) {
    val colors = CelanderTheme.colors
    val scroll = rememberScrollState()
    val columns = Modifier.padding(start = Gutter, end = Spacing.m)

    Column(Modifier.fillMaxSize()) {
        WeekStrip(
            days = days,
            selected = selected,
            today = today,
            window = window,
            todayPulse = todayPulse,
            formats = formats,
            onSelect = onSelectDate,
            modifier = columns,
            asHeader = true,
            onCreateOn = { date -> onCreateAt(date, LocalTime.of(DEFAULT_SCROLL_HOUR, 0), null) },
        )
        if (window == null) {
            SkeletonBlock(columns.fillMaxWidth().padding(vertical = Spacing.s), height = 28.dp)
        } else {
            AllDayBand(days.first(), window, now, zone, formats, onOpenOccurrence, onOpenTask, onToggleTask, columns)
        }

        InitialScroll(
            scroll = scroll,
            page = days.first(),
            ready = window != null,
            nowMinute = if (today in days) WeekColumns.wallMinutes(now, today, zone) else null,
            firstMinute = window?.let { w ->
                days.mapNotNull { date -> w.day(date).timed.minOfOrNull { WeekColumns.wallMinutes(it.timing.startInstant(zone), date, zone) } }.minOrNull()
            },
        )

        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            val totalHeight = MinuteHeight * WeekColumns.MINUTES_PER_DAY.toFloat()
            Box(Modifier.fillMaxWidth().height(totalHeight + bottomPadding + Spacing.l)) {
                val lineColor = colors.divider
                for (hour in 1 until 24) HourLabel(LocalTime.of(hour, 0).format(formats.hour), hour * 60L)
                Row(
                    columns
                        .fillMaxWidth()
                        .height(totalHeight)
                        .drawBehind {
                            val stroke = Sizes.hairline.toPx()
                            for (hour in 0 until 24) {
                                val y = (MinuteHeight * (hour * 60f)).toPx()
                                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
                            }
                            for (day in 0 until WeekColumns.DAYS) {
                                val x = size.width * day / WeekColumns.DAYS
                                drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
                            }
                        },
                ) {
                    days.forEach { date ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(start = Sizes.hairline)
                                .pointerInput(date) {
                                    detectTapGestures(
                                        onTap = { offset -> onCreateAt(date, wallSlotTime(offset.y, MinuteHeight.toPx()), null) },
                                        onLongPress = { offset -> onCreateAt(date, wallSlotTime(offset.y, MinuteHeight.toPx()), null) },
                                    )
                                },
                        ) {
                            if (window != null) {
                                TimelineItems(
                                    date = date,
                                    content = window.day(date),
                                    wallClock = true,
                                    now = now,
                                    zone = zone,
                                    formats = formats,
                                    onOpenOccurrence = onOpenOccurrence,
                                    onOpenTask = onOpenTask,
                                    onToggleTask = onToggleTask,
                                )
                            }
                            if (date == today) NowLine(WeekColumns.wallMinutes(now, date, zone))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Scrolls a timeline once per [page] when its content is [ready]: to just before now, else to
 * the first entry, else to 8 o'clock.
 */
@Composable
private fun InitialScroll(scroll: ScrollState, page: LocalDate, ready: Boolean, nowMinute: Long?, firstMinute: Long?) {
    val density = LocalDensity.current
    var done by rememberSaveable(page) { mutableStateOf(false) }
    LaunchedEffect(ready) {
        if (done || !ready) return@LaunchedEffect
        val targetMinute = when {
            nowMinute != null -> nowMinute - 60
            firstMinute != null -> firstMinute - 30
            else -> DEFAULT_SCROLL_HOUR * 60L
        }.coerceAtLeast(0)
        scroll.scrollTo(with(density) { (MinuteHeight * targetMinute.toFloat()).roundToPx() })
        done = true
    }
}

@Composable
private fun HourLabel(text: String, minute: Long) {
    Text(
        text,
        style = CelanderTheme.typography.labelSmall.copy(fontSize = 10.sp),
        color = CelanderTheme.colors.textTertiary,
        textAlign = TextAlign.End,
        modifier = Modifier
            .offset(y = MinuteHeight * minute.toFloat() - 7.dp)
            .width(Gutter - Spacing.s)
            .clearAndSetSemantics { },
    )
}

/**
 * Snaps a tap on the timeline to the 30-minute slot. The timeline runs in elapsed time, so on the day
 * the clocks go back the result keeps which of the two 2:00 hours was tapped.
 */
private fun slotTime(y: Float, minutePx: Float, date: LocalDate, zone: ZoneId): ZonedDateTime {
    val minutes = CalendarMath.snapMinutes((y / minutePx).toInt(), SLOT_MINUTES).toLong()
    return CalendarMath.dayStart(date, zone).plusSeconds(minutes * 60).atZone(zone)
}

/** The same on the wall-clock axis of the week columns. */
private fun wallSlotTime(y: Float, minutePx: Float): LocalTime {
    val minutes = CalendarMath.snapMinutes((y / minutePx).toInt(), SLOT_MINUTES).coerceIn(0, WeekColumns.MINUTES_PER_DAY.toInt() - SLOT_MINUTES)
    return LocalTime.MIDNIGHT.plusMinutes(minutes.toLong())
}

/**
 * All-day and multi-day events as bars across their days, tasks without a time in their day's
 * column. Beyond [ALL_DAY_VISIBLE] lanes the band folds behind a "more" action.
 */
@Composable
private fun AllDayBand(
    weekStart: LocalDate,
    window: CalendarWindow,
    now: Instant,
    zone: ZoneId,
    formats: Formats,
    onOpenOccurrence: (EventOccurrence) -> Unit,
    onOpenTask: (TaskItem) -> Unit,
    onToggleTask: (TaskItem, Boolean) -> Unit,
    modifier: Modifier,
) {
    val layout = remember(window, weekStart, zone) {
        MonthLayout.layoutWeek(
            weekStart = weekStart,
            occurrences = window.occurrences.filter { it.allDay || it.timing.spansMultipleDays(zone) },
            tasks = window.tasks.filter { it.task.dueTime == null },
            zone = zone,
        )
    }
    if (layout.laneCount == 0) return
    var expanded by rememberSaveable(weekStart) { mutableStateOf(false) }
    val lanes = if (expanded) layout.laneCount else minOf(layout.laneCount, ALL_DAY_VISIBLE)
    val hidden = layout.segments.count { it.lane >= lanes }
    Column(modifier.fillMaxWidth().padding(bottom = Spacing.xs)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(BandLane * lanes)) {
            val column = maxWidth / WeekColumns.DAYS
            layout.segments.filter { it.lane < lanes }.forEach { segment ->
                val span = segment.endCol - segment.startCol + 1
                Box(
                    Modifier
                        .offset(x = column * segment.startCol, y = BandLane * segment.lane)
                        .width(column * span)
                        .height(BandLane)
                        .padding(horizontal = Sizes.hairline, vertical = Spacing.xxs / 2),
                ) {
                    when (val entry = segment.entry) {
                        is MonthEntry.Event -> BandEvent(entry.occurrence, segment, weekStart.plusDays(segment.startCol.toLong()), zone, formats) { onOpenOccurrence(entry.occurrence) }
                        is MonthEntry.Task -> CompactTask(entry.item, now, zone, onOpenTask, onToggleTask)
                    }
                }
            }
        }
        if (layout.laneCount > ALL_DAY_VISIBLE) {
            TextAction(
                if (expanded) stringResource(R.string.action_show_less) else pluralStringResource(R.plurals.more_entries, hidden, hidden),
                onClick = { expanded = !expanded },
            )
        }
    }
}

/** An all-day or multi-day event in the band, open on the sides where it runs into another week. */
@Composable
private fun BandEvent(occurrence: EventOccurrence, segment: WeekSegment, date: LocalDate, zone: ZoneId, formats: Formats, onClick: () -> Unit) {
    val colors = CelanderTheme.colors
    val color = CalendarColor.fromKey(occurrence.colorKey)
    val corner = Radii.chip + 2.dp
    val shape = RoundedCornerShape(
        topStart = if (segment.continuesBefore) 0.dp else corner,
        bottomStart = if (segment.continuesBefore) 0.dp else corner,
        topEnd = if (segment.continuesAfter) 0.dp else corner,
        bottomEnd = if (segment.continuesAfter) 0.dp else corner,
    )
    val title = occurrence.title.ifBlank { stringResource(R.string.untitled) }
    val time = occurrenceTimeLabel(occurrence, date, formats, zone)
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(color.container(colors.isDark, colors.surface))
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = listOf(title, time, occurrence.calendar.name).joinToString(", ")
                onClick { onClick(); true }
            }
            .padding(horizontal = Spacing.s - 2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(title, style = ChipTextStyle.copy(fontSize = 12.sp, lineHeight = 15.sp), color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AllDayArea(
    date: LocalDate,
    events: List<EventOccurrence>,
    tasks: List<TaskItem>,
    now: Instant,
    zone: ZoneId,
    formats: Formats,
    onOpenOccurrence: (EventOccurrence) -> Unit,
    onOpenTask: (TaskItem) -> Unit,
    onToggleTask: (TaskItem, Boolean) -> Unit,
) {
    val colors = CelanderTheme.colors
    var expanded by rememberSaveable(date) { mutableStateOf(false) }
    val total = events.size + tasks.size
    val limit = if (expanded) total else ALL_DAY_VISIBLE
    Column(
        Modifier.fillMaxWidth().padding(start = Gutter, end = Spacing.m, bottom = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        events.take(limit).forEach { occurrence ->
            val color = CalendarColor.fromKey(occurrence.colorKey)
            val time = occurrenceTimeLabel(occurrence, date, formats, zone)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
                    .clip(RoundedCornerShape(Radii.chip + 2.dp))
                    .background(color.container(colors.isDark, colors.surface))
                    .clickable { onOpenOccurrence(occurrence) }
                    .clearAndSetSemantics {
                        contentDescription = listOf(occurrence.title, time, occurrence.calendar.name).joinToString(", ")
                        onClick { onOpenOccurrence(occurrence); true }
                    }
                    .padding(horizontal = Spacing.s, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    occurrence.title.ifBlank { stringResource(R.string.untitled) },
                    style = CelanderTheme.typography.labelLarge,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (occurrence.timing.spansMultipleDays(zone)) {
                    Text(time, style = CelanderTheme.typography.labelSmall, color = colors.textSecondary)
                }
            }
        }
        tasks.take((limit - events.size).coerceAtLeast(0)).forEach { item ->
            CompactTask(item, now, zone, onOpenTask, onToggleTask)
        }
        if (total > ALL_DAY_VISIBLE) {
            TextAction(
                if (expanded) stringResource(R.string.action_show_less) else pluralStringResource(R.plurals.more_entries, total - ALL_DAY_VISIBLE, total - ALL_DAY_VISIBLE),
                onClick = { expanded = !expanded },
            )
        }
    }
}

@Composable
private fun CompactTask(item: TaskItem, now: Instant, zone: ZoneId, onOpenTask: (TaskItem) -> Unit, onToggleTask: (TaskItem, Boolean) -> Unit) {
    val colors = CelanderTheme.colors
    val title = item.task.title.ifBlank { stringResource(R.string.untitled) }
    val checked = item.task.done || item.task.id in LocalCompletingTasks.current
    val toggleLabel = stringResource(if (checked) R.string.task_mark_open else R.string.task_mark_done, title)
    val overdue = item.task.isOverdue(now.atZone(zone))
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.chip + 2.dp))
            .glassTile(RoundedCornerShape(Radii.chip + 2.dp))
            .clickable { onOpenTask(item) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskCheckbox(
            checked = checked,
            onCheckedChange = { onToggleTask(item, it) },
            color = CalendarColor.fromKey(item.colorKey).base(colors.isDark),
            contentDescription = toggleLabel,
            size = 18.dp,
        )
        Text(
            title,
            style = CelanderTheme.typography.labelLarge,
            color = if (overdue) colors.danger else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = Spacing.s),
        )
    }
}

private sealed interface TimelineEntry {
    data class Event(val occurrence: EventOccurrence) : TimelineEntry
    data class Task(val item: TaskItem) : TimelineEntry
}

/** Timed events and tasks of [date], in elapsed minutes of the day or on the [wallClock]. */
@Composable
private fun TimelineItems(
    date: LocalDate,
    content: DayContent,
    wallClock: Boolean,
    now: Instant,
    zone: ZoneId,
    formats: Formats,
    onOpenOccurrence: (EventOccurrence) -> Unit,
    onOpenTask: (TaskItem) -> Unit,
    onToggleTask: (TaskItem, Boolean) -> Unit,
) {
    val placed = remember(content, date, zone, wallClock) {
        fun minuteOf(instant: Instant) = if (wallClock) WeekColumns.wallMinutes(instant, date, zone) else CalendarMath.minutesIntoDay(instant, date, zone)
        val events = content.timed.map { o ->
            TimelineItem(
                key = "e:" + o.key,
                startMinute = minuteOf(o.timing.startInstant(zone)),
                endMinute = minuteOf(o.timing.endInstant(zone)),
                payload = TimelineEntry.Event(o) as TimelineEntry,
            )
        }
        val tasks = content.tasks.filter { it.task.dueTime != null }.map { t ->
            val start = minuteOf(date.atTime(t.task.dueTime).atZone(zone).toInstant())
            TimelineItem("t:${t.task.id}", start, start + MinBlockMinutes, TimelineEntry.Task(t) as TimelineEntry)
        }
        DayLayout.place(events + tasks, MinBlockMinutes)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        placed.forEach { p ->
            val top = MinuteHeight * p.item.startMinute.toFloat()
            val height = MinuteHeight * maxOf(p.item.endMinute - p.item.startMinute, MinBlockMinutes).toFloat()
            val blockWidth = width * (p.right - p.left) - 2.dp
            val itemModifier = Modifier
                .offset(x = width * p.left, y = top)
                .width(blockWidth)
                .height(height - 1.dp)
            when (val entry = p.item.payload) {
                is TimelineEntry.Event -> EventBlock(entry.occurrence, date, height, blockWidth < NarrowBlock, now, zone, formats, itemModifier) { onOpenOccurrence(entry.occurrence) }
                is TimelineEntry.Task -> Box(itemModifier) { CompactTask(entry.item, now, zone, onOpenTask, onToggleTask) }
            }
        }
    }
}

@Composable
private fun EventBlock(
    occurrence: EventOccurrence,
    date: LocalDate,
    height: Dp,
    narrow: Boolean,
    now: Instant,
    zone: ZoneId,
    formats: Formats,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = CelanderTheme.colors
    val color = CalendarColor.fromKey(occurrence.colorKey)
    val shape = RoundedCornerShape(Radii.chip + 2.dp)
    val past = !occurrence.timing.endInstant(zone).isAfter(now)
    val time = occurrenceTimeLabel(occurrence, date, formats, zone)
    val title = occurrence.title.ifBlank { stringResource(R.string.untitled) }
    Row(
        modifier
            .clip(shape)
            .background(color.container(colors.isDark, colors.surface).copy(alpha = if (past) 0.7f else 1f))
            .border(Sizes.hairline, color.base(colors.isDark).copy(alpha = 0.25f), shape)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(title, time, occurrence.location.takeIf { it.isNotBlank() }, occurrence.calendar.name).joinToString(", ")
                onClick { onClick(); true }
            },
    ) {
        Box(Modifier.width(3.dp).fillMaxSize().background(color.base(colors.isDark)))
        Column(Modifier.padding(horizontal = Spacing.s - 2.dp, vertical = Spacing.xxs + 1.dp)) {
            val lines = if (height < 40.dp || narrow) 1 else 2
            Text(title, style = ChipTextStyle.copy(fontSize = 12.sp, lineHeight = 15.sp), color = colors.textPrimary, maxLines = lines, overflow = TextOverflow.Ellipsis)
            if (height >= 40.dp) {
                Text(time, style = ChipTextStyle, color = colors.textSecondary, maxLines = 1)
            }
            if (height >= 64.dp && occurrence.location.isNotBlank()) {
                Text(occurrence.location, style = ChipTextStyle, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun NowLine(minute: Long) {
    val color = CelanderTheme.colors.nowLine
    val y = MinuteHeight * minute.toFloat()
    Box(Modifier.offset(y = y - 1.dp).fillMaxWidth().height(2.dp).background(color).clearAndSetSemantics { })
    Box(Modifier.offset(x = (-5).dp, y = y - 5.dp).size(10.dp).background(color, CircleShape).clearAndSetSemantics { })
}
