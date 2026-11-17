package com.celander.app.event

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celander.app.R
import com.celander.app.calendar.EditorRequest
import com.celander.app.core.model.EventAccess
import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.EventTiming
import com.celander.app.core.model.ReadOnlyReason
import com.celander.app.core.recurrence.SeriesScope
import com.celander.app.core.ui.Formats
import com.celander.app.designsystem.AccentButton
import com.celander.app.designsystem.CalendarColor
import com.celander.app.designsystem.CelanderTheme
import com.celander.app.designsystem.GlassSheet
import com.celander.app.designsystem.IconRow
import com.celander.app.designsystem.SelectableChip
import com.celander.app.designsystem.SkeletonRows
import com.celander.app.designsystem.SoftButton
import com.celander.app.designsystem.Spacing
import com.celander.app.designsystem.color
import com.celander.app.designsystem.rememberGlassSheetController
import com.celander.app.designsystem.springClickable
import java.time.ZoneId
import kotlinx.coroutines.launch

/** Everything about one occurrence at a glance, with edit, duplicate, share and delete. */
@Composable
fun EventDetailSheet(
    eventId: Long,
    occurrenceStart: Long,
    formats: Formats,
    zone: ZoneId,
    onOpenEditor: (EditorRequest) -> Unit,
    onDismissed: () -> Unit,
    viewModel: EventDetailViewModel = hiltViewModel(key = "event_detail"),
) {
    LaunchedEffect(eventId, occurrenceStart) { viewModel.show(eventId, occurrenceStart) }
    val latest by viewModel.state.collectAsStateWithLifecycle()
    val state = latest.forTarget(eventId, occurrenceStart)
    val controller = rememberGlassSheetController()
    var askScope by rememberSaveable { mutableStateOf(false) }
    var pendingEditor by remember { mutableStateOf<EditorRequest?>(null) }

    LaunchedEffect(state) { if (state is EventDetailUiState.Missing) controller.close() }

    GlassSheet(
        onDismissed = {
            onDismissed()
            pendingEditor?.let(onOpenEditor)
        },
        controller = controller,
        paneTitle = (state as? EventDetailUiState.Ready)?.occurrence?.title ?: stringResource(R.string.event_details),
    ) {
        when (val s = state) {
            EventDetailUiState.Loading, is EventDetailUiState.Missing -> SkeletonRows(3, Modifier.padding(Spacing.xl))
            is EventDetailUiState.Ready -> DetailContent(
                occurrence = s.occurrence,
                formats = formats,
                zone = zone,
                onEdit = {
                    pendingEditor = EditorRequest.EditEvent(s.occurrence.event.id, s.occurrence.originalStart)
                    controller.close()
                },
                onDuplicate = {
                    pendingEditor = EditorRequest.DuplicateEvent(s.occurrence.event.id, s.occurrence.originalStart)
                    controller.close()
                },
                onDelete = {
                    if (s.occurrence.isRecurring) {
                        askScope = true
                    } else {
                        viewModel.delete(s.occurrence, SeriesScope.ALL)
                        controller.close()
                    }
                },
                exceptions = s.exceptions,
                access = s.access,
                onNoApp = viewModel::noAppFor,
            )
        }
    }

    val ready = state as? EventDetailUiState.Ready
    if (askScope && ready != null) {
        ScopeSheet(
            title = R.string.scope_delete_title,
            destructive = true,
            choices = ready.deleteScopes,
            limited = ready.deleteScopes.size < SeriesScope.entries.size,
            onChoose = { scope ->
                viewModel.delete(ready.occurrence, scope)
                controller.close()
            },
            onDismissed = { askScope = false },
        )
    }
}

@Composable
private fun DetailContent(
    occurrence: EventOccurrence,
    exceptions: List<EventException>,
    formats: Formats,
    zone: ZoneId,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    access: EventAccess,
    onNoApp: (Int) -> Unit,
) {
    val colors = CelanderTheme.colors
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var shareOpen by rememberSaveable { mutableStateOf(false) }
    val calendarColor = CalendarColor.fromKey(occurrence.colorKey).color()
    val timing = occurrence.timing
    val firstDay = timing.startDate(zone)
    val lastDay = timing.lastDate(zone)

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl)
            .padding(bottom = Spacing.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs)) {
            Box(Modifier.size(10.dp).background(calendarColor, CircleShape))
            Spacer(Modifier.size(Spacing.s))
            Text(occurrence.calendar.name, style = CelanderTheme.typography.labelLarge, color = colors.textSecondary)
        }
        SelectionContainer {
            Text(
                occurrence.title.ifBlank { stringResource(R.string.untitled) },
                style = CelanderTheme.typography.headlineSmall,
                color = colors.textPrimary,
                modifier = Modifier.padding(top = Spacing.s, bottom = Spacing.m).semantics { heading() },
            )
        }

        IconRow(Icons.Outlined.Schedule) {
            Column(Modifier.padding(vertical = Spacing.xs)) {
                val date = if (firstDay == lastDay) formats.capitalize(firstDay.format(formats.dayLong))
                else stringResource(R.string.time_range, formats.capitalize(firstDay.format(formats.dayMonthShort)), formats.capitalize(lastDay.format(formats.dayLong)))
                Text(date, style = CelanderTheme.typography.bodyLarge, color = colors.textPrimary)
                val time = when (timing) {
                    is EventTiming.AllDay -> stringResource(R.string.all_day)
                    is EventTiming.Timed -> stringResource(
                        R.string.time_range,
                        timing.start.atZone(zone).format(formats.time),
                        timing.end.atZone(zone).format(formats.time),
                    )
                }
                Text(time, style = CelanderTheme.typography.bodyMedium, color = colors.textSecondary)
            }
        }
        val rrule = occurrence.event.rrule
        if (rrule != null) {
            IconRow(Icons.Outlined.Repeat) {
                Text(
                    RecurrenceText.describe(resources, rrule, occurrence.event.timing, formats, zone),
                    style = CelanderTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(vertical = Spacing.xs),
                )
            }
        }
        if (occurrence.location.isNotBlank()) {
            val openMap = stringResource(R.string.action_open_map)
            IconRow(
                Icons.Outlined.Place,
                modifier = Modifier.springClickable(role = Role.Button, onClickLabel = openMap, shape = RoundedCornerShape(12.dp)) {
                    if (!openInMaps(context, occurrence.location)) onNoApp(R.string.error_no_maps_app)
                },
            ) {
                Text(occurrence.location, style = CelanderTheme.typography.bodyMedium, color = colors.accent, modifier = Modifier.padding(vertical = Spacing.xs))
            }
        }
        if (occurrence.description.isNotBlank()) {
            IconRow(Icons.AutoMirrored.Outlined.Notes) {
                SelectionContainer {
                    Text(occurrence.description, style = CelanderTheme.typography.bodyMedium, color = colors.textPrimary, modifier = Modifier.padding(vertical = Spacing.s))
                }
            }
        }

        access.reason?.let { reason ->
            IconRow(Icons.Outlined.Lock) {
                Text(
                    when (reason) {
                        ReadOnlyReason.CALENDAR -> stringResource(R.string.event_read_only_calendar)
                        ReadOnlyReason.GUEST -> access.organizer?.let { stringResource(R.string.event_read_only_guest, it) }
                            ?: stringResource(R.string.event_read_only_guest_plain)
                        ReadOnlyReason.NO_PERMISSION -> stringResource(R.string.event_read_only_permission)
                    },
                    style = CelanderTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(vertical = Spacing.xs),
                )
            }
        }

        Spacer(Modifier.height(Spacing.l))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            if (access.canEdit) AccentButton(stringResource(R.string.action_edit), onEdit, icon = Icons.Outlined.Edit)
            SoftButton(stringResource(R.string.action_duplicate), onDuplicate, icon = Icons.Outlined.ContentCopy)
            SoftButton(stringResource(R.string.action_share), onClick = { shareOpen = !shareOpen }, icon = Icons.Outlined.Share)
            if (access.canDelete) SoftButton(stringResource(R.string.action_delete), onDelete, icon = Icons.Outlined.Delete, contentColor = colors.danger)
        }
        AnimatedVisibility(
            shareOpen,
            enter = CelanderTheme.motion.expandIn(),
            exit = CelanderTheme.motion.shrinkOut(),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                SelectableChip(stringResource(R.string.share_as_text), selected = false, onClick = {
                    EventSharing.shareText(context, occurrence, formats, zone)
                })
                SelectableChip(stringResource(R.string.share_as_ics), selected = false, onClick = {
                    scope.launch { EventSharing.shareIcs(context, occurrence, exceptions) }
                })
            }
        }
    }
}
