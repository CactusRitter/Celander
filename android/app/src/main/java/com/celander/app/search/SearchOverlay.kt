package com.celander.app.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celander.app.R
import com.celander.app.calendar.components.OccurrenceRow
import com.celander.app.calendar.components.TaskRow
import com.celander.app.core.model.CalendarInfo
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.TaskItem
import com.celander.app.core.ui.Formats
import com.celander.app.designsystem.CalendarColor
import com.celander.app.designsystem.CelanderTheme
import com.celander.app.designsystem.EmptyState
import com.celander.app.designsystem.GlassSurface
import com.celander.app.designsystem.MotionTokens
import com.celander.app.designsystem.PillShape
import com.celander.app.designsystem.PlainIconButton
import com.celander.app.designsystem.SelectableChip
import com.celander.app.designsystem.Sizes
import com.celander.app.designsystem.Spacing
import com.celander.app.designsystem.blockTouchesBelow
import com.celander.app.designsystem.color
import com.celander.app.designsystem.dimmed
import com.celander.app.designsystem.centeringPadding
import com.celander.app.designsystem.elastic
import com.celander.app.designsystem.fadingTopEdge
import com.celander.app.designsystem.glassTile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay

/**
 * Full-screen search over everything, above the current view. Results appear while typing, grouped
 * by day; tapping one opens the event or task on top of the search.
 */
@Composable
fun SearchOverlay(
    today: LocalDate,
    now: Instant,
    zone: ZoneId,
    formats: Formats,
    onClose: () -> Unit,
    onOpenOccurrence: (EventOccurrence) -> Unit,
    onOpenTask: (TaskItem) -> Unit,
    onToggleTask: (TaskItem, Boolean) -> Unit,
    onCreateEvent: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(viewModel.text.value) }
    val focusManager = LocalFocusManager.current
    val close = {
        focusManager.clearFocus()
        viewModel.reset()
        onClose()
    }
    BackHandler(onBack = close)

    GlassSurface(modifier.fillMaxSize().blockTouchesBelow(), shape = RectangleShape, shadow = false, dense = true) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                // A full-screen surface carries a lot of text; tone the blurred view down further.
                .background(CelanderTheme.colors.background.copy(alpha = 0.4f))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            // In wide windows field, filters and results keep a readable width in the middle.
            val side = centeringPadding(maxWidth, Sizes.maxListWidth, 0.dp)
            Column(Modifier.fillMaxSize()) {
                SearchField(
                    text = text,
                    onTextChange = {
                        text = it
                        viewModel.setQuery(it)
                    },
                    onBack = close,
                    onSubmit = { focusManager.clearFocus() },
                    modifier = Modifier.padding(horizontal = side),
                )
                FilterRow(state.filter, state.calendars, viewModel::setRange, viewModel::toggleCalendar, Modifier.padding(horizontal = side))
                ResultArea(
                    results = state.results,
                    typed = text.trim(),
                    today = today,
                    now = now,
                    zone = zone,
                    formats = formats,
                    onOpenOccurrence = {
                        focusManager.clearFocus()
                        onOpenOccurrence(it)
                    },
                    onOpenTask = {
                        focusManager.clearFocus()
                        onOpenTask(it)
                    },
                    onToggleTask = onToggleTask,
                    onCreateEvent = {
                        focusManager.clearFocus()
                        onCreateEvent(it)
                    },
                    sidePadding = side,
                )
            }
        }
    }
}

@Composable
private fun SearchField(text: String, onTextChange: (String) -> Unit, onBack: () -> Unit, onSubmit: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CelanderTheme.colors
    val focus = remember { FocusRequester() }
    val hint = stringResource(R.string.search_hint)
    LaunchedEffect(Unit) {
        // After the overlay has faded in, so the keyboard does not fight the animation.
        delay(MotionTokens.SHORT.toLong())
        runCatching { focus.requestFocus() }
    }
    Row(
        modifier.fillMaxWidth().padding(start = Spacing.xs, end = Spacing.m, top = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlainIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), onBack, tint = colors.textPrimary)
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = Sizes.touchTarget)
                .glassTile(PillShape)
                .padding(start = Spacing.m, end = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, tint = colors.textSecondary, modifier = Modifier.size(Sizes.icon))
            Spacer(Modifier.width(Spacing.s))
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = CelanderTheme.typography.bodyLarge.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = hint },
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Text(hint, style = CelanderTheme.typography.bodyLarge, color = colors.textTertiary, maxLines = 1)
                        inner()
                    }
                },
            )
            if (text.isNotEmpty()) {
                PlainIconButton(Icons.Rounded.Close, stringResource(R.string.search_clear), { onTextChange("") })
            }
        }
    }
}

@Composable
private fun FilterRow(
    filter: SearchFilter,
    calendars: List<CalendarInfo>,
    onRange: (SearchRange) -> Unit,
    onToggleCalendar: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CelanderTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchRange.entries.forEach { range ->
            SelectableChip(stringResource(range.label), selected = filter.range == range, onClick = { onRange(range) })
        }
        if (calendars.size > 1) {
            Box(Modifier.width(1.dp).height(24.dp).background(colors.divider))
            calendars.forEach { calendar ->
                SelectableChip(
                    text = calendar.name,
                    selected = calendar.id in filter.calendarIds,
                    onClick = { onToggleCalendar(calendar.id) },
                    role = Role.Checkbox,
                    leading = { Box(Modifier.size(10.dp).background(CalendarColor.fromKey(calendar.colorKey).color(), CircleShape)) },
                )
            }
        }
    }
}

@Composable
private fun ResultArea(
    results: SearchResults,
    typed: String,
    today: LocalDate,
    now: Instant,
    zone: ZoneId,
    formats: Formats,
    onOpenOccurrence: (EventOccurrence) -> Unit,
    onOpenTask: (TaskItem) -> Unit,
    onToggleTask: (TaskItem, Boolean) -> Unit,
    onCreateEvent: (String) -> Unit,
    sidePadding: Dp,
) {
    val bottom = WindowInsets.navigationBars.union(WindowInsets.ime).asPaddingValues().calculateBottomPadding()
    when {
        typed.isEmpty() || results is SearchResults.Idle -> EmptyState(
            icon = Icons.Outlined.Search,
            title = stringResource(R.string.search_idle_title),
            message = stringResource(R.string.search_idle_message),
            modifier = Modifier.padding(top = Spacing.xxl),
        )
        results is SearchResults.Found && results.hits.isEmpty() -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = stringResource(R.string.search_no_results, results.query),
            message = stringResource(R.string.search_no_results_message),
            primaryAction = stringResource(R.string.search_create_event, results.query) to { onCreateEvent(results.query) },
            modifier = Modifier.padding(top = Spacing.xxl).semantics { liveRegion = LiveRegionMode.Polite },
        )
        results is SearchResults.Found -> HitList(results, today, now, zone, formats, bottom, sidePadding, onOpenOccurrence, onOpenTask, onToggleTask)
    }
}

@Composable
private fun HitList(
    results: SearchResults.Found,
    today: LocalDate,
    now: Instant,
    zone: ZoneId,
    formats: Formats,
    bottomPadding: Dp,
    sidePadding: Dp,
    onOpenOccurrence: (EventOccurrence) -> Unit,
    onOpenTask: (TaskItem) -> Unit,
    onToggleTask: (TaskItem, Boolean) -> Unit,
) {
    val colors = CelanderTheme.colors
    val listState = rememberLazyListState()
    val groups = remember(results.hits) { results.hits.groupBy { it.date } }
    // New results start at the first upcoming hit (earlier ones are a scroll up away), or at the
    // most recent group when everything is in the past.
    LaunchedEffect(results.query, results.filter) {
        var index = 0
        var lastHeader = 0
        for (hits in groups.values) {
            if (hits.any { !it.past }) {
                listState.scrollToItem(index)
                return@LaunchedEffect
            }
            lastHeader = index
            index += 1 + hits.size
        }
        listState.scrollToItem(lastHeader)
    }
    Text(
        pluralStringResource(R.plurals.search_results_count, results.hits.size, results.hits.size),
        style = CelanderTheme.typography.labelMedium,
        color = colors.textSecondary,
        modifier = Modifier.padding(horizontal = Spacing.xl + sidePadding).semantics { liveRegion = LiveRegionMode.Polite },
    )
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().fadingTopEdge(Spacing.s).elastic(),
        contentPadding = PaddingValues(start = Spacing.m + sidePadding, end = Spacing.m + sidePadding, top = Spacing.xs, bottom = bottomPadding + Spacing.l),
    ) {
        groups.forEach { (date, hits) ->
            stickyHeader(key = "h:$date") {
                DateHeader(date, today, formats, past = hits.all { it.past })
            }
            items(hits, key = { it.key }) { hit ->
                when (hit) {
                    is SearchHit.EventHit -> OccurrenceRow(
                        occurrence = hit.occurrence,
                        date = hit.date,
                        formats = formats,
                        now = now,
                        zone = zone,
                        onClick = { onOpenOccurrence(hit.occurrence) },
                        modifier = Modifier.animateItem(),
                    )
                    is SearchHit.TaskHit -> TaskRow(
                        item = hit.item,
                        formats = formats,
                        overdue = hit.item.task.isOverdue(now.atZone(zone)),
                        onToggle = { onToggleTask(hit.item, it) },
                        onClick = { onOpenTask(hit.item) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DateHeader(date: LocalDate?, today: LocalDate, formats: Formats, past: Boolean) {
    val colors = CelanderTheme.colors
    val isToday = date == today
    Box(Modifier.fillMaxWidth().padding(top = Spacing.m, bottom = Spacing.xs)) {
        Row(
            Modifier
                .glassTile(RoundedCornerShape(50))
                .background(if (isToday) colors.accentSoft else colors.tile.copy(alpha = 0.35f), RoundedCornerShape(50))
                .padding(horizontal = Spacing.m, vertical = Spacing.xs + 2.dp)
                .semantics(mergeDescendants = true) { heading() }
                .dimmed(past),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isToday) {
                Text(stringResource(R.string.today), style = CelanderTheme.typography.labelLarge, color = colors.accent)
                Text(" · ", style = CelanderTheme.typography.labelLarge, color = colors.textSecondary)
            }
            Text(
                if (date == null) stringResource(R.string.search_no_date) else formats.capitalize(date.format(if (date.year == today.year) formats.dayMonthLong else formats.dayLong)),
                style = CelanderTheme.typography.labelLarge,
                color = colors.textPrimary,
            )
        }
    }
}

private val SearchRange.label: Int
    get() = when (this) {
        SearchRange.ALL -> R.string.search_range_all
        SearchRange.FUTURE -> R.string.search_range_future
        SearchRange.PAST -> R.string.search_range_past
    }
