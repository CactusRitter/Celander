package com.celander.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celander.app.calendar.EditorRequest
import com.celander.app.calendar.HomeDestination
import com.celander.app.calendar.HomeSheet
import com.celander.app.calendar.KeyboardShortcuts
import com.celander.app.calendar.LaunchRequests
import com.celander.app.calendar.LocalKeyboardShortcuts
import com.celander.app.calendar.ViewRequest
import com.ce.kit.ui.theme.CeTheme
import com.celander.app.notifications.ReminderKind
import com.celander.app.notifications.ReminderNotifier
import com.celander.app.notifications.reminder
import com.celander.app.settings.SettingsRepository
import com.celander.app.ui.CalendarEnvironment
import com.ce.kit.ui.theme.isDarkNow
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.runBlocking

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var launchRequests: LaunchRequests

    private val keyboardShortcuts = KeyboardShortcuts()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only a fresh start handles the intent; after recreation the opened sheet is restored anyway.
        // Reopened from recents, Android hands over the original intent again: it was handled then.
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) handle(intent)
        // A few-millisecond read so the first frame already uses the chosen theme (no flash).
        val initialSettings = runBlocking { settingsRepository.current() }
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialSettings)
            val dark = settings.isDarkNow()
            SystemBars(dark)
            CeTheme(settings) {
              CalendarEnvironment(reduceMotion = settings.reduceMotion || systemAnimationsOff()) {
                CompositionLocalProvider(LocalKeyboardShortcuts provides keyboardShortcuts) {
                    CelanderNavHost(launchRequests)
                }
              }
            }
        }
    }

    @Composable
    private fun SystemBars(dark: Boolean) {
        DisposableEffect(dark) {
            val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            onDispose {}
        }
    }

    /**
     * Ctrl shortcuts reach the home screen before the focused view; unused ones go on as usual.
     * This overrides the public framework method; androidx marks only its own override as
     * restricted, which lint also reports for subclasses.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = keyboardShortcuts.dispatch(event) || super.dispatchKeyEvent(event)

    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data += KeyboardShortcuts.groups(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A tapped reminder or widget opens its event, task, view or a new entry. */
    private fun handle(intent: Intent) {
        when (intent.action) {
            ACTION_OPEN_REMINDER, ACTION_OPEN_ENTRY -> {
                val reminder = intent.reminder() ?: return
                // The notification's "Details" button does not dismiss it by itself, unlike a tap on it.
                if (intent.action == ACTION_OPEN_REMINDER) NotificationManagerCompat.from(this).cancel(ReminderNotifier.notificationId(reminder.key))
                launchRequests.open(
                    when (reminder.kind) {
                        ReminderKind.EVENT -> HomeSheet.EventDetail(reminder.refId, reminder.occurrenceStart)
                        ReminderKind.TASK -> HomeSheet.Editor(EditorRequest.EditTask(reminder.refId))
                    },
                )
            }
            ACTION_OPEN_VIEW -> {
                val destination = HomeDestination.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_DESTINATION) } ?: return
                launchRequests.show(ViewRequest(destination, intent.epochDay()))
            }
            ACTION_NEW_ENTRY -> {
                val day = intent.epochDay()
                launchRequests.open(
                    HomeSheet.Editor(
                        if (intent.getStringExtra(EXTRA_ENTRY_KIND) == KIND_TASK) EditorRequest.NewTask(day)
                        else EditorRequest.NewEvent(day ?: LocalDate.now().toEpochDay()),
                    ),
                )
            }
        }
    }

    private fun Intent.epochDay(): Long? = if (hasExtra(EXTRA_EPOCH_DAY)) getLongExtra(EXTRA_EPOCH_DAY, 0) else null

    private fun systemAnimationsOff(): Boolean =
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    companion object {
        const val ACTION_OPEN_REMINDER = "com.celander.app.action.OPEN_REMINDER"
        const val ACTION_OPEN_ENTRY = "com.celander.app.action.OPEN_ENTRY"
        const val ACTION_OPEN_VIEW = "com.celander.app.action.OPEN_VIEW"
        const val ACTION_NEW_ENTRY = "com.celander.app.action.NEW_ENTRY"
        const val EXTRA_DESTINATION = "destination"
        const val EXTRA_EPOCH_DAY = "epoch_day"
        const val EXTRA_ENTRY_KIND = "entry_kind"
        const val KIND_EVENT = "event"
        const val KIND_TASK = "task"
    }
}
