package com.celander.app.calendar.month

import android.app.Application
import androidx.compose.animation.core.spring
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The month's day list under the finger: how a drag ends and how the list's state answers it. */
@OptIn(ExperimentalCoroutinesApi::class)
@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class DayPanelSwipeTest {
    private val scope = TestScope()

    /** A frame every 16 ms of the test's virtual time. */
    private val frames = object : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            delay(FRAME_MILLIS)
            return onFrame(scope.testScheduler.currentTime * NANOS_PER_MILLI)
        }
    }

    private fun swipe() = DayPanelSwipe(
        scope = scope + frames,
        spring = spring(dampingRatio = 0.86f, stiffness = 380f),
        reduced = false,
        stretchPx = 48f,
        flingVelocity = 900f,
    ).apply { panelHeight = 1_000f }

    private fun opened(): DayPanelSwipe {
        val swipe = swipe()
        swipe.sync(true)
        scope.advanceUntilIdle()
        return swipe
    }

    private fun frame() {
        scope.advanceTimeBy(FRAME_MILLIS)
        scope.runCurrent()
    }

    @Test
    fun theListConfirmingAFlickDoesNotStopItsSpring() {
        val confirmed = opened()
        val untouched = opened()
        assertEquals(1f, confirmed.progress, 0.0001f)

        // Both pulled a fifth of the way down and flicked shut.
        for (swipe in listOf(confirmed, untouched)) {
            swipe.drag(200f)
            swipe.release(3_000f)
        }
        frame()
        frame()
        // The closed list comes back through the panel state, as the month's LaunchedEffect hands it on.
        confirmed.sync(false)
        repeat(10) {
            frame()
            assertEquals("frame $it", untouched.progress, confirmed.progress, 0.0001f)
        }
        assertTrue(confirmed.progress < 0.8f)
    }

    @Test
    fun aListThatGoesAwayMidDragEndsTheDrag() {
        var closed = false
        val swipe = opened()
        swipe.onClose = { closed = true }

        // At its top the list hands a long pull down to the panel …
        swipe.nestedScroll.onPostScroll(Offset.Zero, Offset(0f, 900f), NestedScrollSource.UserInput)
        assertTrue(swipe.dragging)
        // … and leaves before the finger lifts, so its fling never comes.
        swipe.listGone()

        assertFalse(swipe.dragging)
        assertTrue(closed)
        scope.advanceUntilIdle()
        assertEquals(0f, swipe.progress, 0.0001f)
    }

    @Test
    fun aGridDragOutlivesTheList() {
        val swipe = opened()
        swipe.drag(300f)
        // The list was not part of this drag; the grid's own gesture ends it.
        swipe.listGone()
        assertTrue(swipe.dragging)
        swipe.release(0f)
        assertFalse(swipe.dragging)
    }

    private companion object {
        const val FRAME_MILLIS = 16L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
