package com.celander.app.calendar

import androidx.activity.compose.BackHandler
import com.ce.kit.ui.glass.LocalHazeState
import com.celander.app.ui.LocalWindowLayout
import com.ce.kit.ui.LocalMotion
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celander.app.R
import com.celander.app.calendar.agenda.AgendaView
import com.celander.app.calendar.components.LocalCompletingTasks
import com.celander.app.calendar.day.DayView
import com.celander.app.calendar.day.dayViewShowsWeek
import com.celander.app.calendar.month.MonthView
import com.celander.app.calendar.year.YearView
import com.celander.app.core.time.CalendarMath
import com.celander.app.core.ui.Formats
import com.celander.app.core.ui.rememberFormats
import com.ce.kit.ui.theme.CeTheme
import com.celander.app.ui.ErrorState
import com.ce.kit.ui.glass.GlassLayers
import com.celander.app.ui.GlassSnackbarHost
import com.ce.kit.ui.MotionConfig
import com.ce.kit.ui.MotionTokens
import com.celander.app.ui.Sizes
import com.ce.kit.ui.rememberHaptics
import com.celander.app.event.EditorSheet
import com.celander.app.event.EventDetailSheet
import com.celander.app.search.SearchOverlay
import com.celander.app.task.TaskListView
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenCalendars: () -> Unit,
    onOpenAccounts: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val now by viewModel.now.collectAsStateWithLifecycle()
    val completingTasks by viewModel.completingTasks.collectAsStateWithLifecycle()
    val hazeState = com.ce.kit.ui.glass.LocalHazeState.current ?: rememberHazeState()
    val activity = LocalActivity.current
    val drawer = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val drawerScope = androidx.compose.runtime.rememberCoroutineScope()
    val calendarList by viewModel.calendarList.collectAsStateWithLifecycle()

    CompositionLocalProvider(LocalHazeState provides hazeState, LocalCompletingTasks provides completingTasks) {
        BackHandler(enabled = drawer.isOpen) { drawerScope.launch { drawer.close() } }
        androidx.compose.material3.ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = state?.let { it.sheet == null && !it.searchOpen } ?: false,
            drawerContent = {
                com.ce.kit.ui.GlassDrawerSheet(onDismiss = { drawerScope.launch { drawer.close() } }) {
                    HomeDrawerContent(
                        current = state?.destination ?: HomeDestination.MONTH,
                        calendars = calendarList,
                        onSelectView = { viewModel.selectDestination(it); drawerScope.launch { drawer.close() } },
                        onToggleCalendar = { viewModel.setCalendarVisible(it.id, !it.visible) },
                        onManageCalendars = { drawerScope.launch { drawer.close() }; onOpenCalendars() },
                        onOpenAccounts = { drawerScope.launch { drawer.close() }; onOpenAccounts() },
                        onOpenSettings = { drawerScope.launch { drawer.close() }; onOpenSettings() },
                    )
                }
            },
            scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f),
        ) {
        Box(Modifier.fillMaxSize()) {
            val loadFailed by viewModel.loadFailed.collectAsStateWithLifecycle()
            if (loadFailed) {
                ErrorState(
                    message = stringResource(R.string.error_loading),
                    retryLabel = stringResource(R.string.action_retry),
                    onRetry = viewModel::retry,
                    modifier = Modifier.align(Alignment.Center),
                )
                return@Box
            }
            if (state == null) {
                return@Box
            }
            val zone = state.zone
            val formats = rememberFormats(state.settings.timeFormat)
            val firstDay = state.settings.firstDayOfWeek(formats.locale)
            val haptics = rememberHaptics()
            // The agenda opens at the selected day, and so does its heading, not where the agenda was left.
            var agendaFirstDate by rememberSaveable(state.destination == HomeDestination.AGENDA) { mutableLongStateOf(state.selectedDate.toEpochDay()) }
            var agendaTodayVisible by remember { mutableStateOf(true) }
            var jumpOpen by rememberSaveable { mutableStateOf(false) }

            val destinations = HomeDestination.entries
            val windowFor: @Composable (LocalDate, LocalDate) -> CalendarWindow? = { start, end ->
                val attempt by viewModel.attempt.collectAsStateWithLifecycle()
                // A new time zone moves every day's bounds: the windows are fetched anew in it.
                val flow = remember(start, end, attempt, zone) { viewModel.window(start, end, zone) }
                flow.collectAsStateWithLifecycle().value
            }

            // The welcome card sits above the pill; views keep their content clear of it.
            val showWelcome = !state.hasEntries && !(state.destination == HomeDestination.MONTH && state.monthPanelOpen)
            var welcomeHeight by remember { mutableIntStateOf(0) }
            val welcomeInset by animateDpAsState(
                if (showWelcome) with(LocalDensity.current) { welcomeHeight.toDp() } + 8.dp else 0.dp,
                LocalMotion.current.standard(),
                label = "welcomeInset",
            )
            val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val contentPadding = PaddingValues(
                top = statusTop + Sizes.topBar + 4.dp,
                bottom = navBottom + Sizes.pillHeight + 12.dp * 2 + welcomeInset,
            )
            val horizontalInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
            // Wide windows (tablets) get a page margin around the views and the top bar.
            val gutter = Modifier.padding(horizontal = LocalWindowLayout.current.extraGutter)
            // Where seven timeline columns fit side by side, the day view shows the whole week.
            val layoutDirection = LocalLayoutDirection.current
            val sideInsets = horizontalInsets.asPaddingValues()
            val dayShowsWeek = dayViewShowsWeek(
                LocalWindowLayout.current.width - sideInsets.calculateLeftPadding(layoutDirection) - sideInsets.calculateRightPadding(layoutDirection) -
                    LocalWindowLayout.current.extraGutter * 2,
            )

            BackHandler(enabled = state.destination == HomeDestination.MONTH && state.monthPanelOpen) {
                viewModel.closeMonthPanel()
            }
            KeyboardShortcutHandler { shortcut -> viewModel.onShortcut(shortcut, pickerOpen = jumpOpen, agendaFirst = LocalDate.ofEpochDay(agendaFirstDate), dayShowsWeek = dayShowsWeek) }

            Box(Modifier.fillMaxSize().hazeSource(hazeState, zIndex = GlassLayers.CONTENT)) {
                val motion = LocalMotion.current
                AnimatedContent(
                    targetState = state.destination,
                    transitionSpec = { destinationTransition(initialState, targetState, motion) },
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(horizontalInsets).then(gutter),
                    label = "destination",
                ) { destination ->
                    when (destination) {
                        HomeDestination.YEAR -> YearView(
                            selectedDate = state.selectedDate,
                            today = state.today,
                            firstDay = firstDay,
                            formats = formats,
                            contentPadding = contentPadding,
                            windowFor = windowFor,
                            onYearChange = { year -> viewModel.selectDate(CalendarMath.sameDayIn(YearMonth.of(year, state.selectedDate.monthValue), state.selectedDate.dayOfMonth)) },
                            onOpenMonth = { month -> viewModel.openMonth(CalendarMath.sameDayIn(month, state.selectedDate.dayOfMonth)) },
                            onOpenDay = viewModel::openDay,
                            onLongPressDay = viewModel::openDay,
                        )
                        HomeDestination.MONTH -> MonthView(
                            selectedDate = state.selectedDate,
                            today = state.today,
                            now = now,
                            zone = zone,
                            firstDay = firstDay,
                            showWeekNumbers = state.settings.showWeekNumbers,
                            panelOpen = state.monthPanelOpen,
                            todayPulse = state.jumpRequest,
                            formats = formats,
                            contentPadding = contentPadding,
                            windowFor = windowFor,
                            onMonthChange = { month -> viewModel.selectDate(CalendarMath.sameDayIn(month, state.selectedDate.dayOfMonth)) },
                            onDayTap = viewModel::onMonthDayTapped,
                            onDayLongPress = { date ->
                                haptics.longPress()
                                viewModel.newEvent(date)
                            },
                            onClosePanel = viewModel::closeMonthPanel,
                            onOpenPanel = viewModel::openMonthPanel,
                            onOpenOccurrence = viewModel::openOccurrence,
                            onOpenTask = viewModel::editTask,
                            onToggleTask = viewModel::setTaskDone,
                        )
                        HomeDestination.DAY -> DayView(
                            selectedDate = state.selectedDate,
                            today = state.today,
                            now = now,
                            zone = zone,
                            firstDay = firstDay,
                            showsWeek = dayShowsWeek,
                            todayPulse = state.jumpRequest,
                            formats = formats,
                            contentPadding = contentPadding,
                            windowFor = windowFor,
                            onSelectDate = viewModel::selectDate,
                            onCreateAt = { date, time, offset -> viewModel.newEvent(date, time.hour * 60 + time.minute, offset = offset) },
                            onOpenOccurrence = viewModel::openOccurrence,
                            onOpenTask = viewModel::editTask,
                            onToggleTask = viewModel::setTaskDone,
                        )
                        HomeDestination.AGENDA -> {
                            val taskItems by viewModel.taskItems.collectAsStateWithLifecycle()
                            val overdueTasks = remember(taskItems, state.today) {
                                taskItems.orEmpty().filter { !it.task.done && it.task.dueDate?.isBefore(state.today) == true }
                            }
                            AgendaView(
                                overdueTasks = overdueTasks,
                                anchorDate = state.selectedDate,
                                today = state.today,
                                now = now,
                                zone = zone,
                                formats = formats,
                                contentPadding = contentPadding,
                                jumpRequest = state.jumpRequest,
                                windowFor = windowFor,
                                onFirstVisibleDate = { agendaFirstDate = it.toEpochDay() },
                                onTodayVisible = { agendaTodayVisible = it },
                                onCreateOn = { date -> viewModel.newEvent(date) },
                                onOpenOccurrence = viewModel::openOccurrence,
                                onOpenTask = viewModel::editTask,
                                onToggleTask = viewModel::setTaskDone,
                            )
                        }
                        HomeDestination.TASKS -> {
                            val taskItems by viewModel.taskItems.collectAsStateWithLifecycle()
                            TaskListView(
                                items = taskItems,
                                today = state.today,
                                now = now,
                                zone = zone,
                                formats = formats,
                                contentPadding = contentPadding,
                                onQuickAdd = viewModel::quickAddTask,
                                onNewTask = viewModel::newTask,
                                onOpenTask = viewModel::editTask,
                                onToggleTask = viewModel::setTaskDone,
                            )
                        }
                    }
                }
            }

            val todayVisible = when (state.destination) {
                HomeDestination.YEAR -> state.selectedDate.year == state.today.year
                HomeDestination.MONTH -> YearMonth.from(state.selectedDate) == YearMonth.from(state.today)
                HomeDestination.DAY -> if (dayShowsWeek) CalendarMath.weekStart(state.selectedDate, firstDay) == CalendarMath.weekStart(state.today, firstDay) else state.selectedDate == state.today
                HomeDestination.AGENDA -> agendaTodayVisible
                HomeDestination.TASKS -> true
            }
            val agendaFirst = LocalDate.ofEpochDay(agendaFirstDate)
            val dayWeek = firstDay.takeIf { dayShowsWeek }

            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(horizontalInsets).then(gutter)) {
                HomeTopBar(
                    title = heading(state.destination, state.selectedDate, agendaFirst, dayWeek, formats),
                    today = state.today,
                    showToday = !todayVisible,
                    onToday = {
                        haptics.confirm()
                        viewModel.jumpTo(state.today)
                    },
                    onAdd = viewModel::newEntry,
                    onSearch = { viewModel.setSearchOpen(true) },
                    onMenu = { drawerScope.launch { drawer.open() } },
                )
            }

            NavigationPill(
                current = state.destination,
                destinations = destinations,
                onSelect = viewModel::selectDestination,
                onContextClick = if (state.destination == HomeDestination.TASKS) null else ({ jumpOpen = true }),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .windowInsetsPadding(horizontalInsets)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            )

            AnimatedVisibility(
                visible = state.searchOpen,
                enter = LocalMotion.current.riseIn(-16),
                exit = LocalMotion.current.sinkOut(-16),
            ) {
                SearchOverlay(
                    today = state.today,
                    now = now,
                    zone = zone,
                    formats = formats,
                    onClose = { viewModel.setSearchOpen(false) },
                    onOpenOccurrence = viewModel::openOccurrence,
                    onOpenTask = viewModel::editTask,
                    onToggleTask = viewModel::setTaskDone,
                    onCreateEvent = { title -> viewModel.newEventTitled(title) },
                )
            }

            val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .windowInsetsPadding(horizontalInsets)
                    .padding(start = 12.dp, end = 12.dp, bottom = Sizes.pillHeight + 12.dp * 2),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlassSnackbarHost(
                    message = snackbarMessage,
                    onAction = viewModel::onSnackbarAction,
                    onTimeout = viewModel::onSnackbarTimeout,
                )
                AnimatedVisibility(
                    visible = showWelcome,
                    enter = LocalMotion.current.riseIn(3),
                    exit = LocalMotion.current.sinkOut(3),
                ) {
                    WelcomeCard(
                        onCreate = viewModel::newEntry,
                        onLoadSamples = viewModel::loadSampleData,
                        modifier = Modifier.onSizeChanged { welcomeHeight = it.height },
                    )
                }
            }

            if (jumpOpen) {
                JumpDateSheet(
                    destination = state.destination,
                    selectedDate = state.selectedDate,
                    today = state.today,
                    firstDay = firstDay,
                    formats = formats,
                    onPick = viewModel::jumpTo,
                    onDismissed = { jumpOpen = false },
                )
            }

            when (val sheet = state.sheet) {
                is HomeSheet.EventDetail -> key(sheet) {
                    EventDetailSheet(
                        eventId = sheet.eventId,
                        occurrenceStart = sheet.occurrenceStart,
                        formats = formats,
                        zone = zone,
                        onOpenEditor = { request -> viewModel.openSheet(HomeSheet.Editor(request)) },
                        onDismissed = viewModel::closeSheet,
                    )
                }
                is HomeSheet.Editor -> key(sheet) {
                    EditorSheet(
                        request = sheet.request,
                        formats = formats,
                        today = state.today,
                        firstDay = firstDay,
                        zone = zone,
                        onSaved = viewModel::onEntrySaved,
                        onDismissed = {
                            viewModel.closeSheet()
                            // Opened on top of another app (a date in an e-mail): back to it, as calendar apps do.
                            if (sheet.request is EditorRequest.InsertEvent && activity?.isTaskRoot == false) activity.finish()
                        },
                    )
                }
                null -> Unit
            }
        }
        }
    }
}

/** The heading above every view: the period it shows, with the year. */
@Composable
private fun heading(destination: HomeDestination, selected: LocalDate, agendaFirst: LocalDate, dayWeek: DayOfWeek?, formats: Formats): String = when (destination) {
    HomeDestination.YEAR -> selected.year.toString()
    HomeDestination.MONTH -> formats.capitalize(selected.format(formats.monthYear))
    HomeDestination.DAY -> if (dayWeek != null) {
        val start = CalendarMath.weekStart(selected, dayWeek)
        formats.capitalize(formats.dayRange(start, start.plusDays(6)))
    } else {
        formats.capitalize(selected.format(formats.dayMonthYear))
    }
    HomeDestination.AGENDA -> formats.capitalize(agendaFirst.format(formats.monthYear))
    HomeDestination.TASKS -> stringResource(R.string.view_tasks)
}

/** Year → month → day are levels of zoom; agenda and tasks sit beside month. */
private val HomeDestination.depth: Int
    get() = when (this) {
        HomeDestination.YEAR -> 0
        HomeDestination.DAY -> 2
        else -> 1
    }

/**
 * Going deeper zooms in (the new view grows from slightly smaller, the old one grows past the
 * screen); going up zooms out. Lateral switches cross-fade with a small shift.
 */
private fun destinationTransition(from: HomeDestination, to: HomeDestination, motion: MotionConfig): ContentTransform {
    if (motion.reduced) return fadeIn(motion.fade()) togetherWith fadeOut(motion.fade())
    val enter = motion.enter<Float>()
    val exit = motion.exit<Float>()
    return when {
        to.depth > from.depth ->
            (fadeIn(enter) + scaleIn(enter, initialScale = MotionTokens.ZOOM_IN_SCALE)) togetherWith
                (fadeOut(exit) + scaleOut(exit, targetScale = MotionTokens.ZOOM_OUT_SCALE))
        to.depth < from.depth ->
            (fadeIn(enter) + scaleIn(enter, initialScale = MotionTokens.ZOOM_OUT_SCALE)) togetherWith
                (fadeOut(exit) + scaleOut(exit, targetScale = MotionTokens.ZOOM_IN_SCALE))
        else -> {
            val forward = to.ordinal > from.ordinal
            (fadeIn(enter) + slideInHorizontally(motion.enter()) { width -> if (forward) width / 12 else -width / 12 }) togetherWith
                fadeOut(exit)
        }
    }
}
