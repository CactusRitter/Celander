package com.celander.app.calendar.device

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tiny in-memory calendar provider: calendars, events (with exceptions) and reminders as rows of
 * column maps. Instances are the events themselves (no expansion), which is enough to test what
 * Celander asks for and how it maps the answers. Every query and write is recorded.
 */
class FakeCalendarProvider : ContentProvider() {
    val calendars = mutableListOf<MutableMap<String, Any?>>()
    val events = mutableListOf<MutableMap<String, Any?>>()
    val reminders = mutableListOf<MutableMap<String, Any?>>()
    val queries = mutableListOf<Pair<Uri, String?>>()
    val writes = mutableListOf<String>()

    /** The next this many queries throw, like a provider that crashed or is being updated. */
    val failingQueries = AtomicInteger()
    private var nextId = 1000L

    override fun onCreate() = true

    override fun getType(uri: Uri): String? = null

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (failingQueries.getAndUpdate { maxOf(it - 1, 0) } > 0) throw IllegalStateException("calendar provider unavailable")
        queries += uri to selection
        val segments = uri.pathSegments
        val rows: List<Map<String, Any?>> = when (segments.firstOrNull()) {
            "calendars" -> if (segments.size > 1) calendars.filter { it["_id"] == segments[1].toLong() } else calendars.filter { it["sync_events"] == 1 }
            "instances" -> events.filter { e -> e["deleted"] != 1 && calendarIn(selection, e["calendar_id"] as Long) }.map { instance(it) }
            "events" -> when {
                segments.size > 1 -> events.filter { it["_id"] == segments[1].toLong() }.map(::withCalendar)
                selection?.contains("original_id = ?") == true -> events.filter { it["original_id"] == selectionArgs!![0].toLong() && it["deleted"] != 1 }
                else -> events.map(::withCalendar)
            }
            "reminders" -> reminders.filter { it["event_id"] == selectionArgs?.firstOrNull()?.toLong() }
            else -> emptyList()
        }
        val columns = projection ?: rows.firstOrNull()?.keys?.toTypedArray() ?: emptyArray()
        return MatrixCursor(columns).apply { rows.forEach { row -> addRow(columns.map { row[it] }) } }
    }

    private fun calendarIn(selection: String?, id: Long): Boolean {
        val list = selection?.substringAfter("calendar_id IN (", "")?.substringBefore(")") ?: return true
        return list.split(',').any { it.trim() == id.toString() }
    }

    private fun withCalendar(event: Map<String, Any?>): Map<String, Any?> {
        val calendar = calendars.first { it["_id"] == event["calendar_id"] }
        return event + mapOf(
            "calendar_access_level" to calendar["calendar_access_level"],
            "ownerAccount" to calendar["ownerAccount"],
            "calendar_color" to calendar["calendar_color"],
        )
    }

    private fun instance(event: Map<String, Any?>): Map<String, Any?> = withCalendar(event) + mapOf(
        "event_id" to event["_id"],
        "begin" to event["dtstart"],
        "end" to (event["dtend"] ?: event["dtstart"]),
        "displayColor" to (event["eventColor"] ?: calendars.first { it["_id"] == event["calendar_id"] }["calendar_color"]),
    )

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        writes += "insert $uri ${values.toMap().toSortedMap()}"
        val id = nextId++
        val row = values.toMap().toMutableMap().apply { put("_id", id) }
        when (uri.pathSegments.first()) {
            "reminders" -> reminders += row
            "exception" -> {
                // Like the real provider: the master decides these.
                require("originalAllDay" !in row) { "Exceptions can't overwrite originalAllDay" }
                events += row.apply { put("original_id", uri.pathSegments[1].toLong()) }
            }
            else -> events += row
        }
        return ContentUris.withAppendedId(uri, id)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        writes += "update $uri ${values.toMap().toSortedMap()}"
        val row = events.firstOrNull { it["_id"] == uri.lastPathSegment?.toLongOrNull() } ?: return 0
        row.putAll(values.toMap())
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        writes += "delete $uri ${selection.orEmpty()}"
        return when (uri.pathSegments.first()) {
            "reminders" -> reminders.count { it["event_id"] == selectionArgs?.first()?.toLong() }.also {
                reminders.removeAll { r -> r["event_id"] == selectionArgs?.first()?.toLong() }
            }
            else -> events.firstOrNull { it["_id"] == uri.lastPathSegment?.toLongOrNull() }?.let { it["deleted"] = 1; 1 } ?: 0
        }
    }

    private fun ContentValues?.toMap(): Map<String, Any?> = this?.keySet()?.associateWith { get(it) }.orEmpty()

    companion object {
        val AUTHORITY: String = CalendarContract.AUTHORITY
    }
}
