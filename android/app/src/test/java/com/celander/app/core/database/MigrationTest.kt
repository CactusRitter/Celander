package com.celander.app.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Version 1 databases keep every row and gain the Google tables empty. The v1 database is built from
 * the exported schema; opening it with Room runs the auto-migration and checks the v2 identity.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val name = "migration-test.db"
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun createVersion(version: Int) {
        val schema = Json.parseToJsonElement(File("schemas/${CelanderDatabase::class.qualifiedName}/$version.json").readText()).jsonObject["database"]!!.jsonObject
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs(); delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        for (entity in schema["entities"]!!.jsonArray) {
            val e = entity.jsonObject
            val table = e["tableName"]!!.jsonPrimitive.content
            db.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            e["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            e["contentSyncTriggers"]?.jsonArray?.forEach { db.execSQL(it.jsonPrimitive.content) }
        }
        schema["views"]?.jsonArray?.forEach { db.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content) }
        schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = version
        db.close()
    }

    @Test
    fun version1MigratesWithoutLosingData() {
        createVersion(1)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO calendars (id, name, colorKey, visible, sortOrder, isSample, createdAt) VALUES (1, 'Personal', 'indigo', 1, 0, 0, 0)")
            db.execSQL(
                "INSERT INTO events (id, uid, calendarId, title, location, description, colorKey, allDay, startUtc, endUtc, startDay, endDay, " +
                    "timeZone, rrule, reminders, isSample, createdAt, updatedAt) VALUES (1, 'u1', 1, 'Dentist', '', '', NULL, 0, 0, 3600000, NULL, NULL, " +
                    "'Europe/Berlin', NULL, '10', 0, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO tasks (id, calendarId, title, note, dueDay, dueMinute, reminders, done, completedAt, isSample, createdAt, updatedAt) " +
                    "VALUES (1, 1, 'Book flights', '', NULL, NULL, '', 0, NULL, 0, 0, 0)",
            )
        }
        val db = Room.databaseBuilder(context, CelanderDatabase::class.java, name).build()
        try {
            runBlocking {
                val calendar = db.calendarDao().getAll().single()
                assertEquals("LOCAL", calendar.source)
                assertEquals("Dentist", db.eventDao().getAll().single().title)
                assertEquals("Book flights", db.taskDao().getAll().single().title)
                assertEquals(1, db.eventDao().search("dent*").size)
                assertEquals(1, db.taskDao().search("flight*").size)
                assertTrue(db.googleTasksDao().lists().isEmpty())
                assertTrue(db.googleTasksDao().pendingDeletes().isEmpty())
                assertEquals(1, db.calendarDao().countLocal())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun version2CalendarsKeepUsingTheDefaultReminders() {
        createVersion(2)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO calendars (id, name, colorKey, visible, sortOrder, isSample, createdAt, source) VALUES (1, 'Personal', 'indigo', 1, 0, 0, 0, 'LOCAL')")
            db.execSQL("INSERT INTO device_calendar_prefs (stableKey, visible, colorKey) VALUES ('com.google|me|work', 0, 'teal')")
        }
        val db = Room.databaseBuilder(context, CelanderDatabase::class.java, name).build()
        try {
            runBlocking {
                val calendar = db.calendarDao().getAll().single()
                assertEquals("Personal", calendar.name)
                assertNull(calendar.toModel().reminderDefaults)
                val pref = db.devicePrefsDao().get("com.google|me|work")!!
                assertEquals("teal", pref.colorKey)
                assertNull(pref.reminderDefaults)
                // The new columns store and read back, empty lists apart from "not set".
                db.calendarDao().update(calendar.copy(eventReminders = listOf(30, 5), allDayReminders = emptyList(), taskReminders = listOf(0)))
                val stored = db.calendarDao().getById(1)!!
                assertEquals(listOf(30, 5), stored.eventReminders)
                assertEquals(emptyList<Int>(), stored.allDayReminders)
                db.devicePrefsDao().upsert(pref.copy(eventReminders = listOf(15), allDayReminders = listOf(1440), taskReminders = emptyList()))
                assertEquals(listOf(15), db.devicePrefsDao().get("com.google|me|work")!!.reminderDefaults?.event)
            }
        } finally {
            db.close()
        }
    }
}
