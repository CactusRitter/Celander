package com.celander.app.account

import android.content.Context
import com.celander.app.R
import com.celander.app.account.google.BirthdaySource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The "Birthdays from contacts" switch of each Google account, and why it went off by itself (the People API's refusal), kept in
 * the app's own preferences: no tokens or contact data here. The switch is off until the user grants the contacts permission.
 */
@Singleton
class BirthdayPrefs @Inject constructor(@ApplicationContext private val context: Context) : BirthdaySource {
    private val prefs = context.getSharedPreferences("birthdays", Context.MODE_PRIVATE)

    private val enabledIds = MutableStateFlow(prefs.all.keys.filter { it.startsWith(ON) && prefs.getBoolean(it, false) }.mapNotNull { it.removePrefix(ON).toLongOrNull() }.toSet())
    private val failures = MutableStateFlow(prefs.all.keys.filter { it.startsWith(FAILED) }.mapNotNull { key -> key.removePrefix(FAILED).toLongOrNull()?.let { it to prefs.getInt(key, 0) } }.toMap())

    /** The accounts with the switch on. */
    val enabledAccounts: StateFlow<Set<Long>> = enabledIds

    /** The HTTP code with which the People API refused, by account; gone when the switch is turned on again. */
    val failureCodes: StateFlow<Map<Long, Int>> = failures

    override fun enabled(accountId: Long): Boolean = accountId in enabledIds.value

    override fun calendarName(): String = context.getString(R.string.birthdays_calendar_name)

    override fun purged(accountId: Long, calendarId: String): Boolean = prefs.getBoolean(PURGED + accountId + "." + calendarId, false)

    override fun markPurged(accountId: Long, calendarId: String) {
        prefs.edit().putBoolean(PURGED + accountId + "." + calendarId, true).apply()
    }

    /** Every purge mark of the account goes, so that switching on starts a clean full read. */
    private fun clearPurged(accountId: Long) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(PURGED + accountId + ".") }.forEach { editor.remove(it) }
        editor.apply()
    }

    fun enable(accountId: Long) {
        clearPurged(accountId)
        prefs.edit().putBoolean(ON + accountId, true).remove(FAILED + accountId).apply()
        enabledIds.value = enabledIds.value + accountId
        failures.value = failures.value - accountId
    }

    fun disable(accountId: Long) {
        prefs.edit().remove(ON + accountId).apply()
        enabledIds.value = enabledIds.value - accountId
    }

    override fun failed(accountId: Long, code: Int) {
        prefs.edit().remove(ON + accountId).putInt(FAILED + accountId, code).apply()
        enabledIds.value = enabledIds.value - accountId
        failures.value = failures.value + (accountId to code)
    }

    /** The account is gone: nothing is kept for it. */
    fun forget(accountId: Long) {
        clearPurged(accountId)
        prefs.edit().remove(ON + accountId).remove(FAILED + accountId).apply()
        enabledIds.value = enabledIds.value - accountId
        failures.value = failures.value - accountId
    }

    private companion object {
        const val ON = "on."
        const val FAILED = "failed."
        const val PURGED = "purged."
    }
}
