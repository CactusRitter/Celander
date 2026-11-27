package com.celander.app.intake

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celander.app.R
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.EventTiming
import com.celander.app.core.ui.Formats
import com.celander.app.event.CalendarChooser
import com.celander.app.event.EditorRow
import com.celander.app.event.EditorSection
import com.celander.app.event.MAX_REMINDERS
import com.celander.app.event.RecurrenceText
import com.celander.app.event.RemindersSection
import com.celander.app.notifications.ReminderWarning
import com.celander.app.notifications.rememberNotificationAccess
import com.ce.kit.ui.AccentButton
import com.ce.kit.ui.GlassDialog
import com.ce.kit.ui.GlassSheet
import com.ce.kit.ui.PlainIconButton
import com.ce.kit.ui.SoftButton
import com.ce.kit.ui.TextAction
import com.ce.kit.ui.glass.glassTile
import com.ce.kit.ui.rememberGlassSheetController
import com.ce.kit.ui.springClickable
import java.time.ZoneId

/** How many other entries a conflict notice names. */
private const val CONFLICTS_SHOWN = 4

/** The review of an .ics before anything is saved: one entry in detail, or the list of several. */
@Composable
fun ReviewSheet(
    state: IntakeState.Review,
    formats: Formats,
    zone: ZoneId,
    onSelect: (Int?) -> Unit,
    onCalendar: (Long) -> Unit,
    onReminders: (List<Int>) -> Unit,
    onConfirm: () -> Unit,
    onEdit: (IncomingEvent) -> Unit,
    onShowInCalendar: (IntakeItem) -> Unit,
    onDismissed: () -> Unit,
) {
    val controller = rememberGlassSheetController()
    val item = state.selected?.let(state.items::getOrNull)
    var askDelete by rememberSaveable { mutableStateOf(false) }
    val notifications = rememberNotificationAccess()
    val title = when {
        item == null -> pluralStringResource(R.plurals.intake_title_list, state.items.size, state.items.size)
        item.incoming.cancel -> stringResource(R.string.intake_title_cancel)
        item.verdict == Verdict.UPDATE -> stringResource(R.string.intake_title_update)
        item.existing != null -> stringResource(R.string.kind_event)
        else -> stringResource(R.string.intake_title_add)
    }

    val inList = item != null && state.items.size > 1
    GlassSheet(
        onDismissed = onDismissed,
        controller = controller,
        expandable = true,
        paneTitle = title,
        // Back (and swiping down) from one of several entries leads to the list first.
        confirmDismiss = {
            if (inList) onSelect(null)
            !inList
        },
    ) {
        val action = item?.takeIf { it.applied == null }?.let {
            when (it.verdict) {
                Verdict.NEW -> R.string.intake_add
                Verdict.UPDATE -> R.string.intake_update
                else -> null
            }
        }
        SheetHeader(
            title = title,
            back = inList,
            onLeading = { if (inList) onSelect(null) else controller.close() },
        ) {
            if (item != null && action != null) {
                AccentButton(
                    stringResource(action),
                    {
                        if (item.verdict == Verdict.NEW && item.reminders.isNotEmpty()) notifications.askOnce()
                        onConfirm()
                    },
                    enabled = item.verdict != Verdict.NEW || item.calendarId != null,
                )
            }
        }
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
        ) {
            if (item == null) {
                EntryList(state, formats, zone, onSelect)
            } else {
                Status(item)
                Details(item, formats, zone)
                Conflicts(item.timing, item.conflicts, formats, zone)
                if (item.applied == null) {
                    if (item.verdict == Verdict.NEW) {
                        CalendarChooser(state.calendars, item.calendarId, onCalendar, Modifier.padding(top = 8.dp))
                        EditorSection {
                            RemindersSection(
                                reminders = item.reminders,
                                allDay = item.incoming.shown.allDay,
                                task = false,
                                formats = formats,
                                onChange = onReminders,
                                maxCount = state.calendars.firstOrNull { it.id == item.calendarId }?.maxReminders ?: MAX_REMINDERS,
                                warning = { if (item.reminders.isNotEmpty()) ReminderWarning(notifications, task = false) },
                            )
                        }
                        if (item.incoming.editable) {
                            SoftButton(
                                stringResource(R.string.intake_edit),
                                { onEdit(item.incoming) },
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        }
                    }
                    if (item.verdict == Verdict.CANCEL) {
                        SoftButton(
                            stringResource(R.string.intake_delete),
                            { askDelete = true },
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            contentColor = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        if (item.applied != Applied.DELETED) {
                            TextAction(stringResource(R.string.intake_show), { onShowInCalendar(item) })
                        }
                        TextAction(
                            stringResource(if (inList) R.string.intake_back_to_list else R.string.intake_done),
                            { if (inList) onSelect(null) else controller.close() },
                        )
                    }
                }
            }
        }
    }

    GlassDialog(
        visible = askDelete,
        title = stringResource(R.string.intake_delete_title),
        message = item?.let {
            val name = it.title.ifBlank { stringResource(R.string.untitled) }
            val whole = it.existing == null || IntakePlan.deletesWholeEvent(it.incoming, it.existing)
            stringResource(if (whole) R.string.intake_delete_message else R.string.intake_delete_occurrence_message, name)
        }.orEmpty(),
        confirmLabel = stringResource(R.string.intake_delete),
        onConfirm = {
            askDelete = false
            onConfirm()
        },
        onDismiss = { askDelete = false },
        destructive = true,
    )
}

/** A sheet that only says why there is nothing to take over. */
@Composable
fun MessageSheet(text: Int, onDismissed: () -> Unit) {
    val controller = rememberGlassSheetController()
    val title = stringResource(R.string.intake_title_message)
    GlassSheet(onDismissed = onDismissed, controller = controller, paneTitle = title) {
        SheetHeader(title = title, back = false, onLeading = { controller.close() }) {}
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun SheetHeader(title: String, back: Boolean, onLeading: () -> Unit, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back) {
            PlainIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.intake_back_to_list), onLeading)
        } else {
            PlainIconButton(Icons.Rounded.Close, stringResource(R.string.action_close), onLeading)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
        )
        trailing()
    }
}

private fun statusText(item: IntakeItem): Int = when (item.applied) {
    Applied.ADDED -> R.string.intake_added
    Applied.UPDATED -> R.string.intake_updated
    Applied.DELETED -> R.string.intake_deleted
    null -> when (item.verdict) {
        Verdict.NEW -> R.string.intake_status_new
        Verdict.UPDATE -> R.string.intake_status_update
        Verdict.UNCHANGED -> if (item.incoming.cancel) R.string.intake_status_removed else R.string.intake_status_unchanged
        Verdict.OUTDATED -> R.string.intake_status_outdated
        Verdict.CANCEL -> R.string.intake_status_cancel
        Verdict.NOT_FOUND -> R.string.intake_status_not_found
        Verdict.READ_ONLY -> R.string.intake_status_read_only
    }
}

@Composable
private fun Status(item: IntakeItem) {
    // A new entry needs no explanation: the title and the button say what happens.
    if (item.applied == null && item.verdict == Verdict.NEW) return
    Text(
        stringResource(statusText(item)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .glassTile(RoundedCornerShape(18.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** "Tuesday, 3 November 2026 · 14:30 – 16:00", or the days of an all-day entry. */
@Composable
private fun whenText(timing: EventTiming, formats: Formats, zone: ZoneId): Pair<String, String> {
    val first = timing.startDate(zone)
    val last = timing.lastDate(zone)
    val date = if (first == last) {
        formats.capitalize(first.format(formats.dayLong))
    } else {
        stringResource(R.string.time_range, formats.capitalize(first.format(formats.dayMonthShort)), formats.capitalize(last.format(formats.dayLong)))
    }
    val time = when (timing) {
        is EventTiming.AllDay -> stringResource(R.string.all_day)
        is EventTiming.Timed -> stringResource(R.string.time_range, timing.start.atZone(zone).format(formats.time), timing.end.atZone(zone).format(formats.time))
    }
    return date to time
}

@Composable
private fun Details(item: IntakeItem, formats: Formats, zone: ZoneId) {
    val colors = MaterialTheme.colorScheme
    val resources = LocalResources.current
    Text(
        item.title.ifBlank { stringResource(R.string.untitled) },
        style = MaterialTheme.typography.headlineSmall,
        color = colors.onSurface,
        modifier = Modifier.padding(vertical = 8.dp).semantics { heading() },
    )
    EditorSection {
        val (date, time) = whenText(item.timing, formats, zone)
        EditorRow(Icons.Outlined.Schedule, null) {
            Column(Modifier.weight(1f)) {
                Text(date, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                Text(time, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        item.rrule?.let { rrule ->
            DetailRow(Icons.Outlined.Repeat, RecurrenceText.describe(resources, rrule, item.timing, formats, zone))
        }
        if (item.location.isNotBlank()) DetailRow(Icons.Outlined.Place, item.location)
        if (item.description.isNotBlank()) DetailRow(Icons.AutoMirrored.Outlined.Notes, item.description, maxLines = 8)
        // Where it is already; a new entry has its calendar chooser below.
        item.existing?.calendarName?.takeIf { it.isNotBlank() }?.let { DetailRow(Icons.Outlined.Event, it) }
    }
}

@Composable
private fun DetailRow(icon: ImageVector, text: String, maxLines: Int = 3) {
    EditorRow(icon, null) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
        )
    }
}

/** What else is on at that time; nothing if it is free. Also shown in the editor of an incoming event. */
@Composable
fun Conflicts(timing: EventTiming, conflicts: List<EventOccurrence>, formats: Formats, zone: ZoneId) {
    if (conflicts.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .glassTile(RoundedCornerShape(18.dp), tint = colors.error.copy(alpha = 0.14f))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            stringResource(if (timing is EventTiming.AllDay) R.string.intake_conflicts_day else R.string.intake_conflicts_time),
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
        )
        conflicts.take(CONFLICTS_SHOWN).forEach { other ->
            val time = when (val t = other.timing) {
                is EventTiming.AllDay -> stringResource(R.string.all_day)
                is EventTiming.Timed -> stringResource(R.string.time_range, t.start.atZone(zone).format(formats.time), t.end.atZone(zone).format(formats.time))
            }
            Text(
                "$time · ${other.title.ifBlank { stringResource(R.string.untitled) }}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val more = conflicts.size - CONFLICTS_SHOWN
        if (more > 0) {
            Text(pluralStringResource(R.plurals.intake_conflicts_more, more, more), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun ColumnScope.EntryList(state: IntakeState.Review, formats: Formats, zone: ZoneId, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    state.items.forEachIndexed { index, item ->
        Box(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .springClickable(role = Role.Button, shape = RoundedCornerShape(12.dp)) { onSelect(index) }
                .glassTile(RoundedCornerShape(12.dp))
                .heightIn(min = 52.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column {
                Text(item.title.ifBlank { stringResource(R.string.untitled) }, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val (date, time) = whenText(item.timing, formats, zone)
                Text("$date · $time", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Text(stringResource(statusText(item)), style = MaterialTheme.typography.bodySmall, color = colors.primary)
            }
        }
    }
    if (state.truncated) {
        Text(
            stringResource(R.string.intake_truncated, Incoming.MAX_ENTRIES),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
