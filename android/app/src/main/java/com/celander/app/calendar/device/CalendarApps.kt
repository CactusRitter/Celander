package com.celander.app.calendar.device

import android.content.Context
import android.content.Intent

/** The other calendar apps on the phone, which show the reminders of the phone's calendars. */
object CalendarApps {
    /** Name of the first calendar app other than Celander, or null if there is none. */
    fun otherAppLabel(context: Context): String? {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
        return pm.queryIntentActivities(intent, 0)
            .firstOrNull { it.activityInfo.packageName != context.packageName }
            ?.loadLabel(pm)?.toString()
    }
}
