package com.celander.app.settings

import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celander.app.R
import com.celander.app.core.model.CalendarSource
import com.celander.app.core.model.EntryKind
import com.celander.app.core.model.ReminderDefaults
import com.celander.app.core.ui.Formats
import com.celander.app.core.ui.rememberFormats
import com.celander.app.designsystem.AccentButton
import com.celander.app.designsystem.CalendarColor
import com.celander.app.designsystem.CelanderTheme
import com.celander.app.designsystem.ColorSwatch
import com.celander.app.designsystem.GlassSheet
import com.celander.app.designsystem.PillShape
import com.celander.app.designsystem.Sizes
import com.celander.app.designsystem.SoftButton
import com.celander.app.designsystem.Spacing
import com.celander.app.designsystem.color
import com.celander.app.designsystem.glassTile
import com.celander.app.designsystem.rememberGlassSheetController
import com.celander.app.event.NewEntryReminders
import com.celander.app.event.reminderDefaults

private const val NEW_CALENDAR = -1L
private const val NONE = 0L

/** Settings › Manage calendars: visibility, name, color, default and deleting (with undo). */
@Composable
fun CalendarsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val rows by viewModel.calendarRows.collectAsStateWithLifecycle()
    val googleTasksEmail = viewModel.googleTasks.collectAsStateWithLifecycle().value.email
    val tasksTitle = googleTasksEmail?.let { stringResource(R.string.settings_calendars_google_tasks, it) } ?: stringResource(R.string.google_tasks_title)
    val snackbar by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableLongStateOf(NONE) }
    val list = rows ?: return
    val current = settings ?: return
    val formats = rememberFormats(current.timeFormat)
    val local = list.filter { it.calendar.source == CalendarSource.LOCAL }
    val deviceGroups = list.filter { it.calendar.source == CalendarSource.DEVICE }
        .groupBy { if (it.calendar.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL) null else it.calendar.account }
    val groupTitle = stringResource(R.string.settings_your_calendars)
    val onThisPhone = stringResource(R.string.settings_calendars_on_this_phone)
    val hint = stringResource(R.string.settings_hidden_hint)

    SettingsScaffold(
        title = stringResource(R.string.settings_manage_calendars),
        onBack = onBack,
        snackbar = snackbar,
        onSnackbarAction = viewModel::onSnackbarAction,
        onSnackbarTimeout = viewModel::onSnackbarTimeout,
        modalOpen = editing != NONE,
        overlay = {
            if (editing != NONE) {
                val row = list.firstOrNull { it.calendar.id == editing }
                CalendarEditSheet(
                    row = row,
                    canDelete = row?.calendar?.source == CalendarSource.LOCAL && local.size > 1,
                    globalReminders = current.reminderDefaults,
                    formats = formats,
                    onSave = { name, color, reminders -> viewModel.saveCalendar(row?.calendar?.id, name, color, reminders) },
                    onMakeDefault = { row?.let { viewModel.setDefault(it.calendar.id) } },
                    onDelete = { row?.let { viewModel.deleteCalendar(it.calendar.id) } },
                    onDismissed = { editing = NONE },
                )
            }
        },
    ) {
        settingsGroup(groupTitle) {
            local.forEachIndexed { index, row ->
                if (index > 0) SettingsDivider()
                CalendarListRow(row, onEdit = { editing = row.calendar.id }, onVisibleChange = { viewModel.setVisible(row.calendar.id, it) })
            }
        }
        item(key = "hint") {
            Text(
                hint,
                style = CelanderTheme.typography.bodySmall,
                color = CelanderTheme.colors.textSecondary,
                modifier = Modifier.widthIn(max = Sizes.maxFormWidth).fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.xs),
            )
        }
        item(key = "add") {
            SoftButton(
                stringResource(R.string.settings_new_calendar),
                onClick = { editing = NEW_CALENDAR },
                icon = Icons.Rounded.Add,
                modifier = Modifier.padding(top = Spacing.l),
            )
        }
        // The phone's calendars, one group per account; visibility and color apply only in Celander.
        deviceGroups.forEach { (account, accountRows) ->
            settingsGroup(account ?: onThisPhone) {
                accountRows.forEachIndexed { index, row ->
                    if (index > 0) SettingsDivider()
                    CalendarListRow(row, onEdit = { editing = row.calendar.id }, onVisibleChange = { viewModel.setVisible(row.calendar.id, it) })
                }
            }
        }
        if (deviceGroups.isNotEmpty()) {
            item(key = "device-hint") {
                Text(
                    stringResource(R.string.settings_calendars_device_hint),
                    style = CelanderTheme.typography.bodySmall,
                    color = CelanderTheme.colors.textSecondary,
                    modifier = Modifier.widthIn(max = Sizes.maxFormWidth).fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.xs),
                )
            }
        }
        val taskLists = list.filter { it.calendar.source == CalendarSource.GOOGLE_TASKS }
        if (taskLists.isNotEmpty()) {
            settingsGroup(tasksTitle) {
                taskLists.forEachIndexed { index, row ->
                    if (index > 0) SettingsDivider()
                    CalendarListRow(row, onEdit = { editing = row.calendar.id }, onVisibleChange = { viewModel.setVisible(row.calendar.id, it) })
                }
            }
        }
    }
}

@Composable
private fun CalendarListRow(row: CalendarRow, onEdit: () -> Unit, onVisibleChange: (Boolean) -> Unit) {
    val colors = CelanderTheme.colors
    val calendar = row.calendar
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onEdit).padding(start = Spacing.l, end = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).background(CalendarColor.fromKey(calendar.colorKey).color(), CircleShape))
        Column(Modifier.weight(1f).padding(horizontal = Spacing.m, vertical = Spacing.s)) {
            Text(calendar.name, style = CelanderTheme.typography.bodyLarge, color = colors.textPrimary, maxLines = 2)
            // The phone's calendars are not counted: their events stay in the calendar provider.
            val entries = if (calendar.source == CalendarSource.DEVICE) null else pluralStringResource(R.plurals.entries_count, row.entries, row.entries)
            val isDefault = if (calendar.source == CalendarSource.GOOGLE_TASKS) row.isTaskDefault else row.isDefault
            val summary = when {
                !calendar.writable -> stringResource(R.string.settings_calendar_read_only)
                isDefault && entries != null -> stringResource(R.string.settings_calendar_default_summary, entries)
                isDefault -> stringResource(R.string.settings_calendar_default)
                else -> entries
            }
            if (summary != null) Text(summary, style = CelanderTheme.typography.bodySmall, color = colors.textSecondary)
        }
        val visibleLabel = stringResource(R.string.settings_calendar_visible, calendar.name)
        Switch(
            checked = calendar.visible,
            onCheckedChange = onVisibleChange,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.accent),
            modifier = Modifier.semantics { contentDescription = visibleLabel },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarEditSheet(
    row: CalendarRow?,
    canDelete: Boolean,
    globalReminders: ReminderDefaults,
    formats: Formats,
    onSave: (String, String, ReminderDefaults?) -> Unit,
    onMakeDefault: () -> Unit,
    onDelete: () -> Unit,
    onDismissed: () -> Unit,
) {
    val colors = CelanderTheme.colors
    val controller = rememberGlassSheetController()
    var name by rememberSaveable { mutableStateOf(row?.calendar?.name.orEmpty()) }
    var colorKey by rememberSaveable { mutableStateOf(row?.calendar?.colorKey ?: CalendarColor.DEFAULT.key) }
    var nameError by rememberSaveable { mutableStateOf(false) }
    // Own reminders start from what the calendar uses now: its own, else the app-wide ones.
    var useDefaultReminders by rememberSaveable { mutableStateOf(row?.calendar?.reminderDefaults == null) }
    var ownReminders by rememberSaveable(stateSaver = ReminderDefaultsSaver) {
        mutableStateOf(NewEntryReminders.effective(row?.calendar, globalReminders))
    }
    // Calendars from an account keep their name; only the color and the default change in Celander.
    val fromAccount = row != null && row.calendar.source != CalendarSource.LOCAL
    val title = stringResource(if (row == null) R.string.settings_new_calendar else R.string.settings_edit_calendar)
    val nameHint = stringResource(R.string.settings_calendar_name)
    val nameRequired = stringResource(R.string.settings_calendar_name_required)

    GlassSheet(onDismissed = onDismissed, controller = controller, paneTitle = title) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(title, style = CelanderTheme.typography.titleMedium, color = colors.textPrimary, modifier = Modifier.semantics { heading() })
            if (fromAccount) {
                Text(name, style = CelanderTheme.typography.bodyLarge, color = colors.textPrimary)
                Text(stringResource(R.string.settings_calendar_name_from_account), style = CelanderTheme.typography.bodySmall, color = colors.textSecondary)
            } else BasicTextField(
                value = name,
                onValueChange = {
                    name = it
                    if (it.isNotBlank()) nameError = false
                },
                singleLine = true,
                textStyle = CelanderTheme.typography.bodyLarge.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .glassTile(PillShape)
                    .padding(horizontal = Spacing.l, vertical = Spacing.m)
                    .semantics {
                        contentDescription = nameHint
                        if (nameError) error(nameRequired)
                    },
                decorationBox = { inner ->
                    Box {
                        if (name.isEmpty()) Text(nameHint, style = CelanderTheme.typography.bodyLarge, color = colors.textTertiary)
                        inner()
                    }
                },
            )
            if (nameError) Text(nameRequired, style = CelanderTheme.typography.bodySmall, color = colors.danger)
            FlowRow(Modifier.fillMaxWidth()) {
                CalendarColor.entries.forEach { option ->
                    ColorSwatch(option.color(), selected = option.key == colorKey, label = stringResource(option.label), onClick = { colorKey = option.key })
                }
            }
            if (fromAccount) {
                Text(stringResource(R.string.settings_calendar_color_local), style = CelanderTheme.typography.bodySmall, color = colors.textSecondary)
            }
            CalendarReminderDefaults(
                calendar = row?.calendar,
                useDefaults = useDefaultReminders,
                own = ownReminders,
                formats = formats,
                onUseDefaultsChange = { on ->
                    if (!on) ownReminders = NewEntryReminders.effective(row?.calendar, globalReminders)
                    useDefaultReminders = on
                },
                onOwnChange = { ownReminders = it },
            )
            val canBeDefault = row != null && when (row.calendar.source) {
                CalendarSource.GOOGLE_TASKS -> !row.isTaskDefault && row.calendar.visible
                else -> !row.isDefault && row.calendar.offers(EntryKind.EVENT)
            }
            if (canBeDefault) {
                SoftButton(stringResource(R.string.settings_make_default), onClick = {
                    onMakeDefault()
                    controller.close()
                })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                AccentButton(stringResource(R.string.action_save), onClick = {
                    if (name.isBlank()) {
                        nameError = true
                    } else {
                        onSave(name, colorKey, ownReminders.takeUnless { useDefaultReminders })
                        controller.close()
                    }
                })
                Spacer(Modifier.weight(1f))
                if (row != null && canDelete) {
                    SoftButton(stringResource(R.string.action_delete), onClick = {
                        onDelete()
                        controller.close()
                    }, icon = Icons.Outlined.Delete, contentColor = colors.danger)
                }
            }
            if (row != null && !fromAccount) {
                Text(
                    when {
                        !canDelete -> stringResource(R.string.settings_last_calendar)
                        row.entries > 0 -> pluralStringResource(R.plurals.settings_delete_calendar_entries, row.entries, row.entries)
                        else -> stringResource(R.string.settings_delete_calendar_empty)
                    },
                    style = CelanderTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}
