package com.celander.app.calendar.agenda

import androidx.compose.foundation.background
import com.celander.app.ui.glass.fadingTopEdge
import com.celander.app.ui.glass.glassTile
import androidx.compose.material3.MaterialTheme
import com.celander.app.ui.nowLine
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.celander.app.R
import com.celander.app.calendar.CalendarWindow
import com.celander.app.calendar.components.OccurrenceRow
import com.celander.app.calendar.components.TaskRow
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.TaskItem
import com.celander.app.core.ui.Formats
import com.celander.app.ui.theme.CelanderTheme
import com.celander.app.ui.Sizes
import com.celander.app.ui.SkeletonRows
import com.celander.app.ui.TextAction
import com.celander.app.ui.centeringPadding
import com.celander.app.ui.dimmed
import com.celander.app.ui.glass.fadingTopEdge
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

private const val INITIAL_PAST_DAYS = 45L
private const val INITIAL_FUTURE_DAYS = 120L
private const val EXTEND_DAYS = 90L
private const val MAX_SPAN_DAYS = 1_200L
private const val EDGE_THRESHOLD = 6

/** Agenda rows and the range they were built for. */
private class BuiltRows(val start: LocalDate, val endExclusive: LocalDate, val rows: List<AgendaRow>)

@Composable
fun AgendaView(
    anchorDate: LocalDate,
    today: LocalDate,
    now: Instant,
    zone: ZoneId,
    formats: Formats,
    contentPadding: PaddingValues,
    jumpRequest: Int,
    windowFor: @Composable (start: LocalDate, endExclusive: LocalDate) -> CalendarWindow?,
    onFirstVisibleDate: (LocalDate) -> Unit,
    onTodayVisible: (Boolean) -> Unit,
    onCreateOn: (LocalDate) -> Unit,
    onOpenOccurrence: (EventOccurrence) -> Unit,
    onOpenTask: (TaskItem) -> Unit,
    onToggleTask: (TaskItem, Boolean) -> Unit,
    /** Open tasks due before today; they appear under today. */
    overdueTasks: List<TaskItem>,
    modifier: Modifier = Modifier,
) {
    var rangeStart by rememberSaveable { mutableLongStateOf(anchorDate.minusDays(INITIAL_PAST_DAYS).toEpochDay()) }
    var rangeEnd by rememberSaveable { mutableLongStateOf(anchorDate.plusDays(INITIAL_FUTURE_DAYS).toEpochDay()) }
    val start = LocalDate.ofEpochDay(rangeStart)
    val end = LocalDate.ofEpochDay(rangeEnd)
    val window = windowFor(start, end)
    // Built off the main thread. While a longer range loads, the rows built last stay, so a fling
    // goes on and the list does not blink; only before the first rows is there a skeleton.
    val built by produceState<BuiltRows?>(null, window, today, zone, overdueTasks) {
        val source = window ?: return@produceState
        value = withContext(Dispatchers.Default) {
            BuiltRows(
                start = source.start,
                endExclusive = source.endExclusive,
                rows = AgendaBuilder.build(source.start, source.endExclusive, source.occurrences, source.tasks + overdueTasks, today, zone),
            )
        }
    }
    // The "now" divider follows the clock without building the rows again.
    val nowIndex = built?.let { AgendaBuilder.nowIndex(it.rows, now, zone) }
    val rows = remember(built, nowIndex) { built?.let { AgendaBuilder.withNow(it.rows, nowIndex) } }
    val listState = rememberLazyListState()
    var initialScrollDone by rememberSaveable { mutableStateOf(false) }
    // Only rows of the current range can say where a jump goes.
    val rangeLoaded = built?.let { it.start == start && it.endExclusive == end } == true
    val currentRows by rememberUpdatedState(rows.takeIf { rangeLoaded })
    val currentRangeLoaded by rememberUpdatedState(rangeLoaded)

    LaunchedEffect(rows != null) {
        val list = rows ?: return@LaunchedEffect
        if (initialScrollDone) return@LaunchedEffect
        val index = list.indexOfFirst { it is AgendaRow.DayHeader && !it.date.isBefore(anchorDate) || it is AgendaRow.Gap && !it.until.isBefore(anchorDate) }
        if (index >= 0) listState.scrollToItem(index)
        initialScrollDone = true
    }

    // A jump (today button or date picker) scrolls to the anchor date, re-centering the range if needed.
    var handledJumpRequest by rememberSaveable { mutableIntStateOf(jumpRequest) }
    LaunchedEffect(jumpRequest, rows, rangeLoaded) {
        if (jumpRequest == handledJumpRequest) return@LaunchedEffect
        val list = currentRows ?: return@LaunchedEffect
        val inRange = !anchorDate.isBefore(LocalDate.ofEpochDay(rangeStart)) && anchorDate.isBefore(LocalDate.ofEpochDay(rangeEnd))
        if (inRange) {
            val index = list.indexOfFirst { it.date >= anchorDate || (it is AgendaRow.Gap && !it.until.isBefore(anchorDate)) }
            if (index >= 0) listState.animateScrollToItem(index)
            handledJumpRequest = jumpRequest
        } else {
            rangeStart = anchorDate.minusDays(INITIAL_PAST_DAYS).toEpochDay()
            rangeEnd = anchorDate.plusDays(INITIAL_FUTURE_DAYS).toEpochDay()
        }
    }

    ReportVisibility(listState, rows, onFirstVisibleDate, onTodayVisible)

    LaunchedEffect(listState) {
        snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0, listState.layoutInfo.totalItemsCount) }
            .distinctUntilChanged()
            .collect { (first, last, total) ->
                // One step at a time: the rows shown while a step loads do not ask for another.
                if (total == 0 || !initialScrollDone || !currentRangeLoaded) return@collect
                if (first < EDGE_THRESHOLD) {
                    rangeStart -= EXTEND_DAYS
                    if (rangeEnd - rangeStart > MAX_SPAN_DAYS) rangeEnd -= EXTEND_DAYS
                } else if (last > total - EDGE_THRESHOLD) {
                    rangeEnd += EXTEND_DAYS
                    if (rangeEnd - rangeStart > MAX_SPAN_DAYS) rangeStart += EXTEND_DAYS
                }
            }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        if (rows == null) {
            val side = centeringPadding(maxWidth, Sizes.maxListWidth, 20.dp)
            SkeletonRows(6, Modifier.padding(top = contentPadding.calculateTopPadding() + 16.dp, start = side, end = side))
        } else {
            AgendaList(
                rows = rows,
                listState = listState,
                today = today,
                now = now,
                zone = zone,
                formats = formats,
                contentPadding = contentPadding,
                // Rows stay at a readable width in wide windows; the whole width still scrolls.
                sidePadding = centeringPadding(maxWidth, Sizes.maxListWidth, 12.dp),
                onCreateOn = onCreateOn,
                onOpenOccurrence = onOpenOccurrence,
                onOpenTask = onOpenTask,
                onToggleTask = onToggleTask,
            )
        }
    }
}

@Composable
private fun AgendaList(
    rows: List<AgendaRow>,
    listState: LazyListState,
    today: LocalDate,
    now: Instant,
    zone: ZoneId,
    formats: Formats,
    contentPadding: PaddingValues,
    sidePadding: Dp,
    onCreateOn: (LocalDate) -> Unit,
    onOpenOccurrence: (EventOccurrence) -> Unit,
    onOpenTask: (TaskItem) -> Unit,
    onToggleTask: (TaskItem, Boolean) -> Unit,
) {
    // The list starts below the top bar so sticky day headers dock there instead of under it.
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding())
            .fadingTopEdge(12.dp),
        contentPadding = PaddingValues(
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
            start = sidePadding,
            end = sidePadding,
        ),
    ) {
        rows.forEach { row ->
            when (row) {
                is AgendaRow.DayHeader -> stickyHeader(key = row.key) {
                    DayHeader(row.date, row.isToday, row.date.isBefore(today), formats)
                }
                is AgendaRow.Event -> item(key = row.key) {
                    OccurrenceRow(row.occurrence, row.date, formats, now, zone, onClick = { onOpenOccurrence(row.occurrence) }, modifier = Modifier.animateItem())
                }
                is AgendaRow.Task -> item(key = row.key) {
                    TaskRow(
                        item = row.item,
                        formats = formats,
                        overdue = row.item.task.isOverdue(now.atZone(zone)),
                        onToggle = { done -> onToggleTask(row.item, done) },
                        onClick = { onOpenTask(row.item) },
                        modifier = Modifier.animateItem(),
                    )
                }
                is AgendaRow.Now -> item(key = row.key) { NowDivider() }
                is AgendaRow.EmptyToday -> item(key = row.key) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.agenda_today_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextAction(stringResource(R.string.action_new_event), onClick = { onCreateOn(row.date) })
                    }
                }
                is AgendaRow.Gap -> item(key = row.key) { GapRow(row, formats, today) }
            }
        }
    }
}

@Composable
private fun ReportVisibility(
    listState: LazyListState,
    rows: List<AgendaRow>?,
    onFirstVisibleDate: (LocalDate) -> Unit,
    onTodayVisible: (Boolean) -> Unit,
) {
    val currentRows by rememberUpdatedState(rows)
    val currentFirst by rememberUpdatedState(onFirstVisibleDate)
    val currentToday by rememberUpdatedState(onTodayVisible)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .collect { visible ->
                val list = currentRows ?: return@collect
                val first = visible.firstOrNull()?.let { list.getOrNull(it) } ?: return@collect
                currentFirst(first.date)
                currentToday(visible.any { index -> list.getOrNull(index)?.let { it is AgendaRow.DayHeader && it.isToday } == true })
            }
    }
}

@Composable
private fun DayHeader(date: LocalDate, isToday: Boolean, isPast: Boolean, formats: Formats) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
        Row(
            Modifier
                .glassTile(CircleShape)
                .background(if (isToday) colors.primaryContainer else colors.surface.copy(alpha = 0.35f))
                .padding(horizontal = 12.dp, vertical = 4.dp + 2.dp)
                .semantics(mergeDescendants = true) { heading() }
                .dimmed(isPast),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isToday) {
                Text(stringResource(R.string.today), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                Text(" · ", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            }
            Text(formats.capitalize(date.format(formats.dayMonthLong)), style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
        }
    }
}

@Composable
private fun NowDivider() {
    val color = MaterialTheme.colorScheme.nowLine
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Box(Modifier.weight(1f).height(1.5.dp).background(color))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.agenda_now), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun GapRow(gap: AgendaRow.Gap, formats: Formats, today: LocalDate) {
    val text = if (gap.until == gap.date) {
        stringResource(R.string.agenda_gap_single, formats.capitalize(gap.date.format(formats.dayMonthShort)))
    } else {
        stringResource(R.string.agenda_gap_until, formats.capitalize(gap.until.format(formats.dayMonthShort)))
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp)
            .dimmed(gap.until.isBefore(today)),
    )
}

