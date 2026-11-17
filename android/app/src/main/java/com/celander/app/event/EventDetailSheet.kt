package com.celander.app.event

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.MaterialTheme
import com.celander.app.ui.LocalMotion
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
import com.celander.app.ui.AccentButton
import com.celander.app.ui.CalendarColor
import com.celander.app.ui.theme.CelanderTheme
import com.celander.app.ui.GlassSheet
import com.celander.app.ui.IconRow
import com.celander.app.ui.SelectableChip
import com.celander.app.ui.SkeletonRows
import com.celander.app.ui.SoftButton
import com.celander.app.ui.color
import com.celander.app.ui.rememberGlassSheetController
import com.celander.app.ui.springClickable
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
            EventDetailUiState.Loading, is EventDetailUiState.Missing -> SkeletonRows(3, Modifier.padding(20.dp))
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
    val colors = MaterialTheme.colorScheme
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
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Box(Modifier.size(10.dp).background(calendarColor, CircleShape))
            Spacer(Modifier.size(8.dp))
            Text(occurrence.calendar.name, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        }
        SelectionContainer {
            Text(
                occurrence.title.ifBlank { stringResource(R.string.untitled) },
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onSurface,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp).semantics { heading() },
            )
        }

        IconRow(Icons.Outlined.Schedule) {
            Column(Modifier.padding(vertical = 4.dp)) {
                val date = if (firstDay == lastDay) formats.capitalize(firstDay.format(formats.dayLong))
                else stringResource(R.string.time_range, formats.capitalize(firstDay.format(formats.dayMonthShort)), formats.capitalize(lastDay.format(formats.dayLong)))
                Text(date, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                val time = when (timing) {
                    is EventTiming.AllDay -> stringResource(R.string.all_day)
                    is EventTiming.Timed -> stringResource(
                        R.string.time_range,
                        timing.start.atZone(zone).format(formats.time),
                        timing.end.atZone(zone).format(formats.time),
                    )
                }
                Text(time, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        val rrule = occurrence.event.rrule
        if (rrule != null) {
            IconRow(Icons.Outlined.Repeat) {
                Text(
                    RecurrenceText.describe(resources, rrule, occurrence.event.timing, formats, zone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                    modifier = Modifier.padding(vertical = 4.dp),
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
                Text(occurrence.location, style = MaterialTheme.typography.bodyMedium, color = colors.primary, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
        if (occurrence.description.isNotBlank()) {
            IconRow(Icons.AutoMirrored.Outlined.Notes) {
                SelectionContainer {
                    Text(occurrence.description, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.padding(vertical = 8.dp))
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
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (access.canEdit) AccentButton(stringResource(R.string.action_edit), onEdit, icon = Icons.Outlined.Edit)
            SoftButton(stringResource(R.string.action_duplicate), onDuplicate, icon = Icons.Outlined.ContentCopy)
            SoftButton(stringResource(R.string.action_share), onClick = { shareOpen = !shareOpen }, icon = Icons.Outlined.Share)
            if (access.canDelete) SoftButton(stringResource(R.string.action_delete), onDelete, icon = Icons.Outlined.Delete, contentColor = colors.error)
        }
        AnimatedVisibility(
            shareOpen,
            enter = LocalMotion.current.expandIn(),
            exit = LocalMotion.current.shrinkOut(),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
