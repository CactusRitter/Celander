package com.celander.app.settings

import com.celander.app.task.google.GoogleTasksAccounts
import com.celander.app.task.google.GoogleTasksState
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * A Google account connected to Celander through Google Tasks. [tasks] is null while Google Tasks is
 * not connected for it (an account just added whose connection failed).
 */
data class GoogleAccount(
    val email: String,
    val tasks: GoogleTasksState?,
) {
    /** Something only the user can fix: a Tasks problem. */
    val needsAttention: Boolean
        get() = tasks?.status?.isProblem == true
}

/** The Google accounts as the settings overview summarises them. */
data class GoogleOverview(val accounts: List<GoogleAccount>) {
    /** The first account that needs the user. */
    val troubled: GoogleAccount? get() = accounts.firstOrNull { it.needsAttention }

    val attention: Boolean get() = troubled != null
}

/** The Google Tasks accounts for the calendar list; [email] only while there is exactly one. */
data class GoogleTasksSummary(val emails: List<String>) {
    val email: String? get() = emails.singleOrNull()
}

/** The Google accounts connected to Google Tasks. */
class GoogleAccounts @Inject constructor(
    private val tasks: GoogleTasksAccounts,
) {
    /** The connected accounts, then [extra] (accounts just added whose connection is not there yet), each once. */
    fun accounts(extra: Flow<Set<String>> = flowOf(emptySet())): Flow<List<GoogleAccount>> =
        combine(tasks.state, extra) { connected, added ->
            (connected.map { it.email } + added).distinct().map { email ->
                GoogleAccount(email = email, tasks = connected.firstOrNull { it.email == email })
            }
        }.distinctUntilChanged()
}
