package com.celander.app.designsystem

import android.app.Application
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wheel shows what is selected, even when the caller refuses or corrects a value, and handles
 * its gestures itself, so a sheet around it never moves instead of the wheel. Its elastic edge
 * stretches with the finger and only bounces when a fling runs past the end.
 */
@OptIn(ExperimentalTestApi::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
@RunWith(RobolectricTestRunner::class)
class WheelPickerTest {
    @get:Rule val compose = createComposeRule()

    private val numbers = (0..5).toList()
    private val minutes = (0..59).toList()

    private fun showing(value: Int) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value.toString())

    private fun wheel() = compose.onNodeWithContentDescription(WHEEL)

    /** Lets turns, flings and the caller's moment to answer run out. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        compose.waitForIdle()
    }

    /** The app's elastic edge, recording what the wheel hands it. */
    private fun recordingEdge() = RecordingEdge(with(compose.density) { MotionTokens.RubberBandMaxBounce.toPx() })

    @Composable
    private fun Wheel(
        values: List<Int>,
        selected: Int,
        onSelect: (Int) -> Unit,
        reduceMotion: Boolean = false,
        edge: RecordingEdge? = null,
    ) {
        CelanderTheme(darkTheme = false, accentHue = 240f, glassTransparency = 0f, reduceMotion = reduceMotion) {
            if (edge == null) {
                WheelOf(values, selected, onSelect)
            } else {
                // Notes whether the content ever leaves its place, as the stretch changes.
                LaunchedEffect(edge) { snapshotFlow { edge.isInProgress }.collect { if (it) edge.stretched = true } }
                CompositionLocalProvider(LocalOverscrollFactory provides EdgeFactory(edge)) {
                    WheelOf(values, selected, onSelect)
                }
            }
        }
    }

    @Composable
    private fun WheelOf(values: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
        WheelPicker(
            values = values,
            selected = selected,
            onSelect = onSelect,
            label = { it.toString() },
            contentDescription = WHEEL,
            increaseLabel = NEXT,
            decreaseLabel = PREVIOUS,
        )
    }

    @Test
    fun aRefusedValueTurnsBack() {
        // Like the duration in Settings, which never goes below its minimum: the value stays.
        val reported = mutableListOf<Int>()
        compose.setContent { Wheel(numbers, selected = 2, onSelect = { reported += it }) }
        wheel().assert(showing(2))

        wheel().performCustomAccessibilityActionWithLabel(NEXT)
        settle()

        assertEquals(listOf(3), reported)
        wheel().assert(showing(2))
    }

    @Test
    fun aValueTheCallerTakesAMomentLaterStays() {
        // Like the editor, whose view model applies a time asynchronously.
        var selected by mutableStateOf(2)
        compose.setContent {
            val scope = rememberCoroutineScope()
            Wheel(numbers, selected, onSelect = { value ->
                scope.launch {
                    delay(CALLER_MILLIS)
                    selected = value
                }
            })
        }

        wheel().performCustomAccessibilityActionWithLabel(NEXT)
        settle()

        assertEquals(3, selected)
        wheel().assert(showing(3))
    }

    @Test
    fun aValueTheCallerTakesWhileTheWheelTurnsBackStays() {
        // Like a slow settings write: the answer only comes once the wheel has begun to turn back.
        var selected by mutableStateOf(2)
        compose.setContent {
            val scope = rememberCoroutineScope()
            Wheel(numbers, selected, onSelect = { value ->
                scope.launch {
                    delay(LATE_CALLER_MILLIS)
                    selected = value
                }
            })
        }

        wheel().performCustomAccessibilityActionWithLabel(NEXT)
        settle()

        assertEquals(3, selected)
        wheel().assert(showing(3))
    }

    @Test
    fun aFlingWithinTheListSnapsWithoutBouncing() {
        var selected by mutableStateOf(10)
        val edge = recordingEdge()
        compose.setContent { Wheel(minutes, selected, onSelect = { selected = it }, edge = edge) }
        compose.waitForIdle()

        wheel().performTouchInput { swipeUp(startY = centerY + height / 3f, endY = centerY - height / 3f, durationMillis = 200) }
        settle()

        assertTrue("the wheel snapped to a value within the list: $selected", selected in 11..58)
        wheel().assert(showing(selected))
        assertEquals("the snap used the whole fling, nothing is left to bounce", 0f, edge.bounced, 0.5f)
        assertFalse("the values never left the band", edge.stretched)
    }

    @Test
    fun pulledPastTheFirstValueTheContentFollowsTheFinger() {
        var selected by mutableStateOf(0)
        val edge = recordingEdge()
        compose.setContent { Wheel(minutes, selected, onSelect = { selected = it }, edge = edge) }
        compose.waitForIdle()

        wheel().performTouchInput { swipeDown(startY = centerY - height / 3f, endY = centerY + height / 3f, durationMillis = 300) }
        settle()

        // The edge works in finger direction: positive stretches the content down, with the finger.
        assertTrue("a pull down at the first value stretches the content down: ${edge.pulled}", edge.pulled > 0f)
        assertTrue("the release is handed on downward too: ${edge.flung}", edge.flung > 0f)
        assertEquals(0, selected)
        wheel().assert(showing(0))
    }

    @Test
    fun swipingTheWheelInASheetTurnsTheWheelNotTheSheet() {
        var selected by mutableStateOf(2)
        compose.setContent {
            CelanderTheme(darkTheme = false, accentHue = 240f, glassTransparency = 0f, reduceMotion = false) {
                // Half open, so the sheet would take an upward drag to open fully.
                GlassSheet(onDismissed = {}, expandable = true) {
                    WheelOf(minutes, selected, onSelect = { selected = it })
                }
            }
        }
        compose.waitForIdle()
        val top = wheel().getBoundsInRoot().top

        wheel().performTouchInput { swipeUp(startY = centerY + height / 3f, endY = centerY - height / 3f, durationMillis = 150) }
        settle()

        assertEquals("the sheet stays where it was", top.value, wheel().getBoundsInRoot().top.value, 0.5f)
        assertTrue("the wheel turned on from 2 to $selected", selected > 2)
        wheel().assert(showing(selected))
    }

    @Test
    fun aShorterListKeepsTheWheelWorking() {
        var values by mutableStateOf(minutes)
        var selected by mutableStateOf(50)
        compose.setContent { Wheel(values, selected, onSelect = { selected = it }) }
        compose.waitForIdle()

        compose.runOnIdle {
            values = (0..11).toList()
            selected = 5
        }
        settle()

        wheel().assert(showing(5))
    }

    @Test
    fun withReducedMotionTheWheelJumpsToAValueFromOutside() {
        var selected by mutableStateOf(0)
        compose.setContent { Wheel(minutes, selected, onSelect = { selected = it }, reduceMotion = true) }
        compose.waitForIdle()

        val before = compose.mainClock.currentTime
        compose.runOnIdle { selected = 40 }
        compose.waitForIdle()

        wheel().assert(showing(40))
        // An animated turn over 40 values takes many frames of the test clock; a jump only a few.
        assertTrue(compose.mainClock.currentTime - before <= JUMP_FRAMES * FRAME_MILLIS)
    }

    @Test
    fun aMouseWheelTurnsOneValuePerNotch() {
        var selected by mutableStateOf(2)
        compose.setContent { Wheel(numbers, selected, onSelect = { selected = it }) }

        wheel().performMouseInput {
            moveTo(center)
            scroll(1f)
        }
        settle()

        assertEquals(3, selected)
        wheel().assert(showing(3))
    }

    private companion object {
        const val WHEEL = "Minutes"
        const val NEXT = "Next value"
        const val PREVIOUS = "Previous value"
        const val SETTLE_MILLIS = 3_000L
        const val CALLER_MILLIS = 100L

        /** After the wheel's 400 ms for an answer, while its turn back (a spring of a few hundred ms) runs. */
        const val LATE_CALLER_MILLIS = 450L
        const val JUMP_FRAMES = 5
        const val FRAME_MILLIS = 16L
    }
}

/**
 * The app's elastic edge, with a record of what the wheel hands it. Like Compose's own scrolling it
 * works in finger direction: positive deltas and velocities point down, and a positive stretch
 * moves the content down.
 */
private class RecordingEdge(maxBouncePx: Float) : OverscrollEffect {
    private val effect = RubberBandOverscrollEffect(maxBouncePx)

    /** Drag the list could not take at its edge; the content stretches by it. */
    var pulled = 0f
        private set

    /** Release velocity the wheel handed over. */
    var flung = 0f
        private set

    /** Velocity the wheel's fling did not use; the content bounces with it. */
    var bounced = 0f
        private set

    /** Whether the content ever left its place. */
    var stretched = false

    override val isInProgress: Boolean get() = effect.isInProgress

    override val node: DelegatableNode get() = effect.node

    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset =
        effect.applyToScroll(delta, source) { available -> performScroll(available).also { pulled += (available - it).y } }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        flung += velocity.y
        effect.applyToFling(velocity) { available -> performFling(available).also { bounced += (available - it).y } }
    }
}

private class EdgeFactory(private val edge: OverscrollEffect) : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = edge

    override fun equals(other: Any?): Boolean = other is EdgeFactory && other.edge === edge

    override fun hashCode(): Int = System.identityHashCode(edge)
}
