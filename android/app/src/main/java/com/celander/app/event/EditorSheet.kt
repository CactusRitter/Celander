package com.celander.app.event

import androidx.compose.animation.AnimatedContent
import com.ce.kit.ui.glass.glassTile
import androidx.compose.material3.MaterialTheme
import com.ce.kit.ui.LocalMotion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celander.app.R
import com.celander.app.calendar.EditorRequest
import com.celander.app.calendar.components.DateGridPicker
import com.celander.app.calendar.components.TimeWheelPicker
import com.celander.app.core.model.CalendarInfo
import com.celander.app.core.model.EntryKind
import com.celander.app.core.ui.Formats
import com.ce.kit.ui.AccentButton
import com.celander.app.ui.CalendarColor
import com.ce.kit.ui.theme.CeTheme
import com.ce.kit.ui.ColorSwatch
import com.ce.kit.ui.GlassSheet
import com.ce.kit.ui.HairlineDivider
import com.ce.kit.ui.MotionTokens
import com.ce.kit.ui.PlainIconButton
import com.ce.kit.ui.SegmentedControl
import com.ce.kit.ui.SelectableChip
import com.celander.app.ui.Sizes
import com.celander.app.ui.SkeletonRows
import com.ce.kit.ui.SoftButton
import com.ce.kit.ui.TextAction
import com.celander.app.ui.color
import com.celander.app.ui.rememberEntranceWindow
import com.ce.kit.ui.rememberGlassSheetController
import com.celander.app.ui.staggeredEntrance
import com.celander.app.notifications.NotificationAccess
import com.celander.app.notifications.ReminderWarning
import com.celander.app.notifications.rememberNotificationAccess
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.delay

private enum class PickerTarget { START_DATE, START_TIME, END_DATE, END_TIME, DUE_DATE, DUE_TIME }

/**
 * Creates and edits events and tasks. Unsaved changes are only questioned when there are any,
 * inline instead of a dialog.
 */
@Composable
fun EditorSheet(
    request: EditorRequest,
    formats: Formats,
    today: LocalDate,
    firstDay: DayOfWeek,
    zone: ZoneId,
    onSaved: (EditorOutcome.Saved) -> Unit,
    onDismissed: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel(key = "editor"),
) {
    LaunchedEffect(request) { viewModel.open(request) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controller = rememberGlassSheetController()
    val focusManager = LocalFocusManager.current
    var askDiscard by rememberSaveable { mutableStateOf(false) }
    var savedOutcome by remember { mutableStateOf<EditorOutcome.Saved?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.outcomes.collect { outcome ->
            savedOutcome = outcome as? EditorOutcome.Saved
            controller.close(force = true)
        }
    }

    // Saving clears the draft right away; keep showing it while the sheet animates out.
    val draft = rememberLastNonNull(state.draft)
    val titleFocus = remember { FocusRequester() }
    val notifications = rememberNotificationAccess()

    GlassSheet(
        onDismissed = {
            viewModel.reset()
            onDismissed()
            savedOutcome?.let(onSaved)
        },
        controller = controller,
        expandable = true,
        paneTitle = stringResource(headerTitle(draft)),
        confirmDismiss = {
            if (state.dirty) {
                focusManager.clearFocus()
                askDiscard = true
                false
            } else {
                true
            }
        },
    ) {
        if (draft == null) {
            SkeletonRows(4, Modifier.padding(20.dp))
        } else {
            EditorContent(
                state = state,
                draft = draft,
                askDiscard = askDiscard,
                formats = formats,
                today = today,
                firstDay = firstDay,
                zone = zone,
                titleFocus = titleFocus,
                notifications = notifications,
                viewModel = viewModel,
                onClose = { controller.close(force = false) },
                onKeepEditing = { askDiscard = false },
                onDiscard = { controller.close(force = true) },
            )
        }
    }

    // Above the editor: which occurrences of the series the change applies to.
    state.scopeChoices?.let { choices ->
        ScopeSheet(
            title = R.string.scope_edit_title,
            destructive = false,
            choices = choices,
            onChoose = viewModel::saveWithScope,
            onDismissed = viewModel::cancelScope,
        )
    }
}

@Composable
private fun ColumnScope.EditorContent(
    state: EditorUiState,
    draft: EditorDraft,
    askDiscard: Boolean,
    formats: Formats,
    today: LocalDate,
    firstDay: DayOfWeek,
    zone: ZoneId,
    titleFocus: FocusRequester,
    notifications: NotificationAccess,
    viewModel: EditorViewModel,
    onClose: () -> Unit,
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    EditorHeader(
        draft = draft,
        canSwitchKind = state.canSwitchKind,
        canSave = state.canSave,
        onKind = viewModel::setKind,
        onClose = onClose,
        onSave = {
            if (draft.title.isBlank()) runCatching { titleFocus.requestFocus() } else focusManager.clearFocus()
            // Reminders of the phone's calendars come from the calendar app, not from Celander.
            if (draft.reminders.isNotEmpty() && (draft.kind == EntryKind.EVENT || draft.hasDueDate)) notifications.askOnce()
            viewModel.save()
        },
    )
    DiscardBanner(visible = askDiscard, onKeepEditing = onKeepEditing, onDiscard = onDiscard)
    EditorBody(
        state = state,
        draft = draft,
        formats = formats,
        today = today,
        firstDay = firstDay,
        zone = zone,
        titleFocus = titleFocus,
        notifications = notifications,
        viewModel = viewModel,
    )
}

@Composable
private fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return holder[0] as T?
}

private fun headerTitle(draft: EditorDraft?): Int = when {
    draft == null -> R.string.editor_new_event
    draft.kind == EntryKind.TASK && draft.isNew -> R.string.editor_new_task
    draft.kind == EntryKind.TASK -> R.string.editor_edit_task
    draft.isNew -> R.string.editor_new_event
    else -> R.string.editor_edit_event
}

@Composable
private fun EditorHeader(
    draft: EditorDraft,
    canSwitchKind: Boolean,
    canSave: Boolean,
    onKind: (EntryKind) -> Unit,
    onClose: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlainIconButton(Icons.Rounded.Close, stringResource(R.string.action_close), onClose)
        Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            if (canSwitchKind) {
                SegmentedControl(
                    options = listOf(stringResource(R.string.kind_event), stringResource(R.string.kind_task)),
                    selectedIndex = draft.kind.ordinal,
                    onSelect = { onKind(EntryKind.entries[it]) },
                    modifier = Modifier.widthIn(max = 260.dp),
                )
            } else {
                Text(
                    stringResource(headerTitle(draft)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
        AccentButton(stringResource(R.string.action_save), onSave, enabled = canSave)
    }
}

@Composable
private fun DiscardBanner(visible: Boolean, onKeepEditing: () -> Unit, onDiscard: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotion.current
    AnimatedVisibility(
        visible,
        enter = motion.expandIn(),
        exit = motion.shrinkOut(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .glassTile(RoundedCornerShape(18.dp), tint = colors.error.copy(alpha = 0.14f))
                .padding(start = 16.dp, end = 4.dp, top = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(stringResource(R.string.editor_discard_title), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            Text(stringResource(R.string.editor_discard_message), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextAction(stringResource(R.string.editor_keep_editing), onKeepEditing)
                TextAction(stringResource(R.string.editor_discard), onDiscard, color = colors.error)
            }
        }
    }
}

@Composable
private fun ColumnScope.EditorBody(
    state: EditorUiState,
    draft: EditorDraft,
    formats: Formats,
    today: LocalDate,
    firstDay: DayOfWeek,
    zone: ZoneId,
    titleFocus: FocusRequester,
    notifications: NotificationAccess,
    viewModel: EditorViewModel,
) {
    val motion = LocalMotion.current
    val focusManager = LocalFocusManager.current
    var picker by rememberSaveable { mutableStateOf<PickerTarget?>(null) }
    val togglePicker: (PickerTarget) -> Unit = { target ->
        focusManager.clearFocus()
        picker = if (picker == target) null else target
    }
    val entrance by rememberEntranceWindow(draft.requestKey)

    LaunchedEffect(draft.requestKey) {
        if (draft.isNew && draft.title.isEmpty()) {
            // Let the sheet settle before the keyboard pushes it up.
            delay(MotionTokens.LONG.toLong())
            runCatching { titleFocus.requestFocus() }
        }
    }

    Column(
        Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 20.dp),
    ) {
        TitleField(
            value = draft.title,
            kind = draft.kind,
            error = state.titleError,
            focusRequester = titleFocus,
            onValueChange = viewModel::setTitle,
            modifier = Modifier.staggeredEntrance(0, entrance),
        )
        if (state.titleTooLong) TooLong(EditorUiState.GOOGLE_TITLE_MAX)
        CalendarChooser(
            calendars = state.calendars,
            selectedId = draft.calendarId,
            onSelect = viewModel::setCalendar,
            modifier = Modifier.staggeredEntrance(1, entrance),
        )
        AnimatedContent(
            targetState = draft.kind,
            transitionSpec = { fadeIn(motion.enter()) togetherWith fadeOut(motion.exit()) using SizeTransform(clip = false) },
            label = "editorKind",
        ) { kind ->
            Column {
                when (kind) {
                    EntryKind.EVENT -> EventFields(state, draft, formats, today, firstDay, zone, picker, togglePicker, notifications, viewModel, entrance)
                    EntryKind.TASK -> TaskFields(draft, state, formats, today, firstDay, picker, togglePicker, notifications, viewModel, entrance)
                }
            }
        }
    }
}

@Composable
private fun TitleField(
    value: String,
    kind: EntryKind,
    error: Boolean,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotion.current
    val density = LocalDensity.current
    val shake = remember { Animatable(0f) }
    val placeholder = stringResource(if (kind == EntryKind.TASK) R.string.editor_title_task else R.string.editor_title_event)
    val errorText = stringResource(R.string.editor_title_required)
    LaunchedEffect(error) {
        if (error && !motion.reduced) {
            for (dx in listOf(10f, -8f, 6f, -3f, 0f)) shake.animateTo(dx, tween(50))
        }
    }
    val style = MaterialTheme.typography.headlineSmall
    Column(modifier.padding(top = 8.dp, bottom = 12.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = style.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            maxLines = 3,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .graphicsLayer { translationX = with(density) { shake.value.dp.toPx() } }
                .semantics {
                    contentDescription = placeholder
                    if (error) error(errorText)
                },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = style, color = colors.onSurfaceVariant)
                    inner()
                }
            },
        )
        AnimatedVisibility(error, enter = motion.expandIn(), exit = motion.shrinkOut()) {
            Text(
                errorText,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun CalendarChooser(calendars: List<CalendarInfo>, selectedId: Long?, onSelect: (Long) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        calendars.forEach { calendar ->
            val dot = CalendarColor.fromKey(calendar.colorKey).color()
            SelectableChip(
                text = calendar.name,
                selected = calendar.id == selectedId,
                onClick = { onSelect(calendar.id) },
                leading = { Box(Modifier.size(10.dp).background(dot, CircleShape)) },
            )
        }
    }
}

@Composable
private fun EventFields(
    state: EditorUiState,
    draft: EditorDraft,
    formats: Formats,
    today: LocalDate,
    firstDay: DayOfWeek,
    zone: ZoneId,
    picker: PickerTarget?,
    togglePicker: (PickerTarget) -> Unit,
    notifications: NotificationAccess,
    viewModel: EditorViewModel,
    entrance: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotion.current
    EditorSection(Modifier.staggeredEntrance(2, entrance)) {
        SwitchRow(Icons.Outlined.Schedule, stringResource(R.string.all_day), draft.allDay, viewModel::setAllDay)
        HairlineDivider()
        DateTimeRow(
            label = stringResource(R.string.editor_starts),
            date = draft.startDate,
            time = draft.startTime.takeUnless { draft.allDay },
            today = today,
            formats = formats,
            picker = picker,
            dateTarget = PickerTarget.START_DATE,
            timeTarget = PickerTarget.START_TIME,
            onToggle = togglePicker,
        )
        PickerPanel(picker == PickerTarget.START_DATE) {
            DateGridPicker(draft.startDate, today, firstDay, formats, onPick = { viewModel.setStart(it, draft.startTime) })
        }
        PickerPanel(picker == PickerTarget.START_TIME && !draft.allDay) {
            TimeWheelPicker(draft.startTime, formats, onPick = { viewModel.setStart(draft.startDate, it) })
        }
        DateTimeRow(
            label = stringResource(R.string.editor_ends),
            date = draft.endDate,
            time = draft.endTime.takeUnless { draft.allDay },
            today = today,
            formats = formats,
            picker = picker,
            dateTarget = PickerTarget.END_DATE,
            timeTarget = PickerTarget.END_TIME,
            onToggle = togglePicker,
        )
        PickerPanel(picker == PickerTarget.END_DATE) {
            DateGridPicker(draft.endDate, today, firstDay, formats, onPick = { viewModel.setEnd(it, draft.endTime) })
        }
        PickerPanel(picker == PickerTarget.END_TIME && !draft.allDay) {
            TimeWheelPicker(draft.endTime, formats, onPick = { viewModel.setEnd(draft.endDate, it) })
        }
        AnimatedVisibility(
            state.endAdjusted,
            enter = motion.expandIn(),
            exit = motion.shrinkOut(),
        ) {
            Text(
                stringResource(R.string.editor_end_adjusted),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 44.dp, bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        AnimatedVisibility(draft.endsTooEarly, enter = motion.expandIn(), exit = motion.shrinkOut()) {
            Text(
                stringResource(R.string.editor_end_before_start),
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                modifier = Modifier.padding(start = 44.dp, bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        HairlineDivider()
        val resources = LocalResources.current
        val rrule = draft.rrule
        RepeatSection(
            spec = draft.repeat,
            summary = if (rrule == null) stringResource(R.string.repeat_none) else RecurrenceText.describe(resources, rrule, draft.timing(zone), formats, draft.eventZone),
            start = draft.startDate,
            today = today,
            firstDay = firstDay,
            formats = formats,
            onChange = viewModel::setRepeat,
        )
    }
    EditorSection(Modifier.staggeredEntrance(3, entrance)) {
        RemindersSection(
            reminders = draft.reminders,
            allDay = draft.allDay,
            task = false,
            formats = formats,
            onChange = viewModel::setReminders,
            warning = { if (draft.reminders.isNotEmpty()) ReminderWarning(notifications, task = false) },
        )
    }
    EditorSection(Modifier.staggeredEntrance(4, entrance)) {
        val context = LocalContext.current
        EditorTextField(
            draft.location,
            viewModel::setLocation,
            stringResource(R.string.editor_location),
            Icons.Outlined.Place,
            modifier = Modifier.onFocusChanged { if (!it.hasFocus) viewModel.endPlaceSearch() },
            // Free text stays free; the pin checks what a maps app finds for it.
            trailing = if (draft.location.isBlank()) null else {
                {
                    PlainIconButton(Icons.Outlined.Map, stringResource(R.string.action_open_map), {
                        if (!openInMaps(context, draft.location.trim())) viewModel.noAppFor(R.string.error_no_maps_app)
                    })
                }
            },
        )
        val places by viewModel.placeSuggestions.collectAsStateWithLifecycle()
        // The last list stays while it folds away.
        val shownPlaces = rememberLastNonNull(places.takeIf { it.isNotEmpty() })
        AnimatedVisibility(places.isNotEmpty(), enter = LocalMotion.current.expandIn(), exit = LocalMotion.current.shrinkOut()) {
            PlaceSuggestions(shownPlaces.orEmpty(), onPick = viewModel::pickPlace)
        }
        HairlineDivider()
        EditorTextField(
            draft.description,
            viewModel::setDescription,
            stringResource(R.string.editor_description),
            Icons.AutoMirrored.Outlined.Notes,
            singleLine = false,
        )
    }
    ColorChooser(
        colorKey = draft.colorKey,
        calendarColorKey = state.calendars.firstOrNull { it.id == draft.calendarId }?.colorKey,
        onSelect = viewModel::setColor,
        modifier = Modifier.staggeredEntrance(5, entrance),
    )
}

/** Inline error for text Google Tasks cannot store; saving waits until it is shorter. */
@Composable
private fun TooLong(max: Int) {
    Text(
        pluralStringResource(R.plurals.editor_text_too_long, max, max),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun TaskFields(
    draft: EditorDraft,
    state: EditorUiState,
    formats: Formats,
    today: LocalDate,
    firstDay: DayOfWeek,
    picker: PickerTarget?,
    togglePicker: (PickerTarget) -> Unit,
    notifications: NotificationAccess,
    viewModel: EditorViewModel,
    entrance: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotion.current
    EditorSection(Modifier.staggeredEntrance(2, entrance)) {
        SwitchRow(Icons.Outlined.Event, stringResource(R.string.editor_due_date), draft.hasDueDate, viewModel::setHasDueDate)
        AnimatedVisibility(
            draft.hasDueDate,
            enter = motion.expandIn(),
            exit = motion.shrinkOut(),
        ) {
            Column {
                HairlineDivider()
                DateTimeRow(
                    label = stringResource(R.string.editor_due_on),
                    date = draft.startDate,
                    time = null,
                    today = today,
                    formats = formats,
                    picker = picker,
                    dateTarget = PickerTarget.DUE_DATE,
                    timeTarget = PickerTarget.DUE_TIME,
                    onToggle = togglePicker,
                )
                PickerPanel(picker == PickerTarget.DUE_DATE) {
                    DateGridPicker(draft.startDate, today, firstDay, formats, onPick = { viewModel.setDue(it, draft.startTime) })
                }
                HairlineDivider()
                SwitchRow(Icons.Outlined.Schedule, stringResource(R.string.editor_due_time), draft.hasDueTime, viewModel::setHasDueTime) {
                    if (draft.hasDueTime) {
                        ValueChip(
                            text = draft.startTime.format(formats.time),
                            selected = picker == PickerTarget.DUE_TIME,
                            onClick = { togglePicker(PickerTarget.DUE_TIME) },
                            label = stringResource(R.string.editor_due_time),
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                PickerPanel(picker == PickerTarget.DUE_TIME && draft.hasDueTime) {
                    TimeWheelPicker(draft.startTime, formats, onPick = { viewModel.setDue(draft.startDate, it) })
                }
                HairlineDivider()
                RemindersSection(
                    reminders = draft.reminders,
                    allDay = false,
                    task = true,
                    formats = formats,
                    onChange = viewModel::setReminders,
                    warning = { if (draft.reminders.isNotEmpty()) ReminderWarning(notifications, task = true) },
                    note = if (state.googleTask) stringResource(R.string.editor_google_task_local_only) else null,
                )
            }
        }
    }
    EditorSection(Modifier.staggeredEntrance(3, entrance)) {
        EditorTextField(draft.note, viewModel::setNote, stringResource(R.string.editor_note), Icons.AutoMirrored.Outlined.Notes, singleLine = false)
        if (state.noteTooLong) TooLong(EditorUiState.GOOGLE_NOTE_MAX)
    }
    if (!draft.isNew) {
        Spacer(Modifier.height(12.dp))
        SoftButton(
            stringResource(R.string.editor_delete_task),
            onClick = viewModel::deleteTask,
            icon = Icons.Outlined.Delete,
            contentColor = colors.error,
        )
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    EditorRow(
        icon = icon,
        label = label,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        trailing()
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

/** Label with date and time chips; with large text the chips move below the label instead of squeezing it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateTimeRow(
    label: String,
    date: LocalDate,
    time: LocalTime?,
    today: LocalDate,
    formats: Formats,
    picker: PickerTarget?,
    dateTarget: PickerTarget,
    timeTarget: PickerTarget,
    onToggle: (PickerTarget) -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(44.dp))
        FlowRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.CenterVertically).padding(end = 8.dp),
            )
            Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
                val dateText = formats.capitalize(date.format(if (date.year == today.year) formats.dayMonthShort else formats.dateMedium))
                ValueChip(dateText, selected = picker == dateTarget, onClick = { onToggle(dateTarget) }, label = label)
                if (time != null) {
                    Spacer(Modifier.size(8.dp))
                    ValueChip(time.format(formats.time), selected = picker == timeTarget, onClick = { onToggle(timeTarget) }, label = label)
                }
            }
        }
    }
}

@Composable
private fun PickerPanel(visible: Boolean, content: @Composable () -> Unit) {
    val motion = LocalMotion.current
    AnimatedVisibility(
        visible,
        enter = motion.expandIn(),
        exit = motion.shrinkOut(),
    ) {
        Box(Modifier.padding(bottom = 8.dp)) { content() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorChooser(colorKey: String?, calendarColorKey: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotion.current
    var open by rememberSaveable { mutableStateOf(false) }
    val chosen = colorKey?.let(CalendarColor::fromKey)
    val shown = chosen ?: CalendarColor.fromKey(calendarColorKey)
    EditorSection(modifier) {
        EditorRow(
            icon = Icons.Outlined.Palette,
            label = stringResource(R.string.editor_color),
            modifier = Modifier.toggleable(value = open, role = Role.Button, onValueChange = { open = it }),
        ) {
            Box(Modifier.size(14.dp).background(shown.color(), CircleShape))
            Spacer(Modifier.size(8.dp))
            Text(
                if (chosen == null) stringResource(R.string.editor_color_calendar) else stringResource(chosen.label),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            open,
            enter = motion.expandIn(),
            exit = motion.shrinkOut(),
        ) {
            FlowRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.Center) {
                SelectableChip(
                    text = stringResource(R.string.editor_color_calendar),
                    selected = chosen == null,
                    onClick = { onSelect(null) },
                    leading = { Box(Modifier.size(10.dp).background(CalendarColor.fromKey(calendarColorKey).color(), CircleShape)) },
                    modifier = Modifier.padding(end = 4.dp).align(Alignment.CenterVertically),
                )
                CalendarColor.entries.forEach { option ->
                    ColorSwatch(
                        color = option.color(),
                        selected = chosen == option,
                        label = stringResource(option.label),
                        onClick = { onSelect(option.key) },
                    )
                }
            }
        }
    }
}
