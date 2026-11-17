package com.celander.app.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celander.app.BuildConfig
import com.celander.app.R
import com.celander.app.calendar.device.CalendarApps
import com.celander.app.core.model.CalendarSource
import com.celander.app.core.model.CalendarView
import com.celander.app.core.model.EntryKind
import com.celander.app.core.ui.rememberFormats
import com.celander.app.designsystem.AccentPalette
import com.celander.app.designsystem.CelanderTheme
import com.celander.app.designsystem.GlassDialog
import com.celander.app.designsystem.Spacing
import com.celander.app.designsystem.TextAction
import com.celander.app.designsystem.WheelPicker
import com.celander.app.event.ReminderText
import com.celander.app.notifications.rememberNotificationAccess
import java.time.DayOfWeek

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDesign: () -> Unit,
    onOpenCalendars: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenGoogleAccounts: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val calendars by viewModel.calendarRows.collectAsStateWithLifecycle()
    val google by viewModel.googleCalendar.collectAsStateWithLifecycle()
    val googleOverview by viewModel.googleOverview.collectAsStateWithLifecycle()
    val hasSampleData by viewModel.hasSampleData.collectAsStateWithLifecycle()
    val snackbar by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val current = settings ?: return
    val formats = rememberFormats(current.timeFormat)
    val resources = LocalResources.current
    val notifications = rememberNotificationAccess()
    val context = LocalContext.current
    val calendarApp = remember(context) { CalendarApps.otherAppLabel(context) }
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }

    val appearanceTitle = stringResource(R.string.settings_section_appearance)
    val calendarTitle = stringResource(R.string.settings_section_calendar)
    val googleTitle = stringResource(R.string.settings_section_google)
    val googleOff = stringResource(R.string.settings_google_summary_off)
    val remindersTitle = stringResource(R.string.settings_section_reminders)
    val dataTitle = stringResource(R.string.settings_section_data)
    val defaultCalendarName = stringResource(R.string.default_calendar_name)

    // Only one section is open at a time; closed sections show a summary of their values.
    var openSection by rememberSaveable { mutableStateOf<String?>(null) }
    val toggle = { key: String -> openSection = if (openSection == key) null else key }
    val status = notifications.status
    val blocked = status.blocks(task = false) || status.blocks(task = true)
    val colorName = stringResource(AccentPalette.presetFor(current.accentHue)?.name ?: R.string.design_custom)
    val startViewName = stringResource(
        when (current.startView) {
            CalendarView.YEAR -> R.string.view_year
            CalendarView.MONTH -> R.string.view_month
            CalendarView.DAY -> R.string.view_day
            CalendarView.AGENDA -> R.string.view_agenda
        },
    )
    val appearanceSummary = stringResource(R.string.settings_appearance_summary, colorName, formats.percent(current.glassTransparency), startViewName)
    val weekStartName = when (current.weekStart) {
        WeekStart.SYSTEM -> formats.capitalize(formats.weekdayLong(current.firstDayOfWeek(formats.locale)))
        WeekStart.MONDAY -> formats.capitalize(formats.weekdayLong(DayOfWeek.MONDAY))
        WeekStart.SUNDAY -> formats.capitalize(formats.weekdayLong(DayOfWeek.SUNDAY))
    }
    val calendarSummary = listOfNotNull(
        weekStartName,
        durationLabel(current.defaultDurationMinutes),
        calendars?.let { pluralStringResource(R.plurals.settings_calendar_count, it.size, it.size) },
    ).joinToString(" · ")
    val dataSummary = stringResource(R.string.settings_data_summary)
    val remindersSummary = when {
        blocked -> stringResource(R.string.settings_notifications_off)
        status.mayBeLate -> stringResource(if (!status.exactAlarms) R.string.settings_timing_inexact else R.string.settings_timing_restricted)
        else -> current.eventReminders.joinToString(", ") { ReminderText.label(resources, it, false, false, formats) }.ifEmpty { stringResource(R.string.reminder_none) }
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        snackbar = snackbar,
        onSnackbarAction = viewModel::onSnackbarAction,
        onSnackbarTimeout = viewModel::onSnackbarTimeout,
        modalOpen = confirmDeleteAll,
        overlay = {
            // The only confirmation dialog in the app: this cannot be undone.
            GlassDialog(
                visible = confirmDeleteAll,
                title = stringResource(R.string.delete_all_title),
                message = stringResource(R.string.delete_all_message),
                confirmLabel = stringResource(R.string.delete_all_confirm),
                onConfirm = {
                    confirmDeleteAll = false
                    viewModel.deleteAllData(defaultCalendarName)
                },
                onDismiss = { confirmDeleteAll = false },
                destructive = true,
            )
        },
    ) {
        settingsSection("appearance", Icons.Outlined.Palette, appearanceTitle, appearanceSummary, openSection == "appearance", { toggle("appearance") }) {
            SettingsRow(
                title = stringResource(R.string.settings_design),
                summary = stringResource(R.string.settings_design_summary, colorName, formats.percent(current.glassTransparency)),
                onClick = onOpenDesign,
            )
            SettingsDivider()
            SettingsChoiceRow(
                title = stringResource(R.string.settings_start_view),
                options = listOf(
                    CalendarView.YEAR to stringResource(R.string.view_year),
                    CalendarView.MONTH to stringResource(R.string.view_month),
                    CalendarView.DAY to stringResource(R.string.view_day),
                    CalendarView.AGENDA to stringResource(R.string.view_agenda),
                ),
                selected = current.startView,
                onSelect = { value -> viewModel.update { it.copy(startView = value) } },
            )
        }

        settingsSection("calendar", Icons.Outlined.CalendarMonth, calendarTitle, calendarSummary, openSection == "calendar", { toggle("calendar") }) {
            SettingsChoiceRow(
                title = stringResource(R.string.settings_week_start),
                options = listOf(
                    WeekStart.SYSTEM to stringResource(R.string.settings_system),
                    WeekStart.MONDAY to formats.capitalize(formats.weekdayLong(DayOfWeek.MONDAY)),
                    WeekStart.SUNDAY to formats.capitalize(formats.weekdayLong(DayOfWeek.SUNDAY)),
                ),
                selected = current.weekStart,
                onSelect = { value -> viewModel.update { it.copy(weekStart = value) } },
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.settings_week_numbers),
                checked = current.showWeekNumbers,
                onCheckedChange = { value -> viewModel.update { it.copy(showWeekNumbers = value) } },
            )
            SettingsDivider()
            SettingsChoiceRow(
                title = stringResource(R.string.settings_time_format),
                options = listOf(
                    TimeFormat.SYSTEM to stringResource(R.string.settings_system),
                    TimeFormat.H24 to stringResource(R.string.settings_time_24),
                    TimeFormat.H12 to stringResource(R.string.settings_time_12),
                ),
                selected = current.timeFormat,
                onSelect = { value -> viewModel.update { it.copy(timeFormat = value) } },
            )
            calendars?.filter { it.calendar.offers(EntryKind.EVENT) }?.takeIf { it.size > 1 }?.let { rows ->
                SettingsDivider()
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_default_calendar),
                    options = rows.map { it.calendar.id to it.calendar.name },
                    selected = rows.firstOrNull { it.isDefault }?.calendar?.id,
                    onSelect = { id -> id?.let(viewModel::setDefault) },
                )
            }
            // Only with Google Tasks lists is there a choice that differs from the event default.
            calendars?.takeIf { rows -> rows.any { it.calendar.source == CalendarSource.GOOGLE_TASKS } }?.let { all ->
                val rows = all.filter { it.calendar.offers(EntryKind.TASK) }
                SettingsDivider()
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_default_task_list),
                    options = rows.map { it.calendar.id to it.calendar.name },
                    selected = rows.firstOrNull { it.isTaskDefault }?.calendar?.id,
                    onSelect = { id -> id?.let(viewModel::setDefaultTask) },
                )
            }
            SettingsDivider()
            DurationRow(
                minutes = current.defaultDurationMinutes,
                onChange = { value -> viewModel.update { it.copy(defaultDurationMinutes = value) } },
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.settings_manage_calendars),
                summary = calendars?.let { pluralStringResource(R.plurals.settings_calendar_count, it.size, it.size) },
                onClick = onOpenCalendars,
            )
        }

        googleOverview?.let { overview ->
            val accounts = overview.accounts
            val troubled = overview.troubled
            settingsSection(
                "google",
                Icons.Outlined.Sync,
                googleTitle,
                accounts.joinToString { it.email }.ifEmpty { googleOff },
                openSection == "google",
                { toggle("google") },
                attention = overview.attention,
            ) {
                // Everything else is on the Google accounts page; the overview only says what needs a look.
                SettingsRow(
                    title = stringResource(R.string.google_accounts_title),
                    summary = when {
                        overview.noCalendarAccess -> stringResource(R.string.settings_google_calendar_no_access)
                        troubled != null -> stringResource(R.string.google_accounts_attention, troubled.email)
                        accounts.isEmpty() -> stringResource(R.string.google_accounts_overview_empty)
                        else -> pluralStringResource(R.plurals.google_accounts_count, accounts.size, accounts.size)
                    },
                    summaryColor = if (overview.attention) CelanderTheme.colors.danger else CelanderTheme.colors.textSecondary,
                    onClick = onOpenGoogleAccounts,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        settingsSection(
            "reminders",
            Icons.Outlined.Notifications,
            remindersTitle,
            remindersSummary,
            openSection == "reminders",
            { toggle("reminders") },
            attention = blocked || status.mayBeLate,
        ) {
            // Up to four defaults per kind, from the suggestions or at any custom time.
            DefaultReminders(stringResource(R.string.settings_reminder_events), current.eventReminders, allDay = false, task = false, formats) { list ->
                viewModel.update { it.copy(eventReminders = list) }
            }
            SettingsDivider()
            DefaultReminders(stringResource(R.string.settings_reminder_all_day), current.allDayReminders, allDay = true, task = false, formats) { list ->
                viewModel.update { it.copy(allDayReminders = list) }
            }
            SettingsDivider()
            DefaultReminders(stringResource(R.string.settings_reminder_tasks), current.taskReminders, allDay = false, task = true, formats) { list ->
                viewModel.update { it.copy(taskReminders = list) }
            }
            if (google?.status?.readable == true) {
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_reminders_google),
                    summary = calendarApp?.let { stringResource(R.string.settings_reminders_google_by_app, it) } ?: stringResource(R.string.settings_reminders_google_no_app),
                    summaryColor = if (calendarApp == null) CelanderTheme.colors.danger else CelanderTheme.colors.textSecondary,
                )
            }
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.settings_notifications),
                summary = stringResource(if (blocked) R.string.settings_notifications_off else R.string.settings_notifications_on),
                summaryColor = if (blocked) CelanderTheme.colors.danger else CelanderTheme.colors.textSecondary,
                onClick = { if (blocked) notifications.fix(task = status.blocks(task = true) && !status.blocks(task = false)) else notifications.openNotificationSettings() },
            )
            if (!blocked) {
                // Sound, vibration and pop-up of each kind are set in the system settings of its channel.
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.channel_events),
                    summary = stringResource(R.string.settings_reminders_channel_hint),
                    onClick = { notifications.openChannelSettings(task = false) },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.channel_tasks),
                    summary = stringResource(R.string.settings_reminders_channel_hint),
                    onClick = { notifications.openChannelSettings(task = true) },
                )
            }
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.settings_timing),
                summary = stringResource(
                    when {
                        !status.exactAlarms -> R.string.settings_timing_inexact
                        status.backgroundRestricted -> R.string.settings_timing_restricted
                        else -> R.string.settings_timing_exact
                    },
                ),
                summaryColor = if (status.mayBeLate) CelanderTheme.colors.danger else CelanderTheme.colors.textSecondary,
                onClick = if (status.mayBeLate) ({ notifications.openTimingSettings() }) else null,
            )
        }

        settingsSection("data", Icons.Outlined.Storage, dataTitle, dataSummary, openSection == "data", { toggle("data") }) {
            when (hasSampleData) {
                true -> SettingsRow(
                    title = stringResource(R.string.settings_samples_clear),
                    summary = stringResource(R.string.settings_samples_clear_summary),
                    onClick = viewModel::clearSampleData,
                )
                false -> SettingsRow(
                    title = stringResource(R.string.welcome_samples),
                    summary = stringResource(R.string.settings_samples_load_summary),
                    onClick = viewModel::loadSampleData,
                )
                null -> Unit
            }
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.settings_delete_all),
                summary = stringResource(R.string.settings_delete_all_summary),
                summaryColor = CelanderTheme.colors.danger,
                onClick = { confirmDeleteAll = true },
            )
        }

        // About as a quiet footer: logo, name and version, licenses.
        item(key = "about") {
            Column(
                Modifier.fillMaxWidth().padding(top = Spacing.xxl, bottom = Spacing.l),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Image(painterResource(R.drawable.logo_mark), null, modifier = Modifier.size(LogoSize))
                Text(
                    stringResource(R.string.app_name) + " · " + stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = CelanderTheme.typography.bodySmall,
                    color = CelanderTheme.colors.textSecondary,
                )
                TextAction(stringResource(R.string.settings_licenses), onClick = onOpenLicenses)
            }
        }
    }
}

@Composable
private fun durationLabel(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return listOfNotNull(
        hours.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.settings_hours, it, it) },
        rest.takeIf { it > 0 || hours == 0 }?.let { pluralStringResource(R.plurals.settings_minutes, it, it) },
    ).joinToString(" ")
}

/**
 * Default duration as quick chips like the other calendar settings; "Custom" opens two scroll
 * wheels, hours and minutes in steps of five, never below five minutes.
 */
@Composable
private fun DurationRow(minutes: Int, onChange: (Int) -> Unit) {
    var custom by rememberSaveable { mutableStateOf(minutes !in DURATION_PRESETS) }
    val hoursUnit = stringResource(R.string.settings_duration_unit_hours)
    val minutesUnit = stringResource(R.string.settings_duration_unit_minutes)
    fun short(value: Int) = listOfNotNull(
        (value / 60).takeIf { it > 0 }?.let { "$it $hoursUnit" },
        (value % 60).takeIf { it > 0 }?.let { "$it $minutesUnit" },
    ).joinToString(" ")
    val options = DURATION_PRESETS.map { it to short(it) } + (CUSTOM_DURATION to stringResource(R.string.settings_duration_custom))
    Column(Modifier.fillMaxWidth()) {
        SettingsChoiceRow(
            title = stringResource(R.string.settings_default_duration),
            options = options,
            selected = if (custom) CUSTOM_DURATION else minutes,
            onSelect = { value ->
                custom = value == CUSTOM_DURATION
                if (!custom) onChange(value)
            },
            summary = durationLabel(minutes),
        )
        val motion = CelanderTheme.motion
        AnimatedVisibility(custom, enter = motion.expandIn(), exit = motion.shrinkOut()) {
            DurationWheels(minutes, onChange)
        }
    }
}

@Composable
private fun DurationWheels(minutes: Int, onChange: (Int) -> Unit) {
    val hours = minutes / 60
    val rest = minutes % 60 - minutes % 60 % MINUTE_STEP
    val next = stringResource(R.string.wheel_next)
    val previous = stringResource(R.string.wheel_previous)
    fun set(h: Int, m: Int) = onChange((h * 60 + m).coerceAtLeast(MINUTE_STEP))
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.s),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WheelPicker(
                values = HOURS,
                selected = hours,
                onSelect = { h -> set(h, rest) },
                label = { it.toString() },
                contentDescription = stringResource(R.string.settings_duration_hours),
                increaseLabel = next,
                decreaseLabel = previous,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.settings_duration_unit_hours), style = CelanderTheme.typography.titleMedium, color = CelanderTheme.colors.textSecondary)
            WheelPicker(
                values = MINUTES,
                selected = rest,
                onSelect = { m -> set(hours, m) },
                label = { it.toString().padStart(2, '0') },
                contentDescription = stringResource(R.string.settings_duration_minutes),
                increaseLabel = next,
                decreaseLabel = previous,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.settings_duration_unit_minutes), style = CelanderTheme.typography.titleMedium, color = CelanderTheme.colors.textSecondary)
        }
    }
}

private val LogoSize = 36.dp
private const val MINUTE_STEP = 5
private val DURATION_PRESETS = listOf(15, 30, 45, 60, 90, 120)
private const val CUSTOM_DURATION = -1
private val HOURS = (0..23).toList()
private val MINUTES = (0 until 60 step MINUTE_STEP).toList()
