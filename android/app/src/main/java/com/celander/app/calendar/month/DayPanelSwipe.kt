package com.celander.app.calendar.month

import androidx.compose.animation.core.AnimationSpec
import com.celander.app.ui.LocalMotion
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import com.celander.app.ui.theme.CelanderTheme
import com.celander.app.ui.MotionTokens
import com.celander.app.ui.rubberBand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * How far the month's day list is open, driven directly by the finger: [progress] 0 is closed, 1 is
 * open, above 1 the list is pulled past open and stretches like a rubber band. While dragging, the
 * grid and the list resize with the finger; on release a fast spring finishes the move.
 */
@Stable
internal class DayPanelSwipe(
    private val scope: CoroutineScope,
    private val spring: AnimationSpec<Float>,
    private val reduced: Boolean,
    private val stretchPx: Float,
    private val flingVelocity: Float,
) {
    var onOpen: () -> Unit = {}
    var onClose: () -> Unit = {}

    /** Height in px the open list takes; finger travel is measured against it. */
    var panelHeight = 0f

    var progress by mutableFloatStateOf(0f)
        private set

    /**
     * A finger moves the list. Observable, so the list stays composed while it does, even pulled
     * all the way shut: the end of a drag inside the list arrives through the list.
     */
    var dragging by mutableStateOf(false)
        private set

    private var open = false

    /** The drag came through the list's nested scrolling; only the list reports its end. */
    private var draggingList = false

    /** Progress the finger asks for, without the rubber band. */
    private var raw = 0f
    private var settle: Job? = null

    /** Where the spring of [settle] goes. */
    private var settleTarget = 0f

    /** The list was opened or closed elsewhere (a tap, the close button, back). */
    fun sync(open: Boolean) {
        val target = if (open) 1f else 0f
        // A release that opened or closed the list hears back from it here; its spring already runs
        // there with the finger's speed, and starting it over would stop the list dead.
        if (this.open == open && (settlingTo(target) || progress == target)) return
        this.open = open
        if (!dragging) animateTo(target, 0f)
    }

    /** Applies a drag of [delta] px (down is positive) and returns the part it used. */
    fun drag(delta: Float): Float {
        if (panelHeight <= 0f) return 0f
        // Closed, only an upward pull means something.
        if (!dragging && progress <= 0f && delta > 0f) return 0f
        if (!dragging) {
            settle?.cancel()
            dragging = true
            raw = progress.coerceAtMost(1f)
        }
        raw = (raw - delta / panelHeight).coerceAtLeast(0f)
        progress = if (raw <= 1f) raw else 1f + rubberBand((raw - 1f) * panelHeight, stretchPx) / panelHeight
        return delta
    }

    /** End of the gesture: past half way or with a flick it opens or closes, else it snaps back. */
    fun release(velocity: Float) {
        if (!dragging) return
        dragging = false
        draggingList = false
        val towardsOpen = when {
            velocity < -flingVelocity -> true
            velocity > flingVelocity -> false
            else -> raw > HALF_WAY
        }
        if (towardsOpen && !open) onOpen()
        if (!towardsOpen && open) onClose()
        open = towardsOpen
        animateTo(if (towardsOpen) 1f else 0f, -velocity / panelHeight)
    }

    /**
     * The list left the screen (it emptied, the month closed) while it moved the panel. Its fling,
     * which would end the drag, never comes, so the drag ends here as if the finger stood still.
     */
    fun listGone() {
        if (draggingList) release(0f)
    }

    private fun settlingTo(target: Float): Boolean = settle?.isActive == true && settleTarget == target

    private fun animateTo(target: Float, velocity: Float) {
        settle?.cancel()
        if (reduced) {
            progress = target
            return
        }
        settleTarget = target
        settle = scope.launch { animate(progress, target, velocity, spring) { value, _ -> progress = value } }
    }

    /** A drag the list hands on; from then on the list reports the end of the gesture. */
    private fun dragFromList(delta: Float): Float {
        val used = drag(delta)
        if (dragging) draggingList = true
        return used
    }

    /**
     * For the list inside the open panel: at its top, a downward drag moves the panel; once the
     * panel moves, every drag goes to it until the finger lifts.
     */
    val nestedScroll = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (source == NestedScrollSource.UserInput && dragging) Offset(0f, dragFromList(available.y)) else Offset.Zero

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (source == NestedScrollSource.UserInput && open && available.y > 0f) Offset(0f, dragFromList(available.y)) else Offset.Zero

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!dragging) return Velocity.Zero
            release(available.y)
            return available
        }
    }

    private companion object {
        const val HALF_WAY = 0.5f
    }
}

@Composable
internal fun rememberDayPanelSwipe(): DayPanelSwipe {
    val scope = rememberCoroutineScope()
    val motion = LocalMotion.current
    val density = LocalDensity.current
    return remember(motion.reduced, density) {
        with(density) {
            DayPanelSwipe(
                scope = scope,
                spring = motion.page(),
                reduced = motion.reduced,
                stretchPx = MotionTokens.SheetStretch.toPx(),
                flingVelocity = MotionTokens.PanelSwipeVelocity.toPx(),
            )
        }
    }
}
