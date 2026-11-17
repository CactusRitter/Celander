package com.celander.app.calendar

import android.content.Context
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import com.celander.app.R

/** What a keyboard shortcut asks the home screen to do. */
enum class HomeShortcut(val keyCode: Int) {
    NEW_ENTRY(KeyEvent.KEYCODE_N),
    SEARCH(KeyEvent.KEYCODE_F),
    TODAY(KeyEvent.KEYCODE_T),
    PREVIOUS(KeyEvent.KEYCODE_DPAD_LEFT),
    NEXT(KeyEvent.KEYCODE_DPAD_RIGHT),
    YEAR(KeyEvent.KEYCODE_1),
    MONTH(KeyEvent.KEYCODE_2),
    DAY(KeyEvent.KEYCODE_3),
    AGENDA(KeyEvent.KEYCODE_4),
    TASKS(KeyEvent.KEYCODE_5),
    ;

    /** The view a number shortcut opens, in the order of the pill. */
    val destination: HomeDestination?
        get() = when (this) {
            YEAR -> HomeDestination.YEAR
            MONTH -> HomeDestination.MONTH
            DAY -> HomeDestination.DAY
            AGENDA -> HomeDestination.AGENDA
            TASKS -> HomeDestination.TASKS
            else -> null
        }

    companion object {
        /** Ctrl plus [keyCode], without Alt, Shift or Meta. */
        fun of(keyCode: Int, ctrl: Boolean, otherModifiers: Boolean): HomeShortcut? =
            if (!ctrl || otherModifiers) null else entries.firstOrNull { it.keyCode == keyCode }
    }
}

/**
 * Keyboard shortcuts for tablets and Chromebooks with a keyboard. The activity sees every key
 * first and hands Ctrl combinations to the home screen while it is shown; the home screen decides
 * whether one applies right now (not while a sheet is open, not Ctrl+arrows in a text field). Keys
 * it does not use go on to the focused field as usual.
 */
class KeyboardShortcuts {
    private var handler: ((HomeShortcut) -> Boolean)? = null
    private var consumedDown: Int? = null

    /** Returns true when the event was a shortcut the home screen used (or its key release). */
    fun dispatch(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == consumedDown) {
            consumedDown = null
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val otherModifiers = event.isAltPressed || event.isShiftPressed || event.isMetaPressed
        val shortcut = HomeShortcut.of(event.keyCode, event.isCtrlPressed, otherModifiers) ?: return false
        // Holding the keys repeats only the steps through periods.
        if (event.repeatCount > 0 && shortcut != HomeShortcut.PREVIOUS && shortcut != HomeShortcut.NEXT) return consumedDown == event.keyCode
        val used = handler?.invoke(shortcut) ?: false
        consumedDown = if (used) event.keyCode else null
        return used
    }

    internal fun register(onShortcut: (HomeShortcut) -> Boolean) {
        handler = onShortcut
    }

    internal fun unregister(onShortcut: (HomeShortcut) -> Boolean) {
        if (handler === onShortcut) handler = null
    }

    companion object {
        /** The shortcuts as the system lists them in its keyboard shortcut help (Meta + /). */
        fun groups(context: Context): List<KeyboardShortcutGroup> {
            fun info(label: Int, shortcut: HomeShortcut) = KeyboardShortcutInfo(context.getString(label), shortcut.keyCode, KeyEvent.META_CTRL_ON)
            return listOf(
                KeyboardShortcutGroup(
                    context.getString(R.string.app_name),
                    listOf(
                        info(R.string.action_new_entry, HomeShortcut.NEW_ENTRY),
                        info(R.string.action_search, HomeShortcut.SEARCH),
                        info(R.string.shortcut_today, HomeShortcut.TODAY),
                        info(R.string.shortcut_previous, HomeShortcut.PREVIOUS),
                        info(R.string.shortcut_next, HomeShortcut.NEXT),
                    ),
                ),
                KeyboardShortcutGroup(
                    context.getString(R.string.shortcut_group_views),
                    HomeShortcut.entries.mapNotNull { shortcut -> shortcut.destination?.let { info(it.label, shortcut) } },
                ),
            )
        }
    }
}

val LocalKeyboardShortcuts = staticCompositionLocalOf<KeyboardShortcuts?> { null }

/** Handles keyboard shortcuts while in composition; [onShortcut] returns whether it used one. */
@Composable
fun KeyboardShortcutHandler(onShortcut: (HomeShortcut) -> Boolean) {
    val shortcuts = LocalKeyboardShortcuts.current ?: return
    val current by rememberUpdatedState(onShortcut)
    DisposableEffect(shortcuts) {
        val handler: (HomeShortcut) -> Boolean = { current(it) }
        shortcuts.register(handler)
        onDispose { shortcuts.unregister(handler) }
    }
}
