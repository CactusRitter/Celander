package com.celander.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celander.app.AppLog
import com.celander.app.BuildConfig
import com.celander.app.CrashLog
import com.celander.app.CelanderApp
import com.celander.app.R
import com.celander.app.core.model.CalendarSource
import com.celander.app.core.model.CalendarView
import com.celander.app.core.model.EntryKind
import com.celander.app.core.ui.Formats
import com.celander.app.core.ui.rememberFormats
import com.celander.app.event.ReminderText
import com.celander.app.notifications.rememberNotificationAccess
import com.celander.app.ui.BigRow
import com.celander.app.ui.ChoiceChip
import com.celander.app.ui.GlassDialog
import com.celander.app.ui.IconBubble
import com.celander.app.ui.LocalMotion
import com.celander.app.ui.SettingsCard
import com.celander.app.ui.Sizes
import com.celander.app.ui.SwitchRow
import com.celander.app.ui.TextAction
import com.celander.app.ui.UpdateBanner
import com.celander.app.ui.WhatsNew
import com.celander.app.ui.WheelPicker
import com.ce.kit.ui.glass.GlassKind
import com.ce.kit.ui.glass.GlassSlider
import com.ce.kit.ui.glass.bounceSpring
import com.ce.kit.ui.glass.glass
import com.ce.kit.ui.glass.glassSpring
import com.ce.kit.ui.glass.pressScale
import com.ce.kit.ui.glass.softBounceSpring
import com.ce.kit.ui.theme.Accent
import com.celander.app.ui.theme.BackupIcon
import com.celander.app.ui.theme.DownloadIcon
import com.ce.kit.ui.theme.LocalGlass
import com.celander.app.ui.theme.PaletteIcon
import com.ce.kit.ui.theme.Skin
import com.ce.kit.ui.theme.ThemeMode
import com.ce.kit.ui.theme.ThemeStyle
import com.ce.kit.ui.theme.customPalette
import java.text.NumberFormat
import java.time.DayOfWeek
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The screens of Settings: a short tree (main → section) walked with the Back button, like Cemail and Cenotes. A route is a
 * plain string so it survives a rotation. Calendars, Google accounts and Licenses are pages of their own (navigation).
 */
private object SettingsRoute {
    const val MAIN = "main"
    const val APPEARANCE = "appearance"
    const val CALENDAR = "calendar"
    const val REMINDERS = "reminders"
    const val DATA = "data"
    const val BACKUP = "backup"
    const val UPDATES = "updates"
    const val DIAGNOSTICS = "diagnostics"

    fun parent(route: String): String? = if (route == MAIN) null else MAIN
    fun depth(route: String): Int = if (route == MAIN) 0 else 1
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenCalendars: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenAccounts: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val calendars by viewModel.calendarRows.collectAsStateWithLifecycle()
    val hasSampleData by viewModel.hasSampleData.collectAsStateWithLifecycle()
    val snackbar by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val current = settings ?: return
    val formats = rememberFormats(current.timeFormat)
    val resources = LocalResources.current
    val notifications = rememberNotificationAccess()
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    val defaultCalendarName = stringResource(R.string.default_calendar_name)

    var route by rememberSaveable { mutableStateOf(SettingsRoute.MAIN) }
    val goBack = { SettingsRoute.parent(route)?.let { route = it } ?: onBack() }
    BackHandler(enabled = route != SettingsRoute.MAIN, onBack = goBack)

    val status = notifications.status
    val blocked = status.blocks(task = false) || status.blocks(task = true)
    val title = when (route) {
        SettingsRoute.APPEARANCE -> stringResource(R.string.settings_section_appearance)
        SettingsRoute.CALENDAR -> stringResource(R.string.settings_section_calendar)
        SettingsRoute.REMINDERS -> stringResource(R.string.settings_section_reminders)
        SettingsRoute.DATA -> stringResource(R.string.settings_section_data)
        SettingsRoute.BACKUP -> stringResource(R.string.backup)
        SettingsRoute.UPDATES -> stringResource(R.string.settings_section_updates)
        SettingsRoute.DIAGNOSTICS -> stringResource(R.string.settings_section_diagnostics)
        else -> stringResource(R.string.settings_title)
    }

    SettingsScaffold(
        title = title,
        onBack = goBack,
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
        item(key = "page") {
            // Going deeper grows the new screen in from a little smaller; going back does the opposite.
            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    if (SettingsRoute.depth(targetState) >= SettingsRoute.depth(initialState)) {
                        (fadeIn(glassSpring()) + scaleIn(softBounceSpring(), initialScale = 0.94f)) togetherWith
                            (fadeOut(tween(120)) + scaleOut(glassSpring(), targetScale = 1.03f))
                    } else {
                        (fadeIn(glassSpring()) + scaleIn(softBounceSpring(), initialScale = 1.04f)) togetherWith
                            (fadeOut(tween(120)) + scaleOut(glassSpring(), targetScale = 0.94f))
                    }.using(SizeTransform(clip = false))
                },
                label = "settingsPage",
            ) { target ->
                Column(
                    Modifier.widthIn(max = Sizes.maxFormWidth).fillMaxWidth().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (target) {
                        SettingsRoute.MAIN -> MainPage(
                            settings = current,
                            formats = formats,
                            calendarCount = calendars?.size,
                            remindersSummary = when {
                                blocked -> stringResource(R.string.settings_notifications_off)
                                status.mayBeLate -> stringResource(if (!status.exactAlarms) R.string.settings_timing_inexact else R.string.settings_timing_restricted)
                                else -> current.eventReminders.joinToString(", ") { ReminderText.label(resources, it, false, false, formats) }
                                    .ifEmpty { stringResource(R.string.reminder_none) }
                            },
                            onRoute = { route = it },
                            onOpenAccounts = onOpenAccounts,
                            onOpenLicenses = onOpenLicenses,
                        )
                        SettingsRoute.APPEARANCE -> AppearancePage(current, viewModel)
                        SettingsRoute.CALENDAR -> CalendarPage(current, viewModel, formats, calendars, onOpenCalendars)
                        SettingsRoute.REMINDERS -> RemindersPage(current, viewModel, formats, notifications, blocked)
                        SettingsRoute.DATA -> DataPage(hasSampleData, viewModel) { confirmDeleteAll = true }
                        SettingsRoute.BACKUP -> BackupPage(viewModel.backup)
                        SettingsRoute.UPDATES -> UpdatesPage(current, viewModel)
                        SettingsRoute.DIAGNOSTICS -> DiagnosticsPage()
                    }
                }
            }
        }
    }
}

// --- The main screen: one big row per section ---

@Composable
private fun MainPage(
    settings: AppSettings,
    formats: Formats,
    calendarCount: Int?,
    remindersSummary: String,
    onRoute: (String) -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val colorName = settings.customHue?.let { stringResource(R.string.design_custom) } ?: stringResource(settings.accent.label)
    val weekStartName = when (settings.weekStart) {
        WeekStart.SYSTEM -> formats.capitalize(formats.weekdayLong(settings.firstDayOfWeek(formats.locale)))
        WeekStart.MONDAY -> formats.capitalize(formats.weekdayLong(DayOfWeek.MONDAY))
        WeekStart.SUNDAY -> formats.capitalize(formats.weekdayLong(DayOfWeek.SUNDAY))
    }
    val calendarSummary = listOfNotNull(
        weekStartName,
        durationLabel(settings.defaultDurationMinutes),
        calendarCount?.let { pluralStringResource(R.plurals.settings_calendar_count, it, it) },
    ).joinToString(" · ")
    var index = 0
    @Composable
    fun Section(title: String, body: String?, icon: ImageVector, attention: Boolean = false, onClick: () -> Unit) {
        BigRow(title, body, index++, onClick = onClick) {
            IconBubble { Icon(icon, null, tint = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer) }
        }
    }
    Section(stringResource(R.string.settings_section_accounts), com.celander.app.account.ui.accountsSummary(), Icons.Outlined.Sync, onClick = onOpenAccounts)
    Section(
        stringResource(R.string.settings_section_appearance),
        stringResource(R.string.settings_design_summary, colorName, NumberFormat.getPercentInstance().format(settings.transparency / 100.0)),
        PaletteIcon,
    ) { onRoute(SettingsRoute.APPEARANCE) }
    Section(stringResource(R.string.settings_section_calendar), calendarSummary, Icons.Outlined.CalendarMonth) { onRoute(SettingsRoute.CALENDAR) }
    Section(stringResource(R.string.settings_section_reminders), remindersSummary, Icons.Outlined.Notifications) { onRoute(SettingsRoute.REMINDERS) }
    Section(stringResource(R.string.settings_section_data), stringResource(R.string.settings_data_summary), Icons.Outlined.Storage) { onRoute(SettingsRoute.DATA) }
    Section(stringResource(R.string.backup), stringResource(R.string.backup_summary), BackupIcon) { onRoute(SettingsRoute.BACKUP) }
    Section(stringResource(R.string.settings_section_updates), stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), DownloadIcon) { onRoute(SettingsRoute.UPDATES) }
    Section(stringResource(R.string.settings_section_diagnostics), stringResource(R.string.settings_row_diagnostics_body), Icons.Outlined.BugReport) { onRoute(SettingsRoute.DIAGNOSTICS) }
    Section(stringResource(R.string.settings_licenses), null, Icons.Outlined.Info, onClick = onOpenLicenses)
    // About as a quiet footer: logo, name and version.
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Image(painterResource(R.drawable.logo_mark), null, modifier = Modifier.size(36.dp))
        Text(
            stringResource(R.string.app_name) + " · " + stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            color = com.ce.kit.ui.glass.onBackdrop(MaterialTheme.colorScheme.onSurfaceVariant),
            textAlign = TextAlign.Center,
        )
    }
}

// --- Appearance ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearancePage(settings: AppSettings, viewModel: SettingsViewModel) {
    val glassLook = settings.skin == Skin.GLASS
    val dark = LocalGlass.current.dark
    val formats = rememberFormats(settings.timeFormat)
    SettingsCard {
        Text(stringResource(R.string.skin_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.skin_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Skin.entries.forEach { skin ->
                ChoiceChip(stringResource(skin.label), settings.skin == skin) { viewModel.update { it.copy(skin = skin) } }
            }
        }
        if (settings.skin.forcedDark != null) {
            // Only one mode exists for this design: no light/dark choice to offer.
            Text(stringResource(R.string.skin_always_dark), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.theme_mode_title), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ThemeMode.SYSTEM to R.string.theme_system,
                    ThemeMode.LIGHT to R.string.theme_light,
                    ThemeMode.DARK to R.string.theme_dark,
                ).forEach { (mode, label) ->
                    ChoiceChip(stringResource(label), settings.themeMode == mode) { viewModel.update { it.copy(themeMode = mode) } }
                }
            }
        }
    }
    if (glassLook) SwitchRow(R.string.depth_title, R.string.depth_body, settings.depth3d) { value -> viewModel.update { it.copy(depth3d = value) } }
    SettingsCard {
        if (glassLook) {
            Text(stringResource(R.string.style_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.style_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeStyle.entries.forEach { style ->
                    ChoiceChip(stringResource(style.label), settings.themeStyle == style) { viewModel.update { it.copy(themeStyle = style) } }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        Text(stringResource(R.string.accent_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.accent_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Accent.entries.forEach { accent ->
                AccentDot(if (dark) accent.dark else accent.light, stringResource(accent.label), selected = settings.customHue == null && settings.accent == accent) {
                    viewModel.update { it.copy(accent = accent, customHue = null) }
                }
            }
        }
        Text(stringResource(R.string.accent_custom), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleSmall)
        HueBar(settings.customHue) { hue -> viewModel.updateLive { it.copy(customHue = hue) } }
    }
    if (glassLook) SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_transparency_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(
                NumberFormat.getPercentInstance().format(settings.transparency / 100.0),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(stringResource(R.string.settings_transparency_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GlassSlider(
            value = settings.transparency.toFloat(),
            onValueChange = { value -> viewModel.updateLive { it.copy(transparency = value.roundToInt()) } },
            valueRange = 0f..100f,
        )
        // How the glass looks right now: coloured bars behind a floating pill.
        Box(
            Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.background)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) // tinted like the app's background
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val c = MaterialTheme.colorScheme
                listOf(
                    c.primary to 0.5f, c.error to 0.8f,
                    (if (dark) Accent.TEAL.dark else Accent.TEAL.light) to 0.35f,
                    (if (dark) Accent.AMBER.dark else Accent.AMBER.light) to 0.65f,
                ).forEach { (color, part) ->
                    Box(Modifier.fillMaxWidth(part).height(14.dp).clip(CircleShape).background(color.copy(alpha = 0.8f)))
                }
            }
            Text(
                stringResource(R.string.preview),
                Modifier.align(Alignment.Center).glass(CircleShape, GlassKind.Chrome, elevation = 6.dp).padding(horizontal = 22.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
    // The home screen widgets cannot blur the wallpaper, so their transparency is set apart from the app's.
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.design_widget_transparency), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(formats.percent(settings.widgetTransparency), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(stringResource(R.string.design_widget_transparency_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var dragging by remember { mutableStateOf<Float?>(null) }
        GlassSlider(
            value = dragging ?: settings.widgetTransparency,
            onValueChange = { value -> dragging = value },
            valueRange = 0f..1f,
            onValueChangeFinished = {
                dragging?.let { value -> viewModel.update { it.copy(widgetTransparency = value) } }
                dragging = null
            },
        )
    }
    SwitchRow(R.string.settings_reduce_motion, R.string.settings_reduce_motion_summary, settings.reduceMotion) { value ->
        viewModel.update { it.copy(reduceMotion = value) }
    }
}

/** One accent colour to pick: a round swatch, with a check and a ring when it is the one in use. */
@Composable
private fun AccentDot(color: Color, name: String, selected: Boolean, onClick: () -> Unit) {
    val ring by animateDpAsState(if (selected) 3.dp else 0.dp, bounceSpring(), label = "ring")
    Box(
        Modifier
            .pressScale(0.9f)
            .size(44.dp)
            .then(if (ring > 0.dp) Modifier.border(ring, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), CircleShape) else Modifier)
            .padding(ring + 2.dp)
            .clip(CircleShape)
            .background(color)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, name, Modifier.size(20.dp), tint = Color.White)
    }
}

/**
 * Any colour: a rainbow bar to tap or drag along. The knob shows the chosen hue; without one (an accent of the list is in
 * use) there is no knob.
 */
@Composable
private fun HueBar(hue: Float?, onHue: (Float) -> Unit) {
    // The same colours the custom accent will have, so the bar shows exactly what each point gives.
    val stops = remember { (0..24).map { customPalette((it * 15f).coerceAtMost(359.9f)).light } }
    GlassSlider(
        value = hue,
        onValueChange = onHue,
        valueRange = 0f..359.9f,
        track = Brush.horizontalGradient(stops),
        knobColor = hue?.let { customPalette(it).light } ?: Color.Transparent,
    )
}

// --- Calendar ---

@Composable
private fun CalendarPage(
    current: AppSettings,
    viewModel: SettingsViewModel,
    formats: Formats,
    calendars: List<CalendarRow>?,
    onOpenCalendars: () -> Unit,
) {
    SettingsGroupCard {
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
        SettingsDivider()
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
}

// --- Reminders ---

@Composable
private fun RemindersPage(
    current: AppSettings,
    viewModel: SettingsViewModel,
    formats: Formats,
    notifications: com.celander.app.notifications.NotificationAccess,
    blocked: Boolean,
) {
    val status = notifications.status
    SettingsGroupCard {
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
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.settings_notifications),
            summary = stringResource(if (blocked) R.string.settings_notifications_off else R.string.settings_notifications_on),
            summaryColor = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
            summaryColor = if (status.mayBeLate) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = if (status.mayBeLate) ({ notifications.openTimingSettings() }) else null,
        )
    }
}

// --- Data ---

@Composable
private fun DataPage(hasSampleData: Boolean?, viewModel: SettingsViewModel, onDeleteAll: () -> Unit) {
    SettingsGroupCard {
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
            summaryColor = MaterialTheme.colorScheme.error,
            onClick = onDeleteAll,
        )
    }
}

// --- Updates ---

/** Settings → Updates: this version, looking on GitHub for a newer one (by itself, or now), and what each version brought. */
@Composable
private fun UpdatesPage(settings: AppSettings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val updates = (context.applicationContext as CelanderApp).updates
    val scope = rememberCoroutineScope()
    // What "Look now" found: null = not asked yet; then a version, "up to date" or "could not check".
    var result by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val upToDate = stringResource(R.string.update_none, BuildConfig.VERSION_NAME)
    val failed = stringResource(R.string.update_check_failed)
    val foundText = stringResource(R.string.update_found)
    SwitchRow(R.string.settings_updates_title, R.string.settings_updates_body, settings.checkUpdates) { value ->
        viewModel.update { it.copy(checkUpdates = value) }
    }
    UpdateBanner(allowLater = false)
    SettingsCard {
        Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.titleSmall)
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        ChoiceChip(stringResource(if (checking) R.string.update_checking else R.string.settings_updates_now), false, enabled = !checking) {
            if (checking) return@ChoiceChip
            checking = true
            scope.launch {
                result = try {
                    if (updates.check(force = true) != null) foundText else upToDate
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed
                }
                checking = false
            }
        }
    }
    WhatsNew()
}

/** A glass card for settings rows that have their own padding (the same card as a group of the sub-pages). */
@Composable
private fun SettingsGroupCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), GlassKind.Card).padding(vertical = 4.dp), content = content)
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
        val motion = LocalMotion.current
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            Text(stringResource(R.string.settings_duration_unit_hours), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text(stringResource(R.string.settings_duration_unit_minutes), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val MINUTE_STEP = 5
private val DURATION_PRESETS = listOf(15, 30, 45, 60, 90, 120)
private const val CUSTOM_DURATION = -1
private val HOURS = (0..23).toList()
private val MINUTES = (0 until 60 step MINUTE_STEP).toList()

/** The activity log can always be shared; the last crash shows only if there was one (as in Cemail). */
@Composable
private fun DiagnosticsPage() {
    val context = LocalContext.current
    var crash by remember { mutableStateOf(CrashLog.read(context)) }
    val copiedLabel = stringResource(R.string.copied)
    fun copy(text: String) {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(copiedLabel, text))
    }
    SettingsCard {
        Text(stringResource(R.string.settings_log_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.settings_log_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shareTitle = stringResource(R.string.settings_log_share_title)
            TextAction(stringResource(R.string.settings_log_share), onClick = { AppLog.share(context, shareTitle) })
            TextAction(stringResource(R.string.copy), onClick = { copy(AppLog.text(context)) })
            TextAction(stringResource(R.string.clear), onClick = {
                AppLog.clear(context)
                crash = null
            }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    crash?.let { text ->
        SettingsCard {
            Text(stringResource(R.string.settings_crash_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.settings_crash_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextAction(stringResource(R.string.copy), onClick = { copy(text) })
                TextAction(stringResource(R.string.clear), onClick = {
                    CrashLog.clear(context)
                    crash = null
                }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
