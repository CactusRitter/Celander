package com.celander.app.event

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.MaterialTheme
import com.celander.app.ui.LocalMotion
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.celander.app.R
import com.celander.app.calendar.components.DateGridPicker
import com.celander.app.core.time.CalendarMath
import com.celander.app.core.ui.Formats
import com.celander.app.ui.theme.CelanderTheme
import com.celander.app.ui.SelectableChip
import com.celander.app.ui.Stepper
import java.time.DayOfWeek
import java.time.LocalDate

/** Collapsible "Repeat" block: kind, weekdays or monthly variant, interval and end. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RepeatSection(
    spec: RepeatSpec,
    summary: String,
    start: LocalDate,
    today: LocalDate,
    firstDay: DayOfWeek,
    formats: Formats,
    onChange: (RepeatSpec) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotion.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, motion.standard(), label = "repeatChevron")

    EditorRow(
        icon = Icons.Outlined.Repeat,
        label = null,
        modifier = Modifier.toggleable(value = expanded, role = Role.Button, onValueChange = { expanded = it }),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.editor_repeat), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ExpandMore, null, tint = colors.onSurfaceVariant, modifier = Modifier.rotate(rotation))
    }
    AnimatedVisibility(
        expanded,
        enter = motion.expandIn(),
        exit = motion.shrinkOut(),
    ) {
        Column(Modifier.padding(start = 44.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val monthly = spec.kind == RepeatKind.MONTHLY_DATE || spec.kind == RepeatKind.MONTHLY_LAST_DAY || spec.kind == RepeatKind.MONTHLY_WEEKDAY
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RepeatChoice(R.string.repeat_none, spec.kind == RepeatKind.NONE) { onChange(RepeatSpec()) }
                RepeatChoice(R.string.repeat_daily, spec.kind == RepeatKind.DAILY) { onChange(spec.switchTo(RepeatKind.DAILY)) }
                RepeatChoice(R.string.repeat_weekly, spec.kind == RepeatKind.WEEKLY) {
                    onChange(spec.switchTo(RepeatKind.WEEKLY).copy(weekdays = setOf(start.dayOfWeek)))
                }
                RepeatChoice(R.string.repeat_monthly, monthly) { if (!monthly) onChange(spec.switchTo(RepeatKind.MONTHLY_DATE)) }
                RepeatChoice(R.string.repeat_yearly, spec.kind == RepeatKind.YEARLY) { onChange(spec.switchTo(RepeatKind.YEARLY)) }
                if (spec.kind == RepeatKind.OTHER) RepeatChoice(R.string.repeat_other, true) {}
            }
            when (spec.kind) {
                RepeatKind.WEEKLY -> WeekdayPicker(spec.weekdays, start, firstDay, formats) { onChange(spec.copy(weekdays = it)) }
                RepeatKind.MONTHLY_DATE, RepeatKind.MONTHLY_LAST_DAY, RepeatKind.MONTHLY_WEEKDAY -> {
                    val resources = LocalResources.current
                    val ordinal = RepeatRules.weekdayOrdinal(start)
                    val ordinalText = if (ordinal == -1) resources.getString(R.string.repeat_last) else resources.getStringArray(R.array.repeat_ordinals)[ordinal - 1]
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectableChip(
                            stringResource(R.string.repeat_monthly_on_date, start.dayOfMonth),
                            selected = spec.kind == RepeatKind.MONTHLY_DATE,
                            onClick = { onChange(spec.copy(kind = RepeatKind.MONTHLY_DATE)) },
                        )
                        if (start.dayOfMonth == start.lengthOfMonth()) {
                            SelectableChip(
                                stringResource(R.string.repeat_monthly_last_day),
                                selected = spec.kind == RepeatKind.MONTHLY_LAST_DAY,
                                onClick = { onChange(spec.copy(kind = RepeatKind.MONTHLY_LAST_DAY)) },
                            )
                        }
                        SelectableChip(
                            stringResource(R.string.repeat_monthly_on_weekday, ordinalText, formats.weekdayLong(start.dayOfWeek)),
                            selected = spec.kind == RepeatKind.MONTHLY_WEEKDAY,
                            onClick = { onChange(spec.copy(kind = RepeatKind.MONTHLY_WEEKDAY)) },
                        )
                    }
                }
                else -> Unit
            }
            if (spec.kind != RepeatKind.NONE && spec.kind != RepeatKind.OTHER) {
                Stepper(
                    value = spec.interval,
                    onValueChange = { onChange(spec.copy(interval = it)) },
                    range = 1..RepeatSpec.MAX_INTERVAL,
                    label = intervalLabel(spec),
                )
                RepeatEndChooser(spec, start, today, firstDay, formats, onChange)
            }
        }
    }
}

private fun RepeatSpec.switchTo(kind: RepeatKind) = copy(kind = kind, raw = null, interval = if (this.kind == kind) interval else 1)

@Composable
private fun RepeatChoice(label: Int, selected: Boolean, onClick: () -> Unit) {
    SelectableChip(stringResource(label), selected = selected, onClick = onClick)
}

@Composable
private fun intervalLabel(spec: RepeatSpec): String {
    val n = spec.interval
    return when (spec.kind) {
        RepeatKind.DAILY -> pluralStringResource(R.plurals.repeat_every_days, n, n)
        RepeatKind.WEEKLY -> pluralStringResource(R.plurals.repeat_every_weeks, n, n)
        RepeatKind.YEARLY -> pluralStringResource(R.plurals.repeat_every_years, n, n)
        else -> pluralStringResource(R.plurals.repeat_every_months, n, n)
    }
}

@Composable
private fun WeekdayPicker(selected: Set<DayOfWeek>, start: LocalDate, firstDay: DayOfWeek, formats: Formats, onChange: (Set<DayOfWeek>) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val days = CalendarMath.weekDays(start, firstDay).map { it.dayOfWeek }
    val chosen = selected.ifEmpty { setOf(start.dayOfWeek) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEach { day ->
            val on = day in chosen
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (on) colors.primary else Color.Transparent)
                    .border(1.dp, if (on) colors.primary else colors.outline, CircleShape)
                    .toggleable(value = on, role = Role.Checkbox) { checked ->
                        // At least one weekday stays selected.
                        val next = if (checked) chosen + day else chosen - day
                        if (next.isNotEmpty()) onChange(next)
                    }
                    .semantics { contentDescription = formats.weekdayLong(day) },
                contentAlignment = Alignment.Center,
            ) {
                Text(formats.weekdayNarrow(day), style = MaterialTheme.typography.labelLarge, color = if (on) colors.onPrimary else colors.onSurface)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepeatEndChooser(spec: RepeatSpec, start: LocalDate, today: LocalDate, firstDay: DayOfWeek, formats: Formats, onChange: (RepeatSpec) -> Unit) {
    val motion = LocalMotion.current
    var pickDate by rememberSaveable { mutableStateOf(false) }
    val until = spec.until ?: start.plusMonths(1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.repeat_ends), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectableChip(stringResource(R.string.repeat_end_never), spec.end == RepeatEnd.NEVER, onClick = { onChange(spec.copy(end = RepeatEnd.NEVER)) })
            SelectableChip(
                if (spec.end == RepeatEnd.UNTIL) stringResource(R.string.repeat_until, formats.capitalize(until.format(formats.dateMedium)))
                else stringResource(R.string.repeat_end_on_date),
                spec.end == RepeatEnd.UNTIL,
                onClick = {
                    if (spec.end == RepeatEnd.UNTIL) pickDate = !pickDate else onChange(spec.copy(end = RepeatEnd.UNTIL, until = until))
                },
            )
            SelectableChip(stringResource(R.string.repeat_end_after), spec.end == RepeatEnd.COUNT, onClick = { onChange(spec.copy(end = RepeatEnd.COUNT)) })
        }
        AnimatedVisibility(spec.end == RepeatEnd.UNTIL && pickDate, enter = motion.expandIn(), exit = motion.shrinkOut()) {
            DateGridPicker(
                selected = until,
                today = today,
                firstDay = firstDay,
                formats = formats,
                onPick = { date ->
                    onChange(spec.copy(until = maxOf(date, start)))
                    pickDate = false
                },
            )
        }
        if (spec.end == RepeatEnd.COUNT) {
            Stepper(
                value = spec.count,
                onValueChange = { onChange(spec.copy(count = it)) },
                range = 1..RepeatSpec.MAX_COUNT,
                label = pluralStringResource(R.plurals.repeat_after_count, spec.count, spec.count),
            )
        }
    }
}
