package com.celander.app.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.celander.app.R
import com.celander.app.calendar.data.CalendarsRepository
import com.celander.app.calendar.data.SampleDataRepository
import com.celander.app.calendar.device.DeviceCalendarSource
import com.celander.app.calendar.device.DeviceCalendarStatus
import com.celander.app.core.model.CalendarInfo
import com.celander.app.core.model.ReminderDefaults
import com.celander.app.core.ui.SnackbarManager
import com.celander.app.core.ui.SnackbarMessage
import com.celander.app.core.ui.UiText
import com.celander.app.task.google.GoogleTasksAccounts
import com.celander.app.widget.WidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A calendar in the settings list with how many entries it holds. */
data class CalendarRow(val calendar: CalendarInfo, val entries: Int, val isDefault: Boolean, val isTaskDefault: Boolean = false)

/** The phone's calendars as the settings show them. */
data class GoogleCalendarState(val status: DeviceCalendarStatus)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val calendars: CalendarsRepository,
    private val samples: SampleDataRepository,
    private val reset: DataReset,
    private val snackbar: SnackbarManager,
    device: DeviceCalendarSource,
    googleAccounts: GoogleAccounts,
    googleTasksAccounts: GoogleTasksAccounts,
) : ViewModel() {
    val googleTasks: StateFlow<GoogleTasksSummary> = googleTasksAccounts.state.map { list -> GoogleTasksSummary(list.map { it.email }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GoogleTasksSummary(googleTasksAccounts.emails))

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val calendarRows: StateFlow<List<CalendarRow>?> = combine(
        calendars.calendars,
        calendars.entryCounts,
        calendars.defaultCalendarId,
        calendars.defaultTaskCalendarId,
    ) { list, counts, defaultId, taskDefaultId ->
        list.map { CalendarRow(it, counts[it.id] ?: 0, it.id == defaultId, it.id == taskDefaultId) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val googleCalendar: StateFlow<GoogleCalendarState?> = device.status.map { GoogleCalendarState(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Summary of the Google accounts for the overview; the page itself has its own view model. */
    val googleOverview: StateFlow<GoogleOverview?> = combine(googleAccounts.accounts(), device.status) { accounts, status -> GoogleOverview(accounts, status) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val hasSampleData: StateFlow<Boolean?> = samples.hasSampleData.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val snackbarMessage: StateFlow<SnackbarMessage?> = snackbar.current

    private var busy = false

    /**
     * Changes made while a slider is dragged. A state flow keeps only the newest one while a write is
     * running, so the app follows the finger without writing every frame.
     */
    private val liveChanges = MutableStateFlow<((AppSettings) -> AppSettings)?>(null)

    init {
        viewModelScope.launch { liveChanges.filterNotNull().collect { settingsRepository.update(it) } }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    fun updateLive(transform: (AppSettings) -> AppSettings) {
        liveChanges.value = transform
    }

    fun setVisible(id: Long, visible: Boolean) {
        viewModelScope.launch { calendars.setVisible(id, visible) }
    }

    fun setDefault(id: Long) {
        viewModelScope.launch { calendars.setDefault(id) }
    }

    fun setDefaultTask(id: Long) {
        viewModelScope.launch { calendars.setDefaultTask(id) }
    }

    /**
     * Creates a calendar when [id] is null, otherwise renames and recolors it; [reminders] are its
     * own default reminders (null: the app-wide ones). One after the other, so neither write undoes the other.
     */
    fun saveCalendar(id: Long?, name: String, colorKey: String, reminders: ReminderDefaults? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            if (id == null) {
                val created = calendars.create(trimmed, colorKey)
                if (reminders != null) calendars.setReminderDefaults(created, reminders)
            } else {
                val current = calendars.first().firstOrNull { it.id == id } ?: return@launch
                calendars.update(current.copy(name = trimmed, colorKey = colorKey))
                if (reminders != current.reminderDefaults) calendars.setReminderDefaults(id, reminders)
            }
        }
    }

    /** Deletes a calendar with its entries; undo brings everything back. */
    fun deleteCalendar(id: Long) {
        viewModelScope.launch {
            val snapshot = calendars.delete(id) ?: return@launch
            snackbar.show(UiText.Res(R.string.calendar_deleted, listOf(snapshot.calendar.name)), UiText.Res(R.string.action_undo)) {
                calendars.restore(snapshot)
            }
        }
    }

    fun loadSampleData() = runOnce {
        samples.load()
        snackbar.show(UiText.Res(R.string.sample_loaded), UiText.Res(R.string.action_undo)) { samples.clear() }
    }

    fun clearSampleData() = runOnce {
        samples.clear()
        snackbar.show(UiText.Res(R.string.sample_cleared))
    }

    /** Confirmed in a dialog beforehand; cannot be undone. */
    fun deleteAllData(defaultCalendarName: String) = runOnce {
        reset.deleteAll(defaultCalendarName)
        snackbar.show(UiText.Res(R.string.data_deleted))
    }

    fun showMessage(@StringRes message: Int) = snackbar.show(UiText.Res(message))

    fun onSnackbarAction(message: SnackbarMessage) = snackbar.performAction(message)

    fun onSnackbarTimeout(message: SnackbarMessage) = snackbar.dismiss(message.id)

    private fun runOnce(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }
}
