package com.celander.app.event

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.celander.app.R
import com.celander.app.core.ical.IcsMapper
import com.celander.app.core.ical.IcsWriter
import com.celander.app.core.model.EventException
import com.celander.app.core.model.EventOccurrence
import com.celander.app.core.model.EventTiming
import com.celander.app.core.ui.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

/** Shows [location] in a maps app as a search, so free text like "John's house" works too. False without a maps app. */
fun openInMaps(context: Context, location: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, ("geo:0,0?q=" + Uri.encode(location)).toUri()))
    true
} catch (_: ActivityNotFoundException) {
    false
}

/** Shares an event as readable text or as an .ics file other calendars can import. */
object EventSharing {
    fun describe(context: Context, occurrence: EventOccurrence, formats: Formats, zone: ZoneId): String {
        val timing = occurrence.timing
        val start = timing.startDate(zone)
        val last = timing.lastDate(zone)
        val date = if (start == last) formats.capitalize(start.format(formats.dayLong))
        else context.getString(R.string.time_range, formats.capitalize(start.format(formats.dayLong)), formats.capitalize(last.format(formats.dayLong)))
        val time = when (timing) {
            is EventTiming.AllDay -> context.getString(R.string.all_day)
            is EventTiming.Timed -> context.getString(
                R.string.time_range,
                timing.start.atZone(zone).format(formats.time),
                timing.end.atZone(zone).format(formats.time),
            )
        }
        return buildString {
            appendLine(occurrence.title.ifBlank { context.getString(R.string.untitled) })
            appendLine("$date, $time")
            if (occurrence.location.isNotBlank()) appendLine(occurrence.location)
            if (occurrence.description.isNotBlank()) {
                appendLine()
                append(occurrence.description)
            }
        }.trim()
    }

    fun shareText(context: Context, occurrence: EventOccurrence, formats: Formats, zone: ZoneId) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, occurrence.title)
            .putExtra(Intent.EXTRA_TEXT, describe(context, occurrence, formats, zone))
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun shareIcs(context: Context, occurrence: EventOccurrence, exceptions: List<EventException>) {
        val file = withContext(Dispatchers.IO) {
            val ics = IcsWriter.calendar(IcsMapper.toIcal(occurrence.event, exceptions))
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val name = occurrence.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(40).ifBlank { "event" }
            File(dir, "$name.ics").apply { writeText(ics) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/calendar")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
