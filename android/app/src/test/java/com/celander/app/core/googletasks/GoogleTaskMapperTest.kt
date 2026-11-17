package com.celander.app.core.googletasks

import com.celander.app.core.model.Task
import java.time.LocalDate
import java.time.LocalTime
import java.util.TimeZone
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleTaskMapperTest {
    private val day = LocalDate.of(2026, 10, 23)
    private val base = TaskFields("Milk", "2 litres", day, done = false, completedAt = null)

    private fun withZone(id: String, block: () -> Unit) {
        val saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(id))
        try {
            block()
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test
    fun dueDateKeepsItsDayInEveryZone() {
        for (zone in listOf("America/Los_Angeles", "Etc/GMT+8", "Pacific/Kiritimati", "Etc/GMT-14", "UTC")) {
            withZone(zone) {
                val fields = GoogleTaskMapper.fields(GTask(id = "a", due = "2026-10-23T00:00:00.000Z"))
                assertEquals(zone, day, fields.dueDay)
            }
        }
    }

    @Test
    fun dueDateIsWrittenAsUtcMidnight() {
        assertEquals("2026-10-23T00:00:00.000Z", GoogleTaskMapper.formatDue(day))
        assertEquals("2026-01-05T00:00:00.000Z", GoogleTaskMapper.formatDue(LocalDate.of(2026, 1, 5)))
    }

    @Test
    fun unreadableDueIsNoDate() {
        assertNull(GoogleTaskMapper.fields(GTask(id = "a", due = "soon")).dueDay)
        assertNull(GoogleTaskMapper.fields(GTask(id = "a")).dueDay)
    }

    @Test
    fun missingTextBecomesEmpty() {
        val fields = GoogleTaskMapper.fields(GTask(id = "a"))
        assertEquals("", fields.title)
        assertEquals("", fields.note)
        assertFalse(fields.done)
        assertNull(fields.completedAt)
    }

    @Test
    fun completionTimeFallsBackToUpdated() {
        val completed = GTask(
            id = "a",
            status = "completed",
            completed = "2026-10-20T08:30:00.000Z",
            updated = "2026-10-21T09:00:00.000Z",
        )
        assertEquals(GoogleTaskMapper.parseTime("2026-10-20T08:30:00.000Z"), GoogleTaskMapper.fields(completed).completedAt)
        val fallback = GoogleTaskMapper.fields(completed.copy(completed = null))
        assertTrue(fallback.done)
        assertEquals(GoogleTaskMapper.parseTime("2026-10-21T09:00:00.000Z"), fallback.completedAt)
    }

    @Test
    fun openTasksHaveNoCompletionTime() {
        val fields = GoogleTaskMapper.fields(GTask(id = "a", status = "needsAction", completed = "2026-10-20T08:30:00.000Z"))
        assertFalse(fields.done)
        assertNull(fields.completedAt)
    }

    @Test
    fun rfc3339TimesParseWithOffsetsAndFractions() {
        val expected = 1_792_398_600_000L // 2026-10-19T08:30:00Z
        assertEquals(expected, GoogleTaskMapper.parseTime("2026-10-19T08:30:00Z"))
        assertEquals(expected, GoogleTaskMapper.parseTime("2026-10-19T08:30:00.000Z"))
        assertEquals(expected, GoogleTaskMapper.parseTime("2026-10-19T10:30:00+02:00"))
        assertEquals(expected + 123, GoogleTaskMapper.parseTime("2026-10-19T08:30:00.123Z"))
        assertNull(GoogleTaskMapper.parseTime("yesterday"))
        assertEquals("2026-10-19T08:30:00.123Z", GoogleTaskMapper.formatTime(expected + 123))
        assertEquals(expected, GoogleTaskMapper.parseTime(GoogleTaskMapper.formatTime(expected)))
    }

    @Test
    fun localTaskFieldsIgnoreTimeAndStaleCompletion() {
        val task = Task(
            calendarId = 1,
            title = "Call",
            note = "n",
            dueDate = day,
            dueTime = LocalTime.of(9, 0),
            reminders = listOf(10),
            completedAt = 5,
        )
        assertEquals(TaskFields("Call", "n", day, done = false, completedAt = null), GoogleTaskMapper.fields(task))
        assertEquals(5L, GoogleTaskMapper.fields(task.copy(done = true)).completedAt)
    }

    @Test
    fun insertBodyCarriesEveryField() {
        val body = GoogleTaskMapper.insertBody(base)
        assertEquals(
            """{"title":"Milk","notes":"2 litres","due":"2026-10-23T00:00:00.000Z","status":"needsAction","completed":null}""",
            body.toString(),
        )
        val done = GoogleTaskMapper.insertBody(base.copy(dueDay = null, done = true, completedAt = 1_792_398_600_000L))
        assertFalse("due" in done)
        assertEquals("completed", done.string("status"))
        assertEquals("2026-10-19T08:30:00.000Z", done.string("completed"))
    }

    @Test
    fun insertBodyTruncatesToGoogleLimits() {
        val body = GoogleTaskMapper.insertBody(base.copy(title = "t".repeat(2000), note = "n".repeat(9000)))
        assertEquals(GoogleTaskMapper.MAX_TITLE, body.string("title").length)
        assertEquals(GoogleTaskMapper.MAX_NOTES, body.string("notes").length)
    }

    @Test
    fun patchContainsOnlyTheDiff() {
        assertEquals(JsonObject(emptyMap()), GoogleTaskMapper.patchBody(base, base))
        assertEquals("""{"title":"Oat milk"}""", GoogleTaskMapper.patchBody(base, base.copy(title = "Oat milk")).toString())
        assertEquals("""{"notes":""}""", GoogleTaskMapper.patchBody(base, base.copy(note = "")).toString())
        assertEquals(
            """{"due":"2026-10-24T00:00:00.000Z"}""",
            GoogleTaskMapper.patchBody(base, base.copy(dueDay = day.plusDays(1))).toString(),
        )
        val both = GoogleTaskMapper.patchBody(base, base.copy(title = "Oat milk", note = "1 litre"))
        assertEquals(setOf("title", "notes"), both.keys)
    }

    @Test
    fun clearingTheDateSendsExplicitNull() {
        val body = GoogleTaskMapper.patchBody(base, base.copy(dueDay = null))
        assertEquals(setOf("due"), body.keys)
        assertEquals(JsonNull, body["due"])
        assertEquals("""{"due":null}""", body.toString())
    }

    @Test
    fun completingSendsStatusAndTime() {
        val body = GoogleTaskMapper.patchBody(base, base.copy(done = true, completedAt = 1_792_398_600_000L))
        assertEquals("""{"status":"completed","completed":"2026-10-19T08:30:00.000Z"}""", body.toString())
    }

    @Test
    fun reopeningSendsNeedsActionAndNullCompletion() {
        val doneBase = base.copy(done = true, completedAt = 1_792_398_600_000L)
        val body = GoogleTaskMapper.patchBody(doneBase, doneBase.copy(done = false, completedAt = null))
        assertEquals("""{"status":"needsAction","completed":null}""", body.toString())
    }

    @Test
    fun completionTimeOfOpenTasksIsNoChange() {
        assertEquals(JsonObject(emptyMap()), GoogleTaskMapper.patchBody(base, base.copy(completedAt = 99)))
        val doneBase = base.copy(done = true, completedAt = 1_000L)
        assertEquals(setOf("status", "completed"), GoogleTaskMapper.patchBody(doneBase, doneBase.copy(completedAt = 2_000L)).keys)
    }

    @Test
    fun responsesDecodeIgnoringUnknownKeys() {
        val task = GTaskJson.task(
            """
            {"kind":"tasks#task","id":"t1","etag":"\"e1\"","title":"Milk","updated":"2026-10-19T08:30:00.000Z",
             "selfLink":"https://x","position":"0001","status":"completed","due":"2026-10-23T00:00:00.000Z",
             "completed":"2026-10-19T08:30:00.000Z","hidden":true,"links":[],"webViewLink":"https://y"}
            """.trimIndent(),
        )
        assertEquals("t1", task.id)
        assertEquals("\"e1\"", task.etag)
        assertTrue(task.hidden)
        assertFalse(task.deleted)
        assertNull(task.notes)
        assertEquals(TaskFields("Milk", "", day, done = true, completedAt = 1_792_398_600_000L), GoogleTaskMapper.fields(task))

        val page = GTaskJson.taskPage(
            """{"kind":"tasks#tasks","etag":"x","nextPageToken":"p2","items":[{"id":"a","deleted":true},{"id":"b"}]}""",
        )
        assertEquals("p2", page.nextPageToken)
        assertEquals(listOf("a", "b"), page.items.map { it.id })
        assertTrue(page.items[0].deleted)
        assertTrue(GTaskJson.taskPage("""{"kind":"tasks#tasks"}""").items.isEmpty())

        val lists = GTaskJson.listPage(
            """{"kind":"tasks#taskLists","items":[{"kind":"tasks#taskList","id":"L1","title":"My Tasks","updated":"2026-10-19T08:30:00.000Z"}]}""",
        )
        assertNull(lists.nextPageToken)
        assertEquals(GTaskList(id = "L1", title = "My Tasks", updated = "2026-10-19T08:30:00.000Z"), lists.items.single())
        assertEquals("L2", GTaskJson.list("""{"id":"L2","title":"Work","selfLink":"z"}""").id)
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
