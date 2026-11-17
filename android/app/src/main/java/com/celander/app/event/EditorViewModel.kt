package com.celander.app.event

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.celander.app.R
import com.celander.app.calendar.EditorRequest
import com.celander.app.calendar.data.CalendarsRepository
import com.celander.app.calendar.device.DeviceWriteException
import com.celander.app.core.model.CalendarInfo
import com.celander.app.core.model.CalendarSource
import com.celander.app.core.model.EntryKind
import com.celander.app.core.model.Event
import com.celander.app.core.model.EventTiming
import com.celander.app.core.model.ReminderDefaults
import com.celander.app.core.model.SeriesCapabilities
import com.celander.app.core.model.SourceIds
import com.celander.app.core.model.Task
import com.celander.app.core.places.PlaceSuggestion
import com.celander.app.core.recurrence.OccurrenceCalculator
import com.celander.app.core.recurrence.RRule
import com.celander.app.core.recurrence.SeriesEdits
import com.celander.app.core.recurrence.SeriesScope
import com.celander.app.core.time.CalendarMath
import com.celander.app.core.time.TimeSource
import com.celander.app.core.ui.SnackbarManager
import com.celander.app.core.ui.UiText
import com.celander.app.settings.SettingsRepository
import com.celander.app.task.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The editable state of the editor, in primitives so it can be saved in [SavedStateHandle] and
 * survive process death.
 */
@Serializable
data class EditorDraft(
    val requestKey: String,
    val kind: EntryKind,
    val title: String = "",
    val calendarId: Long? = null,
    val allDay: Boolean = false,
    val startDay: Long,
    val startMinute: Int,
    val endDay: Long,
    val endMinute: Int,
    val timeZone: String,
    val location: String = "",
    val description: String = "",
    val colorKey: String? = null,
    val rrule: String? = null,
    val reminders: List<Int> = emptyList(),
    val hasDueDate: Boolean = false,
    val hasDueTime: Boolean = false,
    val note: String = "",
    val eventId: Long? = null,
    val taskId: Long? = null,
    val uid: String? = null,
    val taskDone: Boolean = false,
    val isSample: Boolean = false,
    /** Set when one occurrence of a series is being edited; saving then asks for the scope. */
    val occurrenceKey: Long? = null,
    /** The repetition was changed in the editor, not just rewritten to follow a moved start. */
    val repeatEdited: Boolean = false,
    /**
     * The reminders are still the defaults of a new entry, not set by hand nor taken from another
     * event, so they follow a switch to another calendar. Existing entries never follow.
     */
    val remindersFromDefaults: Boolean = false,
    /** UTC offset (seconds) of the start when its wall-clock time occurs twice, as the clocks go back. */
    val startOffsetSeconds: Int? = null,
) {
    val startDate: LocalDate get() = LocalDate.ofEpochDay(startDay)
    val endDate: LocalDate get() = LocalDate.ofEpochDay(endDay)
    val startTime: LocalTime get() = LocalTime.of(startMinute / 60, startMinute % 60)
    val endTime: LocalTime get() = LocalTime.of(endMinute / 60, endMinute % 60)
    val start: LocalDateTime get() = startDate.atTime(startTime)
    val end: LocalDateTime get() = endDate.atTime(endTime)
    val isNew: Boolean get() = eventId == null && taskId == null

    /** An event has to last at least a minute (an all-day one at least its first day). */
    val endsTooEarly: Boolean get() = kind == EntryKind.EVENT && if (allDay) endDate.isBefore(startDate) else !end.isAfter(start)

    /** An event without a time of day (switched from all day or from a task) ends after its start. */
    fun withEndAfterStart(defaultMinutes: Int): EditorDraft {
        if (kind != EntryKind.EVENT || allDay || end.isAfter(start)) return this
        val fixed = start.plusMinutes(defaultMinutes.toLong())
        return copy(endDay = fixed.toLocalDate().toEpochDay(), endMinute = fixed.hour * 60 + fixed.minute)
    }

    /** Zone whose wall clock the event's recurrences follow. */
    val eventZone: ZoneId get() = runCatching { ZoneId.of(timeZone) }.getOrDefault(ZoneId.systemDefault())

    val repeat: RepeatSpec get() = RepeatRules.fromRRule(rrule, startDate, eventZone)

    /** Wall-clock fields are read in [deviceZone]; the event keeps its own zone for recurrences. */
    fun timing(deviceZone: ZoneId): EventTiming {
        if (allDay) return EventTiming.AllDay(startDate, maxOf(endDate, startDate))
        val offset = startOffsetSeconds?.let(ZoneOffset::ofTotalSeconds)
        val startInstant = ZonedDateTime.ofLocal(start, deviceZone, offset).toInstant()
        val endInstant = ZonedDateTime.ofLocal(end, deviceZone, offset).toInstant()
        val zone = runCatching { ZoneId.of(timeZone) }.getOrDefault(deviceZone)
        return EventTiming.Timed(startInstant, maxOf(startInstant, endInstant), zone)
    }
}

data class EditorUiState(
    val draft: EditorDraft? = null,
    /** The calendars the entry may go to: those that can hold its kind. */
    val calendars: List<CalendarInfo> = emptyList(),
    val titleError: Boolean = false,
    val endAdjusted: Boolean = false,
    val dirty: Boolean = false,
    /** Non-null while asking which occurrences of a series a change applies to. */
    val scopeChoices: List<SeriesScope>? = null,
    /** Some scopes are missing because the phone's calendar cannot store them. */
    val scopeLimited: Boolean = false,
) {
    /** The selected calendar if it is one of the phone's: no colors, its own reminder limits. */
    val deviceCalendar: CalendarInfo?
        get() = calendars.firstOrNull { it.id == draft?.calendarId }?.takeIf { it.source == CalendarSource.DEVICE }

    /** The task goes to a Google Tasks list, which stores only the date and limits text lengths. */
    val googleTask: Boolean
        get() = draft?.kind == EntryKind.TASK && calendars.firstOrNull { it.id == draft.calendarId }?.source == CalendarSource.GOOGLE_TASKS

    val titleTooLong: Boolean get() = googleTask && draft!!.title.trim().length > GOOGLE_TITLE_MAX
    val noteTooLong: Boolean get() = googleTask && draft!!.note.trim().length > GOOGLE_NOTE_MAX

    companion object {
        /** Google Tasks' limits for title and notes. */
        const val GOOGLE_TITLE_MAX = 1024
        const val GOOGLE_NOTE_MAX = 8192
    }

    val canSwitchKind: Boolean get() = draft?.isNew == true

    /** Saving waits until the end is after the start; the editor says so under "Ends". */
    val canSave: Boolean get() = draft?.endsTooEarly == false
}

/** Why the editor closed itself. */
sealed interface EditorOutcome {
    /** [date] is where the entry now appears, used to offer "show" for new entries. */
    data class Saved(val kind: EntryKind, val date: LocalDate?, val isNew: Boolean) : EditorOutcome

    data object Deleted : EditorOutcome

    /** Closed without changes to save. */
    data object Unchanged : EditorOutcome
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val events: EventRepository,
    private val tasks: TaskRepository,
    private val calendars: CalendarsRepository,
    private val settings: SettingsRepository,
    private val seriesEditor: SeriesEditor,
    private val snackbars: SnackbarManager,
    private val time: TimeSource,
    private val places: PlaceSearch,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val draft = MutableStateFlow(savedState.get<String>(KEY_DRAFT)?.let { runCatching { json.decodeFromString<EditorDraft>(it) }.getOrNull() })
    private val initial = MutableStateFlow(savedState.get<String>(KEY_INITIAL)?.let { runCatching { json.decodeFromString<EditorDraft>(it) }.getOrNull() })
    private val flags = MutableStateFlow(Flags())

    private data class Flags(
        val titleError: Boolean = false,
        val endAdjusted: Boolean = false,
        val scopeChoices: List<SeriesScope>? = null,
        val scopeLimited: Boolean = false,
    )

    // Kept until the sheet reads it, also when the sheet is just being recreated (a rotation).
    private val _outcomes = Channel<EditorOutcome>(Channel.BUFFERED)
    val outcomes: Flow<EditorOutcome> = _outcomes.receiveAsFlow()

    /** What is being typed into the location field; null once a place is picked or the field is left. */
    private val placeQuery = MutableStateFlow<String?>(null)

    /**
     * Places for the location typed so far: looked up only from [PLACE_MIN_CHARS] characters and
     * after a short pause in typing, so not every keystroke leaves the phone. Offline or on errors
     * the list stays empty and the field free text.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val placeSuggestions: StateFlow<List<PlaceSuggestion>> = placeQuery
        .transformLatest { query ->
            val text = query?.trim().orEmpty()
            if (text.length < PLACE_MIN_CHARS) {
                emit(emptyList())
                return@transformLatest
            }
            delay(PLACE_PAUSE_MILLIS)
            val found = try {
                places.suggest(text, Locale.getDefault().language)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            emit(found)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Guards against saving or deleting twice from quick double taps. */
    private var busy = false

    /** The app-wide default reminders, for calendars without their own. */
    private val globalReminders: StateFlow<ReminderDefaults?> = settings.settings
        .map { it.reminderDefaults }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<EditorUiState> = combine(draft, initial, flags, calendars.calendars) { d, i, f, list ->
        EditorUiState(
            draft = d,
            calendars = choosable(d, i, list),
            titleError = f.titleError,
            endAdjusted = f.endAdjusted,
            scopeChoices = f.scopeChoices,
            scopeLimited = f.scopeLimited,
            dirty = d != null && i != null && d != i,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, EditorUiState())

    /**
     * Calendars that can hold the entry's kind. An existing event stays with its kind of calendar
     * (no moves between local calendars and the phone's calendars); its own calendar is always listed.
     * A new entry only lists calendars for its current kind, so switching to a task never offers
     * the phone's calendars, nor switching to an event a Google Tasks list.
     */
    private fun choosable(d: EditorDraft?, i: EditorDraft?, list: List<CalendarInfo>): List<CalendarInfo> {
        if (d == null) return emptyList()
        if (d.isNew) return list.filter { it.offers(d.kind) }
        val own = list.firstOrNull { it.id == i?.calendarId }
        // An event in the phone's calendars stays in its calendar.
        if (own?.source == CalendarSource.DEVICE) return listOf(own)
        return list.filter { calendar ->
            calendar.id == own?.id || calendar.offers(d.kind) && (d.kind == EntryKind.TASK || own == null || calendar.source == own.source)
        }
    }

    /** Loads the entry for [request]; a restored draft for the same request is kept. */
    fun open(request: EditorRequest) {
        val key = request.toString()
        if (draft.value?.requestKey == key) return
        viewModelScope.launch {
            val created = createDraft(request, key) ?: return@launch
            initial.value = created
            draft.value = created
            flags.value = Flags()
            persist()
        }
    }

    fun reset() {
        busy = false
        placeQuery.value = null
        draft.value = null
        initial.value = null
        flags.value = Flags()
        savedState.remove<String>(KEY_DRAFT)
        savedState.remove<String>(KEY_INITIAL)
    }

    private suspend fun createDraft(request: EditorRequest, key: String): EditorDraft? {
        val s = settings.current()
        val zone = time.zone()
        val list = calendars.calendars.first { it.isNotEmpty() }
        val calendarId = calendars.preferredId(EntryKind.EVENT)
        fun defaults(id: Long?, kind: EntryKind, allDay: Boolean) =
            NewEntryReminders.forEntry(list.firstOrNull { it.id == id }, s.reminderDefaults, kind, allDay)
        val duration = s.defaultDurationMinutes.toLong()
        val now = time.nowZoned()

        fun newEventDraft(date: LocalDate, minute: Int?, allDay: Boolean, title: String): EditorDraft {
            val nextHalfHour = CalendarMath.nextHalfHour(now.toLocalDateTime())
            val start = when {
                minute != null -> date.atTime(minute / 60, minute % 60)
                // Late in the evening the next half hour is tomorrow, not today's midnight.
                date == now.toLocalDate() -> nextHalfHour
                else -> date.atTime(nextHalfHour.toLocalTime())
            }
            val end = start.plusMinutes(duration)
            return EditorDraft(
                requestKey = key,
                kind = EntryKind.EVENT,
                title = title,
                calendarId = calendarId,
                allDay = allDay,
                startDay = start.toLocalDate().toEpochDay(),
                startMinute = start.hour * 60 + start.minute,
                endDay = (if (allDay) start.toLocalDate() else end.toLocalDate()).toEpochDay(),
                endMinute = end.hour * 60 + end.minute,
                timeZone = zone.id,
                reminders = defaults(calendarId, EntryKind.EVENT, allDay),
                remindersFromDefaults = true,
            )
        }

        return when (request) {
            is EditorRequest.NewEvent -> newEventDraft(LocalDate.ofEpochDay(request.epochDay), request.minuteOfDay, request.allDay, request.title)
                .copy(startOffsetSeconds = request.startOffsetSeconds)
            is EditorRequest.NewTask -> {
                val date = request.epochDay?.let(LocalDate::ofEpochDay)
                val taskCalendarId = calendars.preferredId(EntryKind.TASK)
                newEventDraft(date ?: now.toLocalDate(), null, false, request.title).copy(
                    kind = EntryKind.TASK,
                    calendarId = taskCalendarId,
                    hasDueDate = date != null,
                    reminders = defaults(taskCalendarId, EntryKind.TASK, allDay = false),
                )
            }
            is EditorRequest.EditEvent -> events.getWithExceptions(request.eventId)?.let { loaded ->
                if (!loaded.event.isRecurring) return@let fromEvent(loaded.event, key, keepIdentity = true)
                fromEvent(occurrenceOf(loaded, request.occurrenceStart), key, keepIdentity = true).copy(occurrenceKey = request.occurrenceStart)
            }
            is EditorRequest.DuplicateEvent -> events.getWithExceptions(request.eventId)?.let { loaded ->
                // A duplicate is a single event at the chosen occurrence.
                val exception = loaded.exceptions.firstOrNull { it.originalStart == request.occurrenceStart && !it.cancelled }
                val timing = exception?.timing ?: if (loaded.event.isRecurring) OccurrenceCalculator.timingAt(loaded.event.timing, request.occurrenceStart) else loaded.event.timing
                // A copy of an event from a calendar that cannot take new events goes to the usual one.
                val sourceCalendar = calendars.first().firstOrNull { it.id == loaded.event.calendarId }
                val target = if (sourceCalendar?.offers(EntryKind.EVENT) == true) loaded.event.calendarId else calendarId ?: loaded.event.calendarId
                val keepsReminders = sourceCalendar?.source != CalendarSource.DEVICE || target == loaded.event.calendarId
                val reminders = if (keepsReminders) loaded.event.reminders else defaults(target, EntryKind.EVENT, timing is EventTiming.AllDay)
                fromEvent(
                    loaded.event.copy(
                        timing = timing,
                        rrule = null,
                        title = exception?.title ?: loaded.event.title,
                        location = exception?.location ?: loaded.event.location,
                        description = exception?.description ?: loaded.event.description,
                        calendarId = target,
                        reminders = reminders,
                    ),
                    key,
                    keepIdentity = false,
                ).copy(remindersFromDefaults = !keepsReminders)
            }
            is EditorRequest.EditTask -> tasks.get(request.taskId)?.let { fromTask(it, key) }
            is EditorRequest.InsertEvent -> insertedDraft(request, newEventDraft(now.toLocalDate(), null, request.allDay, request.title))
        }
    }

    /** [base] with what another app sent along; times it left out keep the usual defaults. */
    private fun insertedDraft(request: EditorRequest.InsertEvent, base: EditorDraft): EditorDraft {
        val zone = time.zone()
        val rrule = request.rrule?.takeIf { RRule.parse(it) != null }
        val filled = base.copy(location = request.location, description = request.description, rrule = rrule)
        val begin = request.beginMillis ?: return filled
        if (request.allDay) {
            // All-day times are midnight UTC and the end is exclusive; the editor's end day is inclusive.
            val start = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
            val end = request.endMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1) }
            return filled.copy(startDay = start.toEpochDay(), endDay = maxOf(start, end ?: start).toEpochDay())
        }
        val start = Instant.ofEpochMilli(begin).atZone(zone).toLocalDateTime()
        val length = Duration.between(base.start, base.end)
        val end = request.endMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }?.takeIf { !it.isBefore(start) } ?: start.plus(length)
        return filled.copy(
            startDay = start.toLocalDate().toEpochDay(),
            startMinute = start.hour * 60 + start.minute,
            endDay = end.toLocalDate().toEpochDay(),
            endMinute = end.hour * 60 + end.minute,
        )
    }

    private fun fromEvent(event: Event, key: String, keepIdentity: Boolean): EditorDraft {
        val zone = time.zone()
        val (start, end) = when (val t = event.timing) {
            is EventTiming.Timed -> t.start.atZone(zone).toLocalDateTime() to t.end.atZone(zone).toLocalDateTime()
            is EventTiming.AllDay -> t.startDate.atTime(9, 0) to t.endDate.atTime(10, 0)
        }
        // A start in the hour that repeats as the clocks go back keeps which of the two it is.
        val startOffset = (event.timing as? EventTiming.Timed)?.start?.atZone(zone)
            ?.takeIf { zone.rules.getValidOffsets(start).size > 1 }?.offset?.totalSeconds
        return EditorDraft(
            requestKey = key,
            kind = EntryKind.EVENT,
            title = event.title,
            calendarId = event.calendarId,
            allDay = event.allDay,
            startDay = start.toLocalDate().toEpochDay(),
            startMinute = start.hour * 60 + start.minute,
            endDay = end.toLocalDate().toEpochDay(),
            endMinute = end.hour * 60 + end.minute,
            timeZone = (event.timing as? EventTiming.Timed)?.zone?.id ?: zone.id,
            location = event.location,
            description = event.description,
            colorKey = event.colorKey,
            rrule = event.rrule,
            reminders = event.reminders,
            eventId = event.id.takeIf { keepIdentity },
            uid = event.uid.takeIf { keepIdentity },
            isSample = event.isSample && keepIdentity,
            startOffsetSeconds = startOffset,
        )
    }

    private fun fromTask(task: Task, key: String): EditorDraft {
        val date = task.dueDate ?: time.today()
        val timeOfDay = task.dueTime ?: Task.DEFAULT_TASK_ANCHOR
        return EditorDraft(
            requestKey = key,
            kind = EntryKind.TASK,
            title = task.title,
            calendarId = task.calendarId,
            startDay = date.toEpochDay(),
            startMinute = timeOfDay.hour * 60 + timeOfDay.minute,
            endDay = date.toEpochDay(),
            endMinute = timeOfDay.hour * 60 + timeOfDay.minute,
            timeZone = time.zone().id,
            reminders = task.reminders,
            hasDueDate = task.dueDate != null,
            hasDueTime = task.dueTime != null,
            note = task.note,
            taskId = task.id,
            taskDone = task.done,
            isSample = task.isSample,
        )
    }

    private fun update(transform: (EditorDraft) -> EditorDraft) {
        val current = draft.value ?: return
        draft.value = transform(current)
        persist()
    }

    private fun persist() {
        savedState[KEY_DRAFT] = draft.value?.let { json.encodeToString(EditorDraft.serializer(), it) }
        savedState[KEY_INITIAL] = initial.value?.let { json.encodeToString(EditorDraft.serializer(), it) }
    }

    /**
     * Switching a new entry between event and task also switches to that kind's default reminders,
     * those of the calendar it ends up in.
     */
    fun setKind(kind: EntryKind) {
        viewModelScope.launch {
            val s = settings.current()
            val list = calendars.first()
            // A calendar that cannot hold the new kind (a Google list for an event) is swapped.
            val currentId = draft.value?.calendarId
            val targetId = if (list.firstOrNull { it.id == currentId }?.offers(kind) == true) currentId else calendars.preferredId(kind)
            val target = list.firstOrNull { it.id == targetId }
            update { d ->
                if (!d.isNew) return@update d.copy(kind = kind, calendarId = targetId).withEndAfterStart(s.defaultDurationMinutes)
                d.copy(
                    kind = kind,
                    calendarId = targetId,
                    reminders = NewEntryReminders.forEntry(target, s.reminderDefaults, kind, d.allDay),
                    remindersFromDefaults = true,
                ).withEndAfterStart(s.defaultDurationMinutes)
            }
        }
    }

    /** Something could not be opened because no app handles it, e.g. a place without a maps app. */
    fun noAppFor(@StringRes message: Int) = snackbars.show(UiText.Res(message))

    fun setTitle(value: String) {
        update { it.copy(title = value) }
        if (value.isNotBlank()) flags.value = flags.value.copy(titleError = false)
    }

    /**
     * Default reminders of a new entry follow the calendar to its own defaults. Others stay, except
     * that the phone's calendars cannot store reminders on the day of an all-day event, and cap their number.
     */
    fun setCalendar(id: Long) {
        val calendar = state.value.calendars.firstOrNull { it.id == id }
        val global = globalReminders.value
        // Only right after opening can the app-wide defaults still be loading.
        if (global == null && draft.value?.remindersFromDefaults == true) {
            viewModelScope.launch { switchCalendar(id, calendar, settings.current().reminderDefaults) }
        } else {
            switchCalendar(id, calendar, global)
        }
    }

    private fun switchCalendar(id: Long, calendar: CalendarInfo?, global: ReminderDefaults?) {
        update { d ->
            val reminders = if (d.remindersFromDefaults && global != null) {
                NewEntryReminders.forEntry(calendar, global, d.kind, d.allDay)
            } else {
                NewEntryReminders.fit(calendar, d.reminders)
            }
            d.copy(calendarId = id, reminders = reminders)
        }
    }

    /** Untouched default reminders (the calendar's) follow the switch; a repeat end date changes its format. */
    fun setAllDay(value: Boolean) {
        viewModelScope.launch {
            val s = settings.current()
            val list = calendars.first()
            update { d ->
                val defaults = NewEntryReminders.effective(list.firstOrNull { it.id == d.calendarId }, s.reminderDefaults)
                val reminders = when {
                    value && d.reminders == defaults.event -> defaults.allDay
                    !value && d.reminders == defaults.allDay -> defaults.event
                    else -> d.reminders
                }
                val repeat = d.repeat
                d.copy(
                    allDay = value,
                    endDay = maxOf(d.endDay, d.startDay),
                    reminders = reminders,
                    rrule = if (repeat.end == RepeatEnd.UNTIL) RepeatRules.toRRule(repeat, d.startDate, value, d.eventZone) else d.rrule,
                ).withEndAfterStart(s.defaultDurationMinutes)
            }
        }
    }

    fun setRepeat(spec: RepeatSpec) = update { d ->
        val rrule = RepeatRules.toRRule(spec, d.startDate, d.allDay, d.eventZone)
        d.copy(rrule = rrule, repeatEdited = d.repeatEdited || rrule != d.rrule)
    }

    /** Moving the start keeps the duration. */
    fun setStart(date: LocalDate, time: LocalTime) {
        update { d ->
            val duration = Duration.between(d.start, d.end)
            val start = date.atTime(time)
            val end = start.plus(duration)
            // "Every 4th Friday" and end dates are tied to the start; plain rules follow it by themselves.
            // A weekly rule on just the start's weekday moves to the new weekday with it.
            val repeat = d.repeat
            val followed = if (repeat.kind == RepeatKind.WEEKLY && repeat.weekdays == setOf(d.startDate.dayOfWeek)) {
                repeat.copy(weekdays = setOf(start.dayOfWeek))
            } else {
                repeat
            }
            val rrule = if (repeat.kind == RepeatKind.MONTHLY_WEEKDAY || repeat.end == RepeatEnd.UNTIL || followed != repeat) {
                RepeatRules.toRRule(followed, start.toLocalDate(), d.allDay, d.eventZone)
            } else {
                d.rrule
            }
            d.copy(
                startDay = start.toLocalDate().toEpochDay(),
                startMinute = start.hour * 60 + start.minute,
                endDay = end.toLocalDate().toEpochDay(),
                endMinute = end.hour * 60 + end.minute,
                rrule = rrule,
                startOffsetSeconds = d.startOffsetSeconds.takeIf { start == d.start },
            )
        }
        flags.value = flags.value.copy(endAdjusted = false)
    }

    /** An end before the start is corrected automatically (start + default duration) with a hint. */
    fun setEnd(date: LocalDate, time: LocalTime) {
        viewModelScope.launch {
            val duration = settings.current().defaultDurationMinutes.toLong()
            var adjusted = false
            update { d ->
                var end = date.atTime(time)
                adjusted = if (d.allDay) date.isBefore(d.startDate) else !end.isAfter(d.start)
                if (adjusted) end = if (d.allDay) d.startDate.atTime(d.endTime) else d.start.plusMinutes(duration)
                d.copy(endDay = end.toLocalDate().toEpochDay(), endMinute = end.hour * 60 + end.minute)
            }
            flags.value = flags.value.copy(endAdjusted = adjusted)
        }
    }

    fun setLocation(value: String) {
        update { it.copy(location = value) }
        placeQuery.value = value
    }

    /** Takes a suggested place into the location field, which stays editable. */
    fun pickPlace(place: PlaceSuggestion) {
        update { it.copy(location = place.text) }
        placeQuery.value = null
    }

    /** The location field lost focus: its suggestions go away. */
    fun endPlaceSearch() {
        placeQuery.value = null
    }

    fun setDescription(value: String) = update { it.copy(description = value) }

    fun setColor(key: String?) = update { it.copy(colorKey = key) }

    fun setNote(value: String) = update { it.copy(note = value) }

    fun setHasDueDate(value: Boolean) = update { it.copy(hasDueDate = value, hasDueTime = value && it.hasDueTime) }

    fun setHasDueTime(value: Boolean) = update { it.copy(hasDueTime = value) }

    fun setDue(date: LocalDate, time: LocalTime) = update {
        it.copy(startDay = date.toEpochDay(), endDay = date.toEpochDay(), startMinute = time.hour * 60 + time.minute, endMinute = time.hour * 60 + time.minute)
    }

    /** Reminders set by hand no longer follow the calendar's defaults. */
    fun setReminders(minutes: List<Int>) = update { d ->
        val reminders = minutes.distinct().sorted()
        d.copy(reminders = reminders, remindersFromDefaults = d.remindersFromDefaults && reminders == d.reminders)
    }

    /** Validates and saves; on success [outcomes] emits and the draft is cleared. */
    fun save() {
        val d = draft.value ?: return
        if (busy) return
        if (d.title.isBlank()) {
            flags.value = flags.value.copy(titleError = true)
            return
        }
        val calendarId = d.calendarId ?: return
        if (d.endsTooEarly) return
        if (state.value.titleTooLong || state.value.noteTooLong) return
        if (d.kind == EntryKind.EVENT && d.occurrenceKey != null) {
            askScope(d)
            return
        }
        busy = true
        viewModelScope.launch {
            val result = try {
                when (d.kind) {
                    EntryKind.EVENT -> saveEvent(d, calendarId)
                    EntryKind.TASK -> saveTask(d, calendarId)
                }
            } catch (e: DeviceWriteException) {
                saveFailed(e)
                return@launch
            }
            rememberCalendar(d.kind, calendarId)
            reset()
            _outcomes.trySend(result)
        }
    }

    /** The phone's calendar refused the write: the editor stays open with the draft. */
    private fun saveFailed(e: DeviceWriteException) {
        busy = false
        snackbars.show(UiText.Res(R.string.event_save_failed, listOf(e.calendarName)))
    }

    /**
     * A change to one occurrence of a series asks where it applies. Changes that only make sense for
     * the whole rule (calendar, color, reminders, repetition) cannot be limited to one occurrence.
     */
    private fun askScope(d: EditorDraft) {
        val i = initial.value
        if (i == null || d == i) {
            reset()
            _outcomes.trySend(EditorOutcome.Unchanged)
            return
        }
        val seriesWide = d.calendarId != i.calendarId || d.colorKey != i.colorKey || d.reminders != i.reminders || d.repeatEdited
        val wanted = if (seriesWide) listOf(SeriesScope.THIS_AND_FOLLOWING, SeriesScope.ALL) else SeriesScope.entries
        val masterId = d.eventId
        if (masterId == null || !SourceIds.isDevice(masterId)) {
            flags.value = flags.value.copy(scopeChoices = wanted, scopeLimited = false)
            return
        }
        viewModelScope.launch {
            // A series from the phone's calendars may not take every scope without losing data.
            val capabilities = events.getWithExceptions(masterId)?.capabilities ?: SeriesCapabilities()
            val zone = time.zone()
            val timeChanged = d.timing(zone) != i.timing(zone)
            val allowed = wanted.filter { scope ->
                when (scope) {
                    SeriesScope.THIS -> true
                    SeriesScope.THIS_AND_FOLLOWING -> capabilities.canSplit
                    SeriesScope.ALL -> !timeChanged || capabilities.canShiftAll
                }
            }
            flags.value = flags.value.copy(scopeChoices = allowed, scopeLimited = allowed.size < wanted.size)
        }
    }

    fun cancelScope() {
        flags.value = flags.value.copy(scopeChoices = null, scopeLimited = false)
    }

    fun saveWithScope(scope: SeriesScope) {
        val d = draft.value ?: return
        val calendarId = d.calendarId ?: return
        val key = d.occurrenceKey ?: return
        val masterId = d.eventId ?: return
        if (busy) return
        busy = true
        flags.value = flags.value.copy(scopeChoices = null, scopeLimited = false)
        viewModelScope.launch {
            try {
                val loaded = events.getWithExceptions(masterId)
                val built = buildEvent(d, calendarId, loaded?.event)
                // What was not edited keeps the series as it is now, also after a sync meanwhile.
                val edited = loaded?.let { editedOnto(occurrenceOf(it, key), built, d, initial.value) } ?: built
                seriesEditor.save(masterId, key, edited, scope)
            } catch (e: DeviceWriteException) {
                saveFailed(e)
                return@launch
            }
            rememberCalendar(EntryKind.EVENT, calendarId)
            reset()
            _outcomes.trySend(EditorOutcome.Saved(EntryKind.EVENT, d.startDate, isNew = false))
        }
    }

    private suspend fun rememberCalendar(kind: EntryKind, calendarId: Long) = settings.update {
        if (kind == EntryKind.EVENT) it.copy(lastUsedCalendarId = calendarId) else it.copy(lastUsedTaskCalendarId = calendarId)
    }

    /** Deletes the task being edited; the snackbar offers undo. */
    fun deleteTask() {
        val id = draft.value?.taskId ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            val removed = tasks.delete(id)
            reset()
            _outcomes.trySend(EditorOutcome.Deleted)
            if (removed != null) {
                snackbars.show(UiText.Res(R.string.task_deleted), UiText.Res(R.string.action_undo)) { tasks.restore(removed) }
            }
        }
    }

    private suspend fun saveEvent(d: EditorDraft, calendarId: Long): EditorOutcome.Saved {
        val existing = d.eventId?.let { events.get(it) }
        val built = buildEvent(d, calendarId, existing)
        val event = when {
            existing == null -> SeriesEdits.startOnRule(built)
            else -> {
                val merged = editedOnto(existing, built, d, initial.value)
                if (d.repeatEdited) SeriesEdits.startOnRule(merged) else merged
            }
        }
        if (existing == null) events.create(event) else events.update(event)
        return EditorOutcome.Saved(EntryKind.EVENT, event.timing.startDate(time.zone()), isNew = existing == null)
    }

    /**
     * What was edited since opening ([d] against [i]) on top of [existing] as it is now: changes
     * made meanwhile elsewhere (a sync, another app) survive in the fields the editor left alone.
     */
    private fun editedOnto(existing: Event, edited: Event, d: EditorDraft, i: EditorDraft?): Event {
        if (i == null) return edited
        val timingEdited = d.allDay != i.allDay || d.startDay != i.startDay || d.startMinute != i.startMinute ||
            d.endDay != i.endDay || d.endMinute != i.endMinute || d.timeZone != i.timeZone
        return existing.copy(
            calendarId = if (d.calendarId != i.calendarId) edited.calendarId else existing.calendarId,
            title = if (d.title != i.title) edited.title else existing.title,
            location = if (d.location != i.location) edited.location else existing.location,
            description = if (d.description != i.description) edited.description else existing.description,
            colorKey = if (d.colorKey != i.colorKey) edited.colorKey else existing.colorKey,
            timing = if (timingEdited) edited.timing else existing.timing,
            rrule = if (d.rrule != i.rrule) edited.rrule else existing.rrule,
            reminders = if (d.reminders != i.reminders) edited.reminders else existing.reminders,
        )
    }

    /** The occurrence of a series at [key] as the editor shows it, with its earlier changes. */
    private fun occurrenceOf(loaded: EventWithExceptions, key: Long): Event {
        val event = loaded.event
        val exception = loaded.exceptions.firstOrNull { it.originalStart == key && !it.cancelled }
        return event.copy(
            timing = exception?.timing ?: OccurrenceCalculator.timingAt(event.timing, key),
            title = exception?.title ?: event.title,
            location = exception?.location ?: event.location,
            description = exception?.description ?: event.description,
        )
    }

    private fun buildEvent(d: EditorDraft, calendarId: Long, existing: Event?) = Event(
        id = existing?.id ?: 0,
        uid = existing?.uid ?: d.uid ?: UUID.randomUUID().toString(),
        calendarId = calendarId,
        title = d.title.trim(),
        location = d.location.trim(),
        description = d.description.trim(),
        colorKey = d.colorKey,
        timing = d.timing(time.zone()),
        rrule = d.rrule,
        reminders = d.reminders,
        isSample = existing?.isSample ?: false,
        createdAt = existing?.createdAt ?: 0,
    )

    private suspend fun saveTask(d: EditorDraft, calendarId: Long): EditorOutcome.Saved {
        val existing = d.taskId?.let { tasks.get(it) }
        val task = Task(
            id = existing?.id ?: 0,
            calendarId = calendarId,
            title = d.title.trim(),
            note = d.note.trim(),
            dueDate = d.startDate.takeIf { d.hasDueDate },
            dueTime = d.startTime.takeIf { d.hasDueDate && d.hasDueTime },
            reminders = if (d.hasDueDate) d.reminders else emptyList(),
            done = existing?.done ?: false,
            completedAt = existing?.completedAt,
            isSample = existing?.isSample ?: false,
            createdAt = existing?.createdAt ?: 0,
        )
        val saved = if (existing == null) task else editedOnto(existing, task, d, initial.value)
        if (existing == null) tasks.create(saved) else tasks.update(saved)
        return EditorOutcome.Saved(EntryKind.TASK, saved.dueDate, isNew = existing == null)
    }

    /** Like for events: only what was edited replaces the task as it is now (e.g. after a Google sync). */
    private fun editedOnto(existing: Task, edited: Task, d: EditorDraft, i: EditorDraft?): Task {
        if (i == null) return edited
        val dueEdited = d.hasDueDate != i.hasDueDate || d.hasDueTime != i.hasDueTime || d.startDay != i.startDay || d.startMinute != i.startMinute
        return existing.copy(
            calendarId = if (d.calendarId != i.calendarId) edited.calendarId else existing.calendarId,
            title = if (d.title != i.title) edited.title else existing.title,
            note = if (d.note != i.note) edited.note else existing.note,
            dueDate = if (dueEdited) edited.dueDate else existing.dueDate,
            dueTime = if (dueEdited) edited.dueTime else existing.dueTime,
            reminders = if (dueEdited || d.reminders != i.reminders) edited.reminders else existing.reminders,
        )
    }

    private companion object {
        const val KEY_DRAFT = "editor_draft"
        const val KEY_INITIAL = "editor_initial"
        const val PLACE_MIN_CHARS = 3
        const val PLACE_PAUSE_MILLIS = 400L
    }
}
