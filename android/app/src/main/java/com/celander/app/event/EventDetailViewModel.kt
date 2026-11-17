package com.celander.app.event

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.celander.app.R
import com.celander.app.calendar.data.CalendarsRepository
import com.celander.app.core.model.EventAccess
import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.SeriesCapabilities
import com.celander.app.core.recurrence.OccurrenceCalculator
import com.celander.app.core.recurrence.SeriesScope
import com.celander.app.core.ui.SnackbarManager
import com.celander.app.core.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface EventDetailUiState {
    data object Loading : EventDetailUiState

    /** The event was deleted (here or elsewhere) while its details were open. */
    data class Missing(val eventId: Long) : EventDetailUiState

    data class Ready(
        val occurrence: EventOccurrence,
        val exceptions: List<EventException>,
        val access: EventAccess = EventAccess(),
        val capabilities: SeriesCapabilities = SeriesCapabilities(),
    ) : EventDetailUiState {
        /** Scopes the event can be deleted with; a series from the phone's calendars may allow fewer. */
        val deleteScopes: List<SeriesScope>
            get() = if (!access.canDelete) emptyList() else SeriesScope.entries.filter { it != SeriesScope.THIS_AND_FOLLOWING || capabilities.canCap }
    }

    /** This state if it belongs to [eventId] at [occurrenceStart]; the view model is reused between sheets. */
    fun forTarget(eventId: Long, occurrenceStart: Long): EventDetailUiState = when (this) {
        Loading -> this
        is Missing -> if (this.eventId == eventId) this else Loading
        is Ready -> if (occurrence.event.id == eventId && occurrence.originalStart == occurrenceStart) this else Loading
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EventDetailViewModel @Inject constructor(
    private val events: EventRepository,
    private val actions: EventActions,
    calendars: CalendarsRepository,
    private val snackbar: SnackbarManager,
) : ViewModel() {
    private val target = MutableStateFlow<Pair<Long, Long>?>(null)

    val state: StateFlow<EventDetailUiState> = target.flatMapLatest { t ->
        if (t == null) {
            flowOf(EventDetailUiState.Loading)
        } else {
            combine(events.observe(t.first), calendars.calendars) { loaded, list ->
                val calendar = loaded?.let { l -> list.firstOrNull { it.id == l.event.calendarId } }
                if (loaded == null || calendar == null) {
                    EventDetailUiState.Missing(t.first)
                } else {
                    val exception = loaded.exceptions.firstOrNull { it.originalStart == t.second && !it.cancelled }
                    val occurrence = if (loaded.event.isRecurring) {
                        OccurrenceCalculator.occurrence(loaded.event, calendar, t.second, exception)
                    } else {
                        OccurrenceCalculator.single(loaded.event, calendar)
                    }
                    EventDetailUiState.Ready(occurrence, loaded.exceptions, loaded.access, loaded.capabilities)
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventDetailUiState.Loading)

    fun show(eventId: Long, occurrenceStart: Long) {
        target.value = eventId to occurrenceStart
    }

    /** Something could not be opened because no app handles it, e.g. a place without a maps app. */
    fun noAppFor(@StringRes message: Int) = snackbar.show(UiText.Res(message))

    /** Deletes without confirmation; the snackbar offers undo. */
    fun delete(occurrence: EventOccurrence, scope: SeriesScope) {
        viewModelScope.launch {
            val deletion = actions.delete(occurrence.event.id, occurrence.originalStart, scope) ?: return@launch
            val message = if (occurrence.isRecurring && scope != SeriesScope.ALL) R.string.event_deleted_occurrence else R.string.event_deleted
            snackbar.show(UiText.Res(message, listOf(occurrence.title)), UiText.Res(R.string.action_undo), action = deletion.undo)
        }
    }
}
