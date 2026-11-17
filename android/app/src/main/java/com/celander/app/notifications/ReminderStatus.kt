package com.celander.app.notifications

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Everything outside the app that decides whether reminders appear on time. */
data class ReminderStatus(
    val permissionGranted: Boolean,
    val notificationsEnabled: Boolean,
    val eventChannelOn: Boolean,
    val taskChannelOn: Boolean,
    val exactAlarms: Boolean,
    val backgroundRestricted: Boolean,
) {
    /** True when reminders of this kind would not be shown at all. */
    fun blocks(task: Boolean): Boolean = !permissionGranted || !notificationsEnabled || !(if (task) taskChannelOn else eventChannelOn)

    /** True when reminders are shown but may come late. */
    val mayBeLate: Boolean get() = !exactAlarms || backgroundRestricted

    companion object {
        fun read(context: Context): ReminderStatus {
            val notifications = NotificationManagerCompat.from(context)
            val channels = context.getSystemService(NotificationManager::class.java)
            fun channelOn(id: String) = channels.getNotificationChannel(id)?.importance != NotificationManager.IMPORTANCE_NONE
            return ReminderStatus(
                permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                notificationsEnabled = notifications.areNotificationsEnabled(),
                eventChannelOn = channelOn(ReminderNotifier.CHANNEL_EVENTS),
                taskChannelOn = channelOn(ReminderNotifier.CHANNEL_TASKS),
                exactAlarms = ReminderScheduler.canScheduleExact(context.getSystemService(AlarmManager::class.java)),
                backgroundRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
            )
        }
    }
}

/**
 * Reads [ReminderStatus] and fixes what can be fixed: asks for the notification permission where the
 * system still allows asking, otherwise opens the matching system settings page.
 */
@Stable
class NotificationAccess internal constructor(
    private val context: Context,
    private val shouldShowRationale: () -> Boolean,
    private val request: () -> Unit,
) {
    var status by mutableStateOf(ReminderStatus.read(context))
        internal set

    // Read on first use, not while the screen composes: the first read touches the disk.
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val askedBefore get() = prefs.getBoolean(KEY_ASKED, false)

    fun refresh() {
        status = ReminderStatus.read(context)
    }

    /** Asks for the permission the first time an entry with reminders is saved; never nags after that. */
    fun askOnce() {
        if (!status.permissionGranted && !askedBefore && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ask()
    }

    /** Turns notifications on for [task] or event reminders, as far as the app may do that itself. */
    fun fix(task: Boolean) {
        when {
            !status.permissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (!askedBefore || shouldShowRationale()) -> ask()
            !status.permissionGranted || !status.notificationsEnabled -> open(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
            else -> openChannelSettings(task)
        }
    }

    fun openNotificationSettings() = open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))

    /** The system page of the [task] or event reminder channel: sound, vibration, pop-up. */
    fun openChannelSettings(task: Boolean) = open(
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, if (task) ReminderNotifier.CHANNEL_TASKS else ReminderNotifier.CHANNEL_EVENTS),
    )

    /** Exact alarms (Android 12+) or, if the app is restricted in the background, its system page. */
    fun openTimingSettings() {
        val intent = if (!status.exactAlarms && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
        }
        open(intent)
    }

    private fun ask() {
        prefs.edit { putBoolean(KEY_ASKED, true) }
        request()
    }

    private fun open(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private companion object {
        const val PREFS = "notification_access"
        const val KEY_ASKED = "asked"
    }
}

/** [NotificationAccess] whose status is re-read whenever the app comes back to the foreground. */
@Composable
fun rememberNotificationAccess(): NotificationAccess {
    val context = LocalContext.current
    val activity = LocalActivity.current
    // Returning from the system dialog resumes the activity, which re-reads the status below.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val access = remember(context) {
        NotificationAccess(
            context = context.applicationContext,
            shouldShowRationale = {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true
            },
            request = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
        )
    }
    LifecycleResumeEffect(access) {
        access.refresh()
        onPauseOrDispose {}
    }
    return access
}
