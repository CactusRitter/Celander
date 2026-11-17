package com.celander.app.core.ui

import com.celander.app.core.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class SnackbarMessage(
    val id: Long,
    val text: UiText,
    val actionLabel: UiText? = null,
    val action: (suspend () -> Unit)? = null,
    val durationMillis: Long = if (action != null) ACTION_DURATION else INFO_DURATION,
    /** Runs once when the message goes away without its action: timed out or replaced. */
    val onExpire: (suspend () -> Unit)? = null,
) {
    companion object {
        const val INFO_DURATION = 4_000L

        /** Messages with "Undo" stay longer so the action can still be reached comfortably. */
        const val ACTION_DURATION = 8_000L
    }
}

/**
 * App-wide snackbar queue holding at most one message. Actions (typically "Undo") and expiries run
 * in the application scope so they complete even if the screen that showed them is gone.
 */
@Singleton
class SnackbarManager @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _current = MutableStateFlow<SnackbarMessage?>(null)
    val current: StateFlow<SnackbarMessage?> = _current.asStateFlow()
    private var nextId = 0L
    private val settled = mutableSetOf<Long>()

    /** [onExpire] comes before [action], so a trailing lambda stays the action. */
    fun show(
        text: UiText,
        actionLabel: UiText? = null,
        onExpire: (suspend () -> Unit)? = null,
        action: (suspend () -> Unit)? = null,
    ) {
        val message = SnackbarMessage(++nextId, text, actionLabel, action, onExpire = onExpire)
        val replaced = synchronized(this) { _current.value.also { _current.value = message } }
        replaced?.let(::expire)
        if (onExpire != null) {
            // Without a screen to time it out (app in the background), the message still expires;
            // later than the longest accessibility timeout.
            scope.launch {
                delay(FALLBACK_EXPIRY_MILLIS)
                dismiss(message.id)
            }
        }
    }

    /** "Undo" and similar: the message goes away without expiring. */
    fun performAction(message: SnackbarMessage) {
        val settledNow = synchronized(this) {
            if (_current.value?.id == message.id) _current.value = null
            settled.add(message.id)
        }
        val action = message.action ?: return
        if (settledNow) scope.launch { action() }
    }

    /** The message timed out. */
    fun dismiss(id: Long) {
        val message = synchronized(this) { _current.value?.takeIf { it.id == id }?.also { _current.value = null } }
        message?.let(::expire)
    }

    private fun expire(message: SnackbarMessage) {
        val first = synchronized(this) { settled.add(message.id) }
        val onExpire = message.onExpire ?: return
        if (first) scope.launch { onExpire() }
    }

    private companion object {
        const val FALLBACK_EXPIRY_MILLIS = 150_000L
    }
}
