package com.celander.app.settings

import com.celander.app.calendar.device.CalendarSync
import com.celander.app.calendar.device.DeviceCalendarSource
import com.celander.app.calendar.device.DeviceCalendarStatus
import com.celander.app.calendar.device.ProviderCalendarSource
import com.celander.app.task.google.GoogleTasksAccounts
import com.celander.app.task.google.GoogleTasksState
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart

/**
 * A Google account Celander knows: one with calendars on this phone, one connected to Google Tasks,
 * or both. [calendarCount] counts its calendars Celander shows (none while the phone's calendars are
 * off in Celander); [calendarSync] is null until the phone's sync settings were read; [tasks] is null
 * while Google Tasks is not connected for it.
 */
data class GoogleAccount(
    val email: String,
    val calendarCount: Int,
    val calendarSync: CalendarSync?,
    val tasks: GoogleTasksState?,
) {
    /** Something only the user can fix: a Tasks problem, or phone-wide auto-sync off while its calendars are shown. */
    val needsAttention: Boolean
        get() = tasks?.status?.isProblem == true || (calendarCount > 0 && calendarSync == CalendarSync.PHONE_OFF)
}

/** The Google accounts as the settings overview summarises them. */
data class GoogleOverview(val accounts: List<GoogleAccount>, val calendarStatus: DeviceCalendarStatus) {
    /** The phone's calendars are switched on, but the calendar permission is missing. */
    val noCalendarAccess: Boolean get() = calendarStatus == DeviceCalendarStatus.NO_ACCESS

    /** The first account that needs the user. */
    val troubled: GoogleAccount? get() = accounts.firstOrNull { it.needsAttention }

    val attention: Boolean get() = noCalendarAccess || troubled != null
}

/** The Google Tasks accounts for the calendar list; [email] only while there is exactly one. */
data class GoogleTasksSummary(val emails: List<String>) {
    val email: String? get() = emails.singleOrNull()
}

/** Unites the phone's Google calendar accounts with the Google Tasks accounts. */
@OptIn(ExperimentalCoroutinesApi::class)
class GoogleAccounts @Inject constructor(
    private val device: DeviceCalendarSource,
    private val tasks: GoogleTasksAccounts,
) {
    /**
     * Phone accounts first, then Tasks-only ones, then [extra] (accounts just added whose calendars
     * have not arrived yet), each once.
     */
    fun accounts(extra: Flow<Set<String>> = flowOf(emptySet())): Flow<List<GoogleAccount>> {
        val names = combine(device.googleAccounts, tasks.state, extra) { phone, connected, added ->
            (phone.map { it.name } + connected.map { it.email } + added).distinct()
        }.distinctUntilChanged()
        val sync = names.flatMapLatest { list -> device.calendarSync(list.toSet()).onStart { emit(emptyMap()) } }
        return combine(names, sync, device.calendars, tasks.state) { list, syncs, calendars, connected ->
            list.map { email ->
                GoogleAccount(
                    email = email,
                    calendarCount = calendars.count { it.accountType == ProviderCalendarSource.GOOGLE_ACCOUNT_TYPE && it.account == email },
                    calendarSync = syncs[email],
                    tasks = connected.firstOrNull { it.email == email },
                )
            }
        }.distinctUntilChanged()
    }
}
