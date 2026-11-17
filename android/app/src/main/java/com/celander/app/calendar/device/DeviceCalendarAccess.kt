package com.celander.app.calendar.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The calendar permissions Celander holds. */
data class CalendarPermissions(val read: Boolean, val write: Boolean)

/**
 * Current calendar permissions. Granting them in the system settings does not restart the app, so
 * [refresh] runs whenever an activity starts and after the permission dialog.
 */
@Singleton
class DeviceCalendarAccess @Inject constructor(@ApplicationContext private val context: Context) {
    private val _permissions = MutableStateFlow(read())
    val permissions: StateFlow<CalendarPermissions> = _permissions.asStateFlow()

    fun refresh() {
        _permissions.value = read()
    }

    private fun read() = CalendarPermissions(
        read = granted(Manifest.permission.READ_CALENDAR),
        write = granted(Manifest.permission.WRITE_CALENDAR),
    )

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }
}

/**
 * Asks for calendar access where the system still allows asking, otherwise opens the app's system
 * settings page. [onResult] gets whether reading is now allowed.
 */
@Stable
class CalendarAccessRequest internal constructor(
    private val context: Context,
    private val shouldShowRationale: () -> Boolean,
    private val request: () -> Unit,
) {
    // Read on first use, not while the screen composes: the first read touches the disk.
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun fix() {
        if (!prefs.getBoolean(KEY_ASKED, false) || shouldShowRationale()) {
            prefs.edit { putBoolean(KEY_ASKED, true) }
            request()
        } else {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    private companion object {
        const val PREFS = "calendar_access"
        const val KEY_ASKED = "asked"
    }
}

@Composable
fun rememberCalendarAccessRequest(access: DeviceCalendarAccess, onResult: (Boolean) -> Unit): CalendarAccessRequest {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val currentOnResult = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        access.refresh()
        currentOnResult.value(result[Manifest.permission.READ_CALENDAR] == true)
    }
    return remember(context) {
        CalendarAccessRequest(
            context = context.applicationContext,
            shouldShowRationale = { activity?.shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR) == true },
            request = { launcher.launch(DeviceCalendarAccess.PERMISSIONS) },
        )
    }
}
