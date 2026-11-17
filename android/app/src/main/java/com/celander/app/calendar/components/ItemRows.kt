package com.celander.app.calendar.components

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celander.app.R
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.EventTiming
import com.celander.app.core.model.TaskItem
import com.celander.app.core.ui.Formats
import com.celander.app.designsystem.CalendarColor
import com.celander.app.designsystem.CelanderTheme
import com.celander.app.designsystem.Radii
import com.celander.app.designsystem.Sizes
import com.celander.app.designsystem.Spacing
import com.celander.app.designsystem.TaskCheckbox
import com.celander.app.designsystem.color
import com.celander.app.designsystem.dimmed
import com.celander.app.designsystem.springClickable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val ROW_PRESS_SCALE = 0.985f

/** Time label of an occurrence as seen on [date] (handles all-day and multi-day spans). */
@Composable
fun occurrenceTimeLabel(occurrence: EventOccurrence, date: LocalDate, formats: Formats, zone: ZoneId): String =
    occurrenceTimeLabel(LocalResources.current, occurrence, date, formats, zone)

/** The same label outside Compose, e.g. for home screen widgets. */
fun occurrenceTimeLabel(resources: Resources, occurrence: EventOccurrence, date: LocalDate, formats: Formats, zone: ZoneId): String {
    val timing = occurrence.timing
    if (timing !is EventTiming.Timed) return resources.getString(R.string.all_day)
    val start = timing.start.atZone(zone)
    val end = timing.end.atZone(zone)
    val first = timing.startDate(zone)
    val last = timing.lastDate(zone)
    return when {
        first == last -> resources.getString(R.string.time_range, start.format(formats.time), end.format(formats.time))
        date == first -> resources.getString(R.string.time_from, start.format(formats.time))
        date == last -> resources.getString(R.string.time_until, end.format(formats.time))
        else -> resources.getString(R.string.all_day)
    }
}

/**
 * Event row: time column, calendar color bar, title and location. Past entries are dimmed and a
 * running event is highlighted.
 */
@Composable
fun OccurrenceRow(
    occurrence: EventOccurrence,
    date: LocalDate,
    formats: Formats,
    now: Instant,
    zone: ZoneId,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CelanderTheme.colors
    val color = CalendarColor.fromKey(occurrence.colorKey).color()
    val start = occurrence.timing.startInstant(zone)
    val end = occurrence.timing.endInstant(zone)
    val past = !end.isAfter(now)
    val running = !past && !start.isAfter(now) && !occurrence.allDay
    val time = occurrenceTimeLabel(occurrence, date, formats, zone)
    val title = occurrence.title.ifBlank { stringResource(R.string.untitled) }
    val runningLabel = stringResource(R.string.state_running)
    val description = listOfNotNull(title, time, occurrence.location.takeIf { it.isNotBlank() }, occurrence.calendar.name, runningLabel.takeIf { running })
        .joinToString(", ")

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .springClickable(shape = RoundedCornerShape(Radii.cell), pressedScale = ROW_PRESS_SCALE, onClick = onClick)
            .then(if (running) Modifier.background(colors.accentSoft.copy(alpha = 0.55f)) else Modifier)
            .clearAndSetSemantics {
                contentDescription = description
                onClick { onClick(); true }
            }
            .padding(horizontal = Spacing.m, vertical = Spacing.s)
            .dimmed(past),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(36.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = CelanderTheme.typography.bodyLarge, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(time, style = CelanderTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1)
                if (occurrence.isRecurring) {
                    Spacer(Modifier.width(Spacing.xs))
                    Icon(Icons.Outlined.Repeat, null, tint = colors.textSecondary, modifier = Modifier.width(Sizes.iconSmall - 3.dp))
                }
                if (occurrence.location.isNotBlank()) {
                    Text(
                        " · " + occurrence.location,
                        style = CelanderTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Tasks the user just checked off. They stay visibly checked for a moment before they leave their
 * list, so the tap has a visible result.
 */
val LocalCompletingTasks = compositionLocalOf { emptySet<Long>() }

/** Task row with a round checkbox in the calendar color; overdue tasks show their date in red. */
@Composable
fun TaskRow(
    item: TaskItem,
    formats: Formats,
    overdue: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDate: Boolean = false,
) {
    val colors = CelanderTheme.colors
    val task = item.task
    val color = CalendarColor.fromKey(item.colorKey).color()
    val title = task.title.ifBlank { stringResource(R.string.untitled) }
    val due = buildList {
        if (showDate && task.dueDate != null) add(formats.capitalize(task.dueDate.format(formats.dayMonthShort)))
        if (task.dueTime != null) add(task.dueTime.format(formats.time))
    }.joinToString(", ")
    val checked = task.done || task.id in LocalCompletingTasks.current
    val toggleLabel = stringResource(if (checked) R.string.task_mark_open else R.string.task_mark_done, title)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .springClickable(shape = RoundedCornerShape(Radii.cell), pressedScale = ROW_PRESS_SCALE, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(start = Spacing.xs, end = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskCheckbox(
            checked = checked,
            onCheckedChange = onToggle,
            color = color,
            contentDescription = toggleLabel,
        )
        Column(Modifier.weight(1f).padding(start = Spacing.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = CelanderTheme.typography.bodyLarge,
                color = if (checked) colors.textTertiary else colors.textPrimary,
                textDecoration = if (checked) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (due.isNotEmpty() || overdue) {
                Text(
                    when {
                        !overdue -> due
                        due.isEmpty() -> stringResource(R.string.task_overdue)
                        else -> stringResource(R.string.task_overdue_with_date, due)
                    },
                    style = CelanderTheme.typography.bodySmall,
                    color = if (overdue) colors.danger else colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}
