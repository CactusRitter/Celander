package com.celander.app.intake

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.celander.app.R
import com.celander.app.calendar.EditorRequest
import com.celander.app.calendar.data.CalendarsRepository
import com.celander.app.core.ical.IcsMapper
import com.celander.app.core.model.CalendarInfo
import com.celander.app.core.model.EntryKind
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.EventTiming
import com.celander.app.core.time.TimeSource
import com.celander.app.event.NewEntryReminders
import com.celander.app.event.reminderDefaults
import com.celander.app.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What was done with an entry. */
enum class Applied { ADDED, UPDATED, DELETED }

data class IntakeItem(
    val incoming: IncomingEvent,
    val verdict: Verdict,
    val existing: ExistingEvent?,
    /** The calendar a new entry goes to. */
    val calendarId: Long?,
    val reminders: List<Int>,
    /** Reminders set by hand no longer follow the chosen calendar. */
    val remindersEdited: Boolean = false,
    val conflicts: List<EventOccurrence> = emptyList(),
    val applied: Applied? = null,
) {
    /** A cancellation of the whole event shows what is there; everything else shows what the file says. */
    private val showsExisting: Boolean get() = existing != null && IntakePlan.deletesWholeEvent(incoming, existing)

    val title: String get() = if (showsExisting) existing!!.event.title else incoming.shown.summary
    val location: String get() = if (showsExisting) existing!!.event.location else incoming.shown.location
    val description: String get() = if (showsExisting) existing!!.event.description else incoming.shown.description
    val rrule: String? get() = if (showsExisting) existing!!.event.rrule else incoming.shown.rrule
    val timing: EventTiming get() = if (showsExisting) existing!!.event.timing else incoming.timing
}

val IncomingEvent.timing: EventTiming
    get() = IcsMapper.fromIcal(listOf(shown.copy(recurrenceId = null, cancelled = false)), 0).first().first.timing

sealed interface IntakeState {
    data object Loading : IntakeState

    /** A new event for the editor, which is the review before saving. */
    data class Edit(val request: EditorRequest.InsertEvent) : IntakeState

    /** Nothing to take over, and why. */
    data class Message(val text: Int) : IntakeState

    data class Review(
        val items: List<IntakeItem>,
        val calendars: List<CalendarInfo>,
        /** The entry shown in detail; null shows the list of several. */
        val selected: Int?,
        val truncated: Boolean = false,
    ) : IntakeState
}

@HiltViewModel
class IntakeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val intake: IntakeRepository,
    private val calendars: CalendarsRepository,
    private val settings: SettingsRepository,
    private val time: TimeSource,
) : ViewModel() {
    private val _state = MutableStateFlow<IntakeState>(IntakeState.Loading)
    val state: StateFlow<IntakeState> = _state.asStateFlow()
    private var started = false
    private var busy = false

    /** Reads what the intent brought; only once, the view model outlives a rotation. */
    fun load(intent: Intent) {
        if (started) return
        started = true
        val action = intent.action
        val data = intent.data
        // Extras of another app may hold classes Celander does not have; then they are left out.
        val extras: Map<String, Any?> = runCatching {
            @Suppress("DEPRECATION")
            intent.extras?.let { bundle -> bundle.keySet().associateWith { bundle.get(it) } }
        }.getOrNull().orEmpty()
        viewModelScope.launch {
            _state.value = try {
                read(action, data, extras)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                IntakeState.Message(R.string.intake_error_unreadable)
            }
        }
    }

    private suspend fun read(action: String?, data: Uri?, extras: Map<String, Any?>): IntakeState {
        if (action == Intent.ACTION_VIEW) {
            val content = withContext(Dispatchers.IO) { data?.let(::readFile) ?: IcsContent.Unreadable }
            return show(content) ?: IntakeState.Message(messageFor(content))
        }
        // Celander has no way back from an id of Android's calendar database to one of its events.
        if (action == Intent.ACTION_EDIT && data?.lastPathSegment?.toLongOrNull() != null) {
            return IntakeState.Message(R.string.intake_error_edit)
        }
        val standard = Incoming.fromExtras(extras) { context.getString(R.string.intake_guests, it) }
        val ics = (extras[Incoming.EXTRA_ICS] as? CharSequence)?.toString()
        if (ics != null) {
            val content = withContext(Dispatchers.Default) { Incoming.readIcs(ics) }
            show(content)?.let { return it }
            // A broken .ics still leaves the standard extras; a reply must not become an event.
            if (content == IcsContent.Reply || standard == null) return IntakeState.Message(messageFor(content))
        }
        return IntakeState.Edit(standard ?: EditorRequest.InsertEvent())
    }

    private fun messageFor(content: IcsContent): Int = when (content) {
        IcsContent.Reply -> R.string.intake_error_reply
        IcsContent.TooLarge -> R.string.intake_error_too_large
        else -> R.string.intake_error_unreadable
    }

    private fun readFile(uri: Uri): IcsContent {
        if (uri.scheme != "content" && uri.scheme != "file") return IcsContent.Unreadable
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                while (out.size() <= Incoming.MAX_ICS_BYTES) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return IcsContent.Unreadable
        if (bytes.size > Incoming.MAX_ICS_BYTES) return IcsContent.TooLarge
        return Incoming.readIcs(bytes.decodeToString())
    }

    private suspend fun show(content: IcsContent): IntakeState.Review? {
        if (content !is IcsContent.Events) return null
        // The first calendar is created at the first start of the app, which this may be.
        val list = calendars.calendars.first { it.isNotEmpty() }
        val writable = list.filter { it.offers(EntryKind.EVENT) }.ifEmpty { list.filter { it.canHold(EntryKind.EVENT) } }
        val preferred = calendars.preferredId(EntryKind.EVENT)
        val global = settings.current().reminderDefaults
        val items = content.entries.mapNotNull { incoming ->
            // An entry whose dates or rule defeat the calculation is left out instead of failing all.
            runCatching {
                val reminders = NewEntryReminders.forEntry(list.firstOrNull { it.id == preferred }, global, EntryKind.EVENT, incoming.shown.allDay)
                judged(IntakeItem(incoming, Verdict.NEW, null, preferred, reminders))
            }.getOrNull()
        }
        if (items.isEmpty()) return null
        return IntakeState.Review(items, writable, selected = if (items.size == 1) 0 else null, truncated = content.truncated)
    }

    /** [item] against what is in the calendar now. */
    private suspend fun judged(item: IntakeItem): IntakeItem {
        val existing = intake.find(item.incoming)
        val verdict = IntakePlan.verdict(item.incoming, existing)
        val found = item.copy(verdict = verdict, existing = existing)
        val conflicts = if (verdict == Verdict.NEW || verdict == Verdict.UPDATE) {
            runCatching { intake.conflicts(found.timing, existing?.event?.id, time.zone()) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return found.copy(conflicts = conflicts)
    }

    suspend fun conflicts(timing: EventTiming): List<EventOccurrence> = intake.conflicts(timing, null, time.zone())

    fun select(index: Int?) = review { it.copy(selected = index) }

    fun setCalendar(id: Long) {
        viewModelScope.launch {
            val global = settings.current().reminderDefaults
            selectedItem { item, review ->
                val calendar = review.calendars.firstOrNull { it.id == id }
                item.copy(
                    calendarId = id,
                    reminders = if (item.remindersEdited) {
                        item.reminders.take(calendar?.maxReminders ?: item.reminders.size)
                    } else {
                        NewEntryReminders.forEntry(calendar, global, EntryKind.EVENT, item.incoming.shown.allDay)
                    },
                )
            }
        }
    }

    fun setReminders(minutes: List<Int>) = selectedItem { item, _ -> item.copy(reminders = minutes.sorted(), remindersEdited = true) }

    /** The user pressed "Add", "Update" or confirmed "Delete" for the shown entry. */
    fun confirm() {
        val review = _state.value as? IntakeState.Review ?: return
        val index = review.selected ?: return
        val item = review.items[index]
        if (busy || item.applied != null) return
        busy = true
        viewModelScope.launch {
            // Looked up again: a sync may have changed or brought the event since the file was opened.
            val now = judged(item)
            val applied = when {
                now.verdict != item.verdict -> null
                now.verdict == Verdict.NEW -> item.calendarId?.let {
                    intake.add(item.incoming, it, item.reminders)
                    settings.update { s -> s.copy(lastUsedCalendarId = item.calendarId) }
                    Applied.ADDED
                }
                now.verdict == Verdict.UPDATE -> now.existing?.let {
                    intake.apply(item.incoming, it)
                    Applied.UPDATED
                }
                now.verdict == Verdict.CANCEL -> now.existing?.let {
                    intake.apply(item.incoming, it)
                    Applied.DELETED
                }
                else -> null
            }
            val result = if (applied != null) item.copy(applied = applied, conflicts = emptyList()) else now
            review { current -> current.copy(items = current.items.mapIndexed { i, old -> if (i == index) result else old }) }
            busy = false
        }
    }

    private fun review(change: (IntakeState.Review) -> IntakeState.Review) {
        val current = _state.value as? IntakeState.Review ?: return
        _state.value = change(current)
    }

    private fun selectedItem(change: (IntakeItem, IntakeState.Review) -> IntakeItem) = review { current ->
        current.copy(items = current.items.mapIndexed { i, item -> if (i == current.selected) change(item, current) else item })
    }
}
