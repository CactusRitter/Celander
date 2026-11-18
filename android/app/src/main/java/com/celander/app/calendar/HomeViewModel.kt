package com.celander.app.calendar

import com.celander.app.AppLog
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.celander.app.R
import com.celander.app.calendar.data.CalendarContentRepository
import com.celander.app.calendar.data.CalendarsRepository
import com.celander.app.calendar.data.SampleDataRepository
import com.celander.app.core.model.CalendarView
import com.celander.app.core.model.EntryKind
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.Task
import com.celander.app.core.model.TaskItem
import com.celander.app.core.time.CalendarMath
import com.celander.app.core.time.TimeSource
import com.celander.app.core.ui.SnackbarManager
import com.celander.app.core.ui.SnackbarMessage
import com.celander.app.core.ui.UiText
import com.celander.app.event.EditorOutcome
import com.celander.app.settings.AppSettings
import com.celander.app.settings.SettingsRepository
import com.celander.app.task.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class HomeDestination(val calendarView: CalendarView?) {
    YEAR(CalendarView.YEAR),
    MONTH(CalendarView.MONTH),
    DAY(CalendarView.DAY),
    AGENDA(CalendarView.AGENDA),
    TASKS(null),
    ;

    companion object {
        fun of(view: CalendarView): HomeDestination = entries.first { it.calendarView == view }
    }
}

data class HomeUiState(
    val destination: HomeDestination,
    val selectedDate: LocalDate,
    val today: LocalDate,
    /** The device's time zone, in which [today], the now line and the days of entries are read. */
    val zone: ZoneId,
    val monthPanelOpen: Boolean,
    val searchOpen: Boolean,
    val jumpRequest: Int,
    val sheet: HomeSheet?,
    val hasEntries: Boolean,
    val settings: AppSettings,
)

/**
 * Navigation state of the home screen. Destination, selected day, the month day panel, the search
 * overlay and the open sheet live in [SavedStateHandle] so they survive process death.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    settingsRepository: SettingsRepository,
    private val content: CalendarContentRepository,
    private val tasks: TaskRepository,
    private val calendars: CalendarsRepository,
    private val samples: SampleDataRepository,
    private val snackbar: SnackbarManager,
    launchRequests: LaunchRequests,
    timeSource: TimeSource,
) : ViewModel() {
    init {
        viewModelScope.launch {
            launchRequests.sheet.filterNotNull().collect { sheet ->
                setSearchOpen(false)
                openSheet(sheet)
                launchRequests.consume()
            }
        }
        viewModelScope.launch {
            launchRequests.view.filterNotNull().collect { request ->
                // The clock stops while the app is in the background: catch up on today first, or the
                // day asked for, if it was today back then, would count as left there and move on.
                followToday(timeSource.today())
                setSearchOpen(false)
                closeMonthPanel()
                selectDestination(request.destination)
                request.epochDay?.let { jumpTo(LocalDate.ofEpochDay(it)) }
                launchRequests.consumeView()
            }
        }
    }

    private val initialToday = timeSource.today()

    init {
        // The last day seen as today, kept across process death: a selection on it moves on (see followToday).
        if (!savedState.contains(KEY_LAST_TODAY)) savedState[KEY_LAST_TODAY] = initialToday.toEpochDay()
    }

    /** The device's zone and today in it, read in the same tick so the two always agree. */
    private data class Clock(val zone: ZoneId, val today: LocalDate)

    /**
     * Read again with every tick: the zone changes when the phone moves into another one (a flight),
     * today at midnight. A selection on today moves on with it.
     */
    private val clock = timeSource.ticker()
        .map { instant -> timeSource.zone().let { zone -> Clock(zone, instant.atZone(zone).toLocalDate()) } }
        .distinctUntilChanged()
        .onEach { followToday(it.today) }

    /** Current instant, refreshed every 30 seconds for "now" lines and past/running states. */
    val now: StateFlow<Instant> = timeSource.ticker().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), timeSource.now())

    private data class Navigation(
        val destination: String?,
        val selectedEpochDay: Long,
        val panelOpen: Boolean,
        val searchOpen: Boolean,
        val jumpRequest: Int,
    )

    private val navigation = combine(
        savedState.getStateFlow<String?>(KEY_DESTINATION, null),
        savedState.getStateFlow(KEY_SELECTED, initialToday.toEpochDay()),
        savedState.getStateFlow(KEY_PANEL, false),
        savedState.getStateFlow(KEY_SEARCH, false),
        savedState.getStateFlow(KEY_JUMP_REQUEST, 0),
    ) { destination, selected, panel, search, jump -> Navigation(destination, selected, panel, search, jump) }

    private val sheet = savedState.getStateFlow<String?>(KEY_SHEET, null).map(HomeSheet::decode)

    private val _loadFailed = MutableStateFlow(false)

    /** Reading the local data failed; the screen offers "Try again" instead of staying empty. */
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    private val _attempt = MutableStateFlow(0)

    /** Changes with every retry, so screens fetch fresh windows. */
    val attempt: StateFlow<Int> = _attempt.asStateFlow()

    private fun failed(error: Throwable) {
        AppLog.e("home", "Loading calendar data failed", error)
        _loadFailed.value = true
    }

    fun retry() {
        windows.clear()
        _loadFailed.value = false
        _attempt.value++
    }

    val uiState: StateFlow<HomeUiState?> = _attempt.flatMapLatest {
        combine(
            navigation,
            sheet,
            content.hasEntries,
            settingsRepository.settings,
            clock,
        ) { nav, sheet, hasEntries, settings, time -> HomeData(nav, sheet, hasEntries, settings, time) }
            .catch { failed(it) }
    }.map { (nav, sheet, hasEntries, settings, time) ->
        HomeUiState(
            destination = nav.destination?.let { name -> HomeDestination.entries.firstOrNull { it.name == name } }
                ?: HomeDestination.of(settings.startView),
            selectedDate = LocalDate.ofEpochDay(nav.selectedEpochDay),
            today = time.today,
            zone = time.zone,
            monthPanelOpen = nav.panelOpen,
            searchOpen = nav.searchOpen,
            jumpRequest = nav.jumpRequest,
            sheet = sheet,
            hasEntries = hasEntries,
            settings = settings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private data class HomeData(
        val nav: Navigation,
        val sheet: HomeSheet?,
        val hasEntries: Boolean,
        val settings: AppSettings,
        val clock: Clock,
    )

    /** All tasks of visible calendars for the task overview; null until loaded. */
    val taskItems: StateFlow<List<TaskItem>?> = combine(tasks.visibleTasks, calendars.calendars) { list, calendarList ->
        val byId = calendarList.associateBy { it.id }
        list.mapNotNull { task -> byId[task.calendarId]?.let { TaskItem(task, it) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _completingTasks = MutableStateFlow<Set<Long>>(emptySet())

    /** Tasks shown as checked while their completion is about to be saved. */
    val completingTasks: StateFlow<Set<Long>> = _completingTasks.asStateFlow()

    /** Recently viewed windows; kept while the screen lives so going back shows them instantly. */
    private val windows = object : LinkedHashMap<Pair<LocalDate, LocalDate>, StateFlow<CalendarWindow?>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<LocalDate, LocalDate>, StateFlow<CalendarWindow?>>) = size > MAX_CACHED_WINDOWS
    }

    /** Zone the cached [windows] were built in. */
    private var windowZone: ZoneId? = null

    /**
     * Entries of [start, endExclusive) by day in [zone]. Windows of another zone are dropped: after
     * the phone changed its time zone, every day begins at another instant.
     */
    fun window(start: LocalDate, endExclusive: LocalDate, zone: ZoneId): StateFlow<CalendarWindow?> {
        if (zone != windowZone) {
            windows.clear()
            windowZone = zone
        }
        return windows.getOrPut(start to endExclusive) {
            content.window(start, endExclusive, zone).catch { failed(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        }
    }

    fun selectDestination(destination: HomeDestination) {
        savedState[KEY_DESTINATION] = destination.name
    }

    fun selectDate(date: LocalDate) {
        savedState[KEY_SELECTED] = date.toEpochDay()
    }

    /**
     * When today changes (at midnight, or on a flight into another zone), a selection on the old
     * today moves on to the new one, so views left open overnight do not show yesterday. A day the
     * user picked otherwise stays.
     */
    private fun followToday(today: LocalDate) {
        val previous = savedState.get<Long>(KEY_LAST_TODAY)
        savedState[KEY_LAST_TODAY] = today.toEpochDay()
        if (previous != null && previous != today.toEpochDay() && savedState.get<Long>(KEY_SELECTED) == previous) selectDate(today)
    }

    /** Opens the day list below the month grid, or the day view when the day is already selected. */
    fun onMonthDayTapped(date: LocalDate) {
        val state = uiState.value ?: return
        if (state.monthPanelOpen && state.selectedDate == date) {
            openDay(date)
        } else {
            selectDate(date)
            savedState[KEY_PANEL] = true
        }
    }

    fun closeMonthPanel() {
        savedState[KEY_PANEL] = false
    }

    /** Swiped up on the month: the list of the selected day comes back. */
    fun openMonthPanel() {
        savedState[KEY_PANEL] = true
    }

    fun openDay(date: LocalDate) {
        selectDate(date)
        selectDestination(HomeDestination.DAY)
    }

    fun openMonth(date: LocalDate) {
        selectDate(date)
        selectDestination(HomeDestination.MONTH)
    }

    /** Selects [date] and asks scrolling views (agenda) to bring it into view. */
    fun jumpTo(date: LocalDate) {
        selectDate(date)
        savedState[KEY_JUMP_REQUEST] = (savedState.get<Int>(KEY_JUMP_REQUEST) ?: 0) + 1
    }

    fun setSearchOpen(open: Boolean) {
        savedState[KEY_SEARCH] = open
    }

    /**
     * A keyboard shortcut; returns whether it applied. None applies behind an open sheet or the date
     * picker ([pickerOpen]). Ctrl+arrows only page where no text field could want them for the cursor,
     * by a week where the day view shows the whole week ([dayShowsWeek]).
     */
    fun onShortcut(shortcut: HomeShortcut, pickerOpen: Boolean, agendaFirst: LocalDate, dayShowsWeek: Boolean): Boolean {
        val state = uiState.value ?: return false
        if (state.sheet != null || pickerOpen) return false
        val destination = shortcut.destination
        when {
            destination != null -> {
                setSearchOpen(false)
                selectDestination(destination)
            }
            shortcut == HomeShortcut.NEW_ENTRY -> newEntry()
            shortcut == HomeShortcut.SEARCH -> setSearchOpen(true)
            state.searchOpen -> return false
            shortcut == HomeShortcut.TODAY -> jumpTo(state.today)
            state.destination == HomeDestination.TASKS -> return false
            else -> stepPeriod(state, forward = shortcut == HomeShortcut.NEXT, agendaFirst = agendaFirst, dayShowsWeek = dayShowsWeek)
        }
        return true
    }

    /** The previous or next year, month, week or day, keeping the day of the month; the agenda moves by a week. */
    private fun stepPeriod(state: HomeUiState, forward: Boolean, agendaFirst: LocalDate, dayShowsWeek: Boolean) {
        val step = if (forward) 1L else -1L
        val selected = state.selectedDate
        when (state.destination) {
            HomeDestination.YEAR -> selectDate(CalendarMath.sameDayIn(YearMonth.from(selected).plusYears(step), selected.dayOfMonth))
            HomeDestination.MONTH -> selectDate(CalendarMath.sameDayIn(YearMonth.from(selected).plusMonths(step), selected.dayOfMonth))
            HomeDestination.DAY -> selectDate(if (dayShowsWeek) selected.plusWeeks(step) else selected.plusDays(step))
            HomeDestination.AGENDA -> jumpTo(agendaFirst.plusWeeks(step))
            HomeDestination.TASKS -> Unit
        }
    }

    fun openSheet(sheet: HomeSheet) {
        savedState[KEY_SHEET] = HomeSheet.encode(sheet)
    }

    fun closeSheet() {
        savedState[KEY_SHEET] = null
    }

    fun newEvent(date: LocalDate, minuteOfDay: Int? = null, allDay: Boolean = false, offset: ZoneOffset? = null) =
        openSheet(HomeSheet.Editor(EditorRequest.NewEvent(date.toEpochDay(), minuteOfDay, allDay, startOffsetSeconds = offset?.totalSeconds)))

    fun newEntry() {
        val state = uiState.value ?: return
        when (state.destination) {
            HomeDestination.TASKS -> openSheet(HomeSheet.Editor(EditorRequest.NewTask()))
            HomeDestination.AGENDA -> newEvent(state.today)
            else -> newEvent(state.selectedDate)
        }
    }

    fun newTask() = openSheet(HomeSheet.Editor(EditorRequest.NewTask()))

    /** "Create event with this title" from a search without results; today at the next half hour. */
    fun newEventTitled(title: String) {
        val today = uiState.value?.today ?: return
        openSheet(HomeSheet.Editor(EditorRequest.NewEvent(today.toEpochDay(), title = title)))
    }

    /** Quick add in the task overview: a task without date in the calendar used last. */
    fun quickAddTask(title: String) {
        viewModelScope.launch {
            val calendarId = calendars.preferredId(EntryKind.TASK) ?: return@launch
            tasks.create(Task(calendarId = calendarId, title = title))
        }
    }

    fun openOccurrence(occurrence: EventOccurrence) =
        openSheet(HomeSheet.EventDetail(occurrence.event.id, occurrence.originalStart))

    fun editTask(item: TaskItem) = openSheet(HomeSheet.Editor(EditorRequest.EditTask(item.task.id)))

    /** Confirms a save; entries on another day than the selected one get a "Show" action. */
    fun onEntrySaved(outcome: EditorOutcome.Saved) {
        val text = when (outcome.kind) {
            EntryKind.EVENT -> if (outcome.isNew) R.string.event_created else R.string.event_updated
            EntryKind.TASK -> if (outcome.isNew) R.string.task_created else R.string.task_updated
        }
        val date = outcome.date
        if (date == null || date == uiState.value?.selectedDate) {
            snackbar.show(UiText.Res(text))
        } else {
            snackbar.show(UiText.Res(text), UiText.Res(R.string.action_show)) {
                withContext(Dispatchers.Main) { jumpTo(date) }
            }
        }
    }

    private var loadingSamples = false

    fun loadSampleData() {
        if (loadingSamples) return
        loadingSamples = true
        viewModelScope.launch {
            samples.load()
            loadingSamples = false
            snackbar.show(UiText.Res(R.string.sample_loaded), UiText.Res(R.string.action_undo)) { samples.clear() }
        }
    }

    val snackbarMessage: StateFlow<SnackbarMessage?> = snackbar.current

    fun onSnackbarAction(message: SnackbarMessage) = snackbar.performAction(message)

    fun onSnackbarTimeout(message: SnackbarMessage) = snackbar.dismiss(message.id)

    /**
     * Completes or reopens a task, offering undo. A completed task stays checked in its list for a
     * moment before it leaves; the change is saved even if the screen goes away in between.
     */
    fun setTaskDone(item: TaskItem, done: Boolean) {
        val id = item.task.id
        viewModelScope.launch {
            try {
                if (done) {
                    _completingTasks.update { it + id }
                    delay(COMPLETE_DELAY_MILLIS)
                }
            } finally {
                withContext(NonCancellable) {
                    tasks.setDone(id, done)
                    _completingTasks.update { it - id }
                }
            }
            if (done) {
                snackbar.show(UiText.Res(R.string.task_completed_message, listOf(item.task.title)), UiText.Res(R.string.action_undo)) {
                    tasks.setDone(id, false)
                }
            }
        }
    }

    private companion object {
        const val KEY_DESTINATION = "destination"
        const val KEY_SELECTED = "selected_day"
        const val KEY_LAST_TODAY = "last_today"
        const val KEY_PANEL = "month_panel"
        const val KEY_SEARCH = "search_open"
        const val KEY_JUMP_REQUEST = "jump_request"
        const val KEY_SHEET = "sheet"
        const val MAX_CACHED_WINDOWS = 24
        const val TAG = "Home"
        const val COMPLETE_DELAY_MILLIS = 450L
    }
}
