package com.celander.app.task.google

import com.celander.app.core.googletasks.GoogleTaskMapper
import com.celander.app.core.googletasks.TaskFields
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The HTTP shape of [OkHttpTasksRemote]: paths, query parameters, headers, bodies and error mapping. */
class OkHttpTasksRemoteTest {
    private val server = MockWebServer()
    private lateinit var remote: OkHttpTasksRemote

    @Before
    fun setUp() {
        server.start()
        remote = OkHttpTasksRemote(OkHttpClient(), Dispatchers.IO, server.url("/tasks/v1/"))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun respond(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    private fun request(): RecordedRequest = server.takeRequest()

    @Test
    fun listsArePagedWithTheBearerToken() = runBlocking {
        respond("""{"kind":"tasks#taskLists","items":[{"id":"L1","title":"My Tasks","updated":"2026-10-23T08:00:00.000Z"}],"nextPageToken":"p2"}""")
        val page = remote.lists("abc", pageToken = "p1")
        assertEquals("L1", page.items.single().id)
        assertEquals("p2", page.nextPageToken)
        val recorded = request()
        assertEquals("GET", recorded.method)
        assertEquals("/tasks/v1/users/@me/lists?maxResults=100&pageToken=p1", recorded.path)
        assertEquals("Bearer abc", recorded.getHeader("Authorization"))

        respond("""{"id":"L1","title":"My Tasks"}""")
        assertEquals("L1", remote.defaultList("abc").id)
        assertEquals("/tasks/v1/users/@me/lists/@default", request().path)
    }

    @Test
    fun fullPullAsksForCompletedAndHiddenTasks() = runBlocking {
        respond("""{"items":[{"id":"t1","title":"Milk","status":"completed","hidden":true,"completed":"2026-10-20T10:00:00.000Z"}]}""")
        val page = remote.tasks("abc", "L 1")
        assertTrue(page.items.single().hidden)
        assertNull(page.nextPageToken)
        val url = request().requestUrl!!
        assertEquals(listOf("tasks", "v1", "lists", "L 1", "tasks"), url.pathSegments)
        assertEquals("true", url.queryParameter("showCompleted"))
        assertEquals("true", url.queryParameter("showHidden"))
        assertEquals("100", url.queryParameter("maxResults"))
        assertNull(url.queryParameter("showDeleted"))
        assertNull(url.queryParameter("updatedMin"))
    }

    @Test
    fun incrementalPullAsksForDeletedTasksSinceTheWatermark() = runBlocking {
        respond("""{"items":[{"id":"t1","deleted":true}],"nextPageToken":"n"}""")
        val since = GoogleTaskMapper.parseTime("2026-10-23T07:55:00.000Z")!!
        val page = remote.tasks("abc", "L1", updatedMin = since, pageToken = "p")
        assertTrue(page.items.single().deleted)
        val url = request().requestUrl!!
        assertEquals("true", url.queryParameter("showDeleted"))
        assertEquals("2026-10-23T07:55:00.000Z", url.queryParameter("updatedMin"))
        assertEquals("p", url.queryParameter("pageToken"))
    }

    @Test
    fun insertPatchAndMoveSendJsonWithExplicitNulls() = runBlocking {
        respond("""{"id":"t1","title":"Milk","status":"needsAction","etag":"e1"}""")
        val created = remote.insert("abc", "L1", GoogleTaskMapper.insertBody(TaskFields("Milk", "", LocalDate.of(2026, 10, 23), false, null)))
        assertEquals("e1", created.etag)
        val post = request()
        assertEquals("POST", post.method)
        assertEquals("/tasks/v1/lists/L1/tasks", post.path)
        assertTrue(post.getHeader("Content-Type")!!.startsWith("application/json"))
        val inserted = Json.parseToJsonElement(post.body.readUtf8()).jsonObject
        assertEquals("2026-10-23T00:00:00.000Z", inserted["due"].toString().trim('"'))
        assertEquals(JsonNull, inserted["completed"])

        respond("""{"id":"t1"}""")
        val base = TaskFields("Milk", "", LocalDate.of(2026, 10, 23), true, 1_000)
        remote.patch("abc", "L1", "t1", GoogleTaskMapper.patchBody(base, base.copy(dueDay = null, done = false, completedAt = null)))
        val patch = request()
        assertEquals("PATCH", patch.method)
        assertEquals("/tasks/v1/lists/L1/tasks/t1", patch.path)
        val body = Json.parseToJsonElement(patch.body.readUtf8()).jsonObject
        assertEquals(setOf("due", "status", "completed"), body.keys)
        assertEquals(JsonNull, body["due"])
        assertEquals(JsonNull, body["completed"])

        respond("""{"id":"t1"}""")
        remote.move("abc", "L1", "t1", "L2")
        val move = request()
        assertEquals("POST", move.method)
        assertEquals("/tasks/v1/lists/L1/tasks/t1/move?destinationTasklist=L2", move.path)
    }

    @Test
    fun deleteTreatsGoneTasksAsDeleted() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(410))
        repeat(3) { remote.delete("abc", "L1", "t1") }
        assertEquals("DELETE", request().method)

        respond("{}", code = 500)
        val error = runCatching { remote.delete("abc", "L1", "t1") }.exceptionOrNull() as TasksApiException
        assertEquals(500, error.code)
        assertTrue(error.transient)
    }

    @Test
    fun errorBodiesAreClassified() = runBlocking {
        suspend fun failure(code: Int, body: String): TasksApiException {
            respond(body, code)
            try {
                remote.lists("abc")
            } catch (e: TasksApiException) {
                return e
            }
            fail("expected an error")
            throw AssertionError()
        }

        val classic = failure(403, """{"error":{"code":403,"message":"Tasks API has not been used","errors":[{"reason":"accessNotConfigured"}]}}""")
        assertTrue(classic.apiDisabled)
        assertFalse(classic.transient)

        val modern = failure(
            403,
            """{"error":{"code":403,"status":"PERMISSION_DENIED","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"SERVICE_DISABLED"}]}}""",
        )
        assertTrue(modern.apiDisabled)

        assertTrue(failure(403, """{"error":{"errors":[{"reason":"insufficientPermissions"}]}}""").insufficientScope)
        assertTrue(failure(403, """{"error":{"errors":[{"reason":"rateLimitExceeded"}]}}""").transient)
        assertTrue(failure(429, "").transient)
        val unauthorized = failure(401, "<html>not json</html>")
        assertEquals(401, unauthorized.code)
        assertTrue(unauthorized.reasons.isEmpty())
        val bad = failure(400, """{"error":{"code":400,"message":"Invalid value"}}""")
        assertFalse(bad.transient)
        assertTrue(bad.message!!.contains("Invalid value"))
    }

    @Test
    fun onlySureRefusalsOfOneTaskAreToldApartFromPassingErrors() {
        fun answer(code: Int, vararg reasons: String): TasksApiException {
            val items = reasons.joinToString(",") { """{"reason":"$it"}""" }
            return OkHttpTasksRemote.apiError(code, """{"error":{"code":$code,"errors":[$items]}}""")
        }

        // Pass on their own: tried again later, never parked.
        val passing = listOf(
            answer(408),
            OkHttpTasksRemote.apiError(409, """{"error":{"code":409,"status":"ABORTED"}}"""),
            answer(429),
            answer(500),
            answer(503, "backendError"),
            answer(403, "rateLimitExceeded"),
            answer(403, "userRateLimitExceeded"),
            answer(403, "dailyLimitExceeded"),
            answer(403, "quotaExceeded"),
            answer(403, "backendError"),
            answer(403, "forbidden", "userRateLimitExceeded"),
            answer(400, "rateLimitExceeded"),
        )
        passing.forEach { e ->
            assertTrue(e.message, e.transient)
            assertFalse(e.message, e.refusesThisTask)
        }

        // Google refuses this task's content or access to it.
        listOf(
            answer(400, "invalid"),
            OkHttpTasksRemote.apiError(400, """{"error":{"code":400,"message":"Invalid value"}}"""),
            OkHttpTasksRemote.apiError(413, "<html>Request Entity Too Large</html>"),
            answer(403, "forbidden"),
        ).forEach { e ->
            assertFalse(e.message, e.transient)
            assertTrue(e.message, e.refusesThisTask)
        }

        // No sure sign of either: the run ends and the task stays pending.
        listOf(
            OkHttpTasksRemote.apiError(403, "<html>Forbidden</html>"),
            answer(403, "limitExceeded"),
            answer(403, "accessNotConfigured"),
            answer(403, "insufficientPermissions"),
            answer(403, "forbidden", "insufficientPermissions"),
            answer(401, "authError"),
            answer(412, "conditionNotMet"),
        ).forEach { e ->
            assertFalse(e.message, e.transient)
            assertFalse(e.message, e.refusesThisTask)
        }
    }
}
