package com.celander.app.calendar

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import com.celander.app.core.di.ApplicationScope
import com.celander.app.core.di.IoDispatcher
import com.celander.app.core.device.DeviceMapping
import com.celander.app.core.model.EventTiming
import com.celander.app.core.model.SourceIds
import com.celander.app.core.time.TimeSource
import com.celander.app.event.EventRepository
import com.celander.app.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opens an event another app asked to show or edit: its details, or the editor if it may be edited.
 * An event Celander cannot find opens the start view on today instead.
 */
@Singleton
class EventLinks @Inject constructor(
    @ApplicationContext private val context: Context,
    private val events: EventRepository,
    private val settings: SettingsRepository,
    private val launchRequests: LaunchRequests,
    private val time: TimeSource,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    fun open(link: EventLink) {
        scope.launch {
            val sheet = sheetFor(link)
            if (sheet != null) {
                launchRequests.open(sheet)
            } else {
                val startView = settings.current().startView
                val destination = HomeDestination.entries.firstOrNull { it.calendarView == startView } ?: HomeDestination.MONTH
                launchRequests.show(ViewRequest(destination, time.today().toEpochDay()))
            }
        }
    }

    private suspend fun sheetFor(link: EventLink): HomeSheet? {
        // A changed occurrence of a series is a row of its own; Celander shows it within its series.
        val original = originalOf(link.providerId)
        val eventId = SourceIds.device(original?.masterId ?: link.providerId)
        val loaded = events.getWithExceptions(eventId) ?: return null
        val key = original?.let { DeviceMapping.occurrenceKey(it.instanceTime, it.allDay ?: (loaded.event.timing is EventTiming.AllDay)) }
            ?: CalendarIntents.occurrenceKey(loaded.event, loaded.exceptions, link.beginMillis, time.now(), time.zone())
        return if (link.edit && loaded.access.canEdit) {
            HomeSheet.Editor(EditorRequest.EditEvent(eventId, key))
        } else {
            HomeSheet.EventDetail(eventId, key)
        }
    }

    private data class Original(val masterId: Long, val instanceTime: Long, val allDay: Boolean?)

    /** The series and original start of a changed occurrence's row, or null for any other row. */
    private suspend fun originalOf(providerId: Long): Original? = withContext(io) {
        try {
            context.contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, providerId),
                arrayOf(
                    CalendarContract.Events.ORIGINAL_ID,
                    CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
                    CalendarContract.Events.ORIGINAL_ALL_DAY,
                ),
                null,
                null,
                null,
            )?.use { c ->
                if (!c.moveToFirst() || c.isNull(0) || c.isNull(1)) {
                    null
                } else {
                    val masterId = c.getString(0).toLongOrNull()
                    masterId?.takeIf { it in 0 until SourceIds.DEVICE_BASE }
                        ?.let { Original(it, c.getLong(1), if (c.isNull(2)) null else c.getInt(2) == 1) }
                }
            }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
