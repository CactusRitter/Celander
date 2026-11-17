package com.celander.app.designsystem

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.DragScope
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Scroll wheel like Android's number picker: the value in the middle band is selected, neighbours
 * shrink and fade towards the edges, flings snap to a value with a tick. [onSelect] runs when the
 * wheel comes to rest on a value other than [selected]; afterwards the wheel shows whatever
 * [selected] is, so a value the caller refuses or corrects turns back. For TalkBack it is one
 * element with "next" and "previous" actions; a mouse wheel turns it one value per notch.
 *
 * The wheel turns its list itself instead of through the list's scrolling, which would hand
 * drags and flings on to nested scrolling: a sheet around the wheel would move instead of it.
 */
@Composable
fun <T> WheelPicker(
    values: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    contentDescription: String,
    increaseLabel: String,
    decreaseLabel: String,
    modifier: Modifier = Modifier,
    visibleItems: Int = VISIBLE_ITEMS,
) {
    val colors = CelanderTheme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val state = rememberLazyListState(initialFirstVisibleItemIndex = values.indexOf(selected).coerceAtLeast(0))
    val currentValues by rememberUpdatedState(values)
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val reduced by rememberUpdatedState(CelanderTheme.motion.reduced)
    val itemPx = with(LocalDensity.current) { WheelItemHeight.toPx() }
    val overscroll = rememberOverscrollEffect()
    val snapFling = rememberSnapFlingBehavior(state)
    val drag = remember(state, snapFling, overscroll) { WheelDragState(state, snapFling, overscroll) }
    // A gesture or step from its start until the caller has had its moment to answer.
    var settling by remember { mutableStateOf<Job?>(null) }

    // The item whose centre is closest to the band in the middle.
    val center by remember(state, itemPx) {
        derivedStateOf {
            val offset = state.firstVisibleItemScrollOffset
            (state.firstVisibleItemIndex + if (offset > itemPx / 2) 1 else 0).coerceIn(0, currentValues.lastIndex.coerceAtLeast(0))
        }
    }

    suspend fun turnTo(index: Int) {
        if (index < 0 || (state.firstVisibleItemIndex == index && state.firstVisibleItemScrollOffset == 0)) return
        if (reduced) state.scrollToItem(index) else state.animateScrollToItem(index)
    }

    // At rest: report the value in the middle and give the caller a moment to take or correct it
    // (a view model may answer a little later), then show whatever is selected by then. An answer
    // that only arrives while the wheel turns is followed too, since nothing else would.
    suspend fun answer() {
        val value = currentValues.getOrNull(center) ?: return
        if (value != currentSelected) {
            currentOnSelect(value)
            delay(ANSWER_MILLIS)
        }
        do {
            val target = currentValues.indexOf(currentSelected)
            turnTo(target)
        } while (currentValues.indexOf(currentSelected) != target)
    }

    fun settle(movement: suspend () -> Unit) {
        settling?.cancel()
        settling = scope.launch {
            movement()
            answer()
        }
    }

    fun step(by: Int): Boolean {
        if (currentValues.isEmpty()) return false
        val target = (center + by).coerceIn(0, currentValues.lastIndex)
        if (target == center) return false
        settle { turnTo(target) }
        return true
    }

    // A tick for every value that passes the middle.
    LaunchedEffect(state) {
        snapshotFlow { center }.collect { if (state.isScrollInProgress) haptics.tick() }
    }
    // Changed from outside (another wheel corrected it): turn to the new value. A gesture under way
    // is left alone; it turns to the selected value itself once it has settled.
    LaunchedEffect(state) {
        snapshotFlow { currentValues.indexOf(currentSelected) }.collectLatest { index ->
            if (settling?.isActive != true && !state.isScrollInProgress) turnTo(index)
        }
    }

    val current = currentValues.getOrNull(center)?.let(label).orEmpty()
    Box(
        modifier
            .height(WheelItemHeight * visibleItems)
            .draggable(
                drag,
                Orientation.Vertical,
                // A finger on the turning wheel stops it where it is.
                startDragImmediately = state.isScrollInProgress,
                onDragStarted = { settling?.cancel() },
                onDragStopped = { velocity -> settle { drag.fling(velocity) } },
            )
            .pointerInput(state, itemPx) {
                awaitPointerEventScope {
                    var notches = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        notches += event.changes.fold(0f) { sum, change -> sum + change.scrollDelta.y }
                        if (abs(notches) >= 1f) {
                            step(if (notches > 0f) 1 else -1)
                            notches = 0f
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
                stateDescription = current
                customActions = listOf(
                    CustomAccessibilityAction(increaseLabel) { step(1) },
                    CustomAccessibilityAction(decreaseLabel) { step(-1) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // The selection band behind the middle value.
        Box(Modifier.fillMaxWidth().height(WheelItemHeight).background(colors.accent.copy(alpha = BAND_ALPHA), PillShape))
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(vertical = WheelItemHeight * (visibleItems / 2)),
            horizontalAlignment = Alignment.CenterHorizontally,
            userScrollEnabled = false,
            overscrollEffect = overscroll,
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(values) { index, value ->
                Text(
                    label(value),
                    style = CelanderTheme.typography.titleLarge,
                    color = if (index == center) colors.textPrimary else colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WheelItemHeight)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .graphicsLayer {
                            // Distance from the middle in items, from the live scroll position.
                            val position = index - state.firstVisibleItemIndex - state.firstVisibleItemScrollOffset / itemPx
                            val distance = abs(position).coerceAtMost(visibleItems / 2f + 1f)
                            val scale = 1f - distance * SHRINK_PER_ITEM
                            scaleX = scale
                            scaleY = scale
                            alpha = (1f - distance * FADE_PER_ITEM).coerceAtLeast(0f)
                        },
                )
            }
        }
    }
}

/**
 * Drags turn the wheel's list like its own scrolling would, with the same elastic edge, but
 * without nested scrolling. Deltas and velocities come in finger direction; the list scrolls the
 * other way, so a drag down shows earlier values. The elastic edge works in finger direction, as
 * with Compose's scrolling, so the content stretches with the finger: only the list's own
 * deltas and velocities are turned around.
 */
private class WheelDragState(
    private val list: LazyListState,
    private val snapFling: FlingBehavior,
    private val overscroll: OverscrollEffect?,
) : DraggableState {
    override suspend fun drag(dragPriority: MutatePriority, block: suspend DragScope.() -> Unit) {
        val effect = overscroll
        list.scroll(dragPriority) {
            val scroll = this
            val dragScope = object : DragScope {
                override fun dragBy(pixels: Float) {
                    if (effect == null) {
                        scroll.scrollBy(-pixels)
                    } else {
                        effect.applyToScroll(Offset(0f, pixels), NestedScrollSource.UserInput) { available ->
                            Offset(0f, -scroll.scrollBy(-available.y))
                        }
                    }
                }
            }
            dragScope.block()
        }
    }

    override fun dispatchRawDelta(delta: Float) {
        list.dispatchRawDelta(-delta)
    }

    /** Lets the wheel run on after a release at [velocity] and snaps it to a value. */
    suspend fun fling(velocity: Float) {
        val effect = overscroll
        if (effect == null) {
            list.scroll { with(snapFling) { performFling(-velocity) } }
        } else {
            effect.applyToFling(Velocity(0f, velocity)) { available ->
                var remaining = 0f
                list.scroll { remaining = with(snapFling) { performFling(-available.y) } }
                // The effect bounces with what the fling did not use, so it wants the used part;
                // the snap returns what it left over. A snap within the list uses everything.
                available - Velocity(0f, -remaining)
            }
        }
    }
}

private const val VISIBLE_ITEMS = 5
private const val BAND_ALPHA = 0.16f
private const val SHRINK_PER_ITEM = 0.12f
private const val FADE_PER_ITEM = 0.32f
private val WheelItemHeight = Sizes.touchTarget

/** How long the caller has to take or correct a reported value before the wheel shows `selected` again. */
private const val ANSWER_MILLIS = 400L
