package com.celander.app.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.celander.app.calendar.data.CalendarsRepository
import com.celander.app.core.model.CalendarInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * What the result area shows. [Found.query] tells which query the hits belong to; while a new query
 * runs, the previous hits stay visible instead of flashing an empty list.
 */
sealed interface SearchResults {
    data object Idle : SearchResults

    data class Found(val query: String, val filter: SearchFilter, val hits: List<SearchHit>) : SearchResults
}

data class SearchUiState(
    val filter: SearchFilter = SearchFilter(),
    val calendars: List<CalendarInfo> = emptyList(),
    val results: SearchResults = SearchResults.Idle,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val repository: SearchRepository,
    calendars: CalendarsRepository,
) : ViewModel() {
    private val query = MutableStateFlow(savedState.get<String>(KEY_QUERY).orEmpty())
    private val filter = MutableStateFlow(
        SearchFilter(
            range = savedState.get<String>(KEY_RANGE)?.let { name -> SearchRange.entries.firstOrNull { it.name == name } } ?: SearchRange.ALL,
            calendarIds = savedState.get<LongArray>(KEY_CALENDARS)?.toSet().orEmpty(),
        ),
    )

    /** The text as typed; the field reads it directly so typing never lags behind the search. */
    val text: StateFlow<String> = query

    private val results = combine(query.debounce { if (it.isBlank()) 0L else DEBOUNCE_MILLIS }, filter) { q, f -> q.trim() to f }
        .distinctUntilChanged()
        .flatMapLatest { (q, f) ->
            if (q.isEmpty()) {
                flowOf(SearchResults.Idle)
            } else {
                repository.results(q, f).map { SearchResults.Found(q, f, it) }
            }
        }

    val state: StateFlow<SearchUiState> = combine(filter, calendars.calendars, results) { f, list, r ->
        SearchUiState(filter = f, calendars = list.filter { it.shown }, results = r)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun setQuery(value: String) {
        query.value = value
        savedState[KEY_QUERY] = value
    }

    fun setRange(range: SearchRange) {
        filter.update { it.copy(range = range) }
        savedState[KEY_RANGE] = range.name
    }

    fun toggleCalendar(id: Long) {
        filter.update { f -> f.copy(calendarIds = if (id in f.calendarIds) f.calendarIds - id else f.calendarIds + id) }
        savedState[KEY_CALENDARS] = filter.value.calendarIds.toLongArray()
    }

    /** Leaving the search starts the next one fresh. */
    fun reset() {
        setQuery("")
        setRange(SearchRange.ALL)
        filter.update { it.copy(calendarIds = emptySet()) }
        savedState[KEY_CALENDARS] = LongArray(0)
    }

    private companion object {
        const val KEY_QUERY = "search_query"
        const val KEY_RANGE = "search_range"
        const val KEY_CALENDARS = "search_calendars"
        const val DEBOUNCE_MILLIS = 150L
    }
}
