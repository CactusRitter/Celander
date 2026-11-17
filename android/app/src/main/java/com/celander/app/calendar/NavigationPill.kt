package com.celander.app.calendar

import androidx.annotation.StringRes
import com.celander.app.ui.glass.GlassKind
import com.celander.app.ui.glass.glass
import androidx.compose.material3.MaterialTheme
import com.celander.app.ui.LocalMotion
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CalendarViewDay
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.celander.app.R
import com.celander.app.ui.theme.CelanderTheme
import com.celander.app.ui.Sizes
import com.celander.app.ui.blockTouchesBelow
import com.celander.app.ui.rememberHaptics
import com.celander.app.ui.springClickable
import kotlinx.coroutines.launch

val HomeDestination.icon: ImageVector
    get() = when (this) {
        HomeDestination.YEAR -> Icons.Outlined.Apps
        HomeDestination.MONTH -> Icons.Outlined.CalendarMonth
        HomeDestination.DAY -> Icons.Outlined.CalendarViewDay
        HomeDestination.AGENDA -> Icons.Outlined.ViewAgenda
        HomeDestination.TASKS -> Icons.Outlined.TaskAlt
    }

@get:StringRes
val HomeDestination.label: Int
    get() = when (this) {
        HomeDestination.YEAR -> R.string.view_year
        HomeDestination.MONTH -> R.string.view_month
        HomeDestination.DAY -> R.string.view_day
        HomeDestination.AGENDA -> R.string.view_agenda
        HomeDestination.TASKS -> R.string.view_tasks
    }

private val ChevronSize = 16.dp

/**
 * Floating navigation pill of icons only; the heading above the view names the period. The active
 * segment sits on a tinted container and, with a small chevron, opens the date picker. "New entry"
 * sits at the top, next to search ([HomeTopBar]).
 *
 * The tinted container behind the active segment is its own layer: it glides to a newly chosen
 * segment, and a finger sliding along the pill drags it along, ticking at every segment it passes
 * and opening that view right away. While the finger is down the segments keep their places (the
 * wide one stays where the slide began), so nothing shifts under it; lifting it lets them settle.
 */
@Composable
fun NavigationPill(
    current: HomeDestination,
    destinations: List<HomeDestination>,
    onSelect: (HomeDestination) -> Unit,
    onContextClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme

    Box(modifier, contentAlignment = Alignment.Center) {
        val outerPadding = 4.dp + 2.dp
        val motion = LocalMotion.current
        val haptics = rememberHaptics()
        val scope = rememberCoroutineScope()
        val currentOnSelect by rememberUpdatedState(onSelect)
        val currentDestination by rememberUpdatedState(current)
        val currentContextClick by rememberUpdatedState(onContextClick)
        // During a slide: the segment that stays wide, as it was when the slide began.
        var anchor by remember { mutableStateOf<HomeDestination?>(null) }
        var anchorContextClick by remember { mutableStateOf<(() -> Unit)?>(null) }
        val shown = anchor ?: current
        val shownContextClick = if (anchor != null) anchorContextClick else onContextClick
        // Segment bounds on screen and the left edge of the box that draws the container; their
        // difference stays put while the pill itself moves (it is centered and changes width).
        val onScreen = remember { mutableStateMapOf<HomeDestination, SegmentBounds>() }
        var originX by remember { mutableFloatStateOf(0f) }
        fun bounds(destination: HomeDestination) = onScreen[destination]?.shiftedBy(-originX)
        val center = remember { Animatable(0f) }
        val width = remember { Animatable(0f) }
        var placed by remember { mutableStateOf(false) }
        var hovered by remember { mutableStateOf<HomeDestination?>(null) }

        // At rest the container sits behind the active segment and follows it when that changes.
        val active = bounds(shown)
        LaunchedEffect(active, hovered) {
            if (active == null || hovered != null) return@LaunchedEffect
            if (!placed) {
                center.snapTo(active.center)
                width.snapTo(active.width)
                placed = true
            } else {
                launch { center.animateTo(active.center, motion.standard()) }
                width.animateTo(active.width, motion.standard())
            }
        }

        // blockTouchesBelow consumes presses on their way out of the pill, so the slide also takes
        // a down that is already consumed.
        Box(
            Modifier
                .glass(CircleShape, GlassKind.Chrome, elevation = 10.dp)
                .pointerInput(destinations) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val first = destinations.firstOrNull()?.let(::bounds) ?: return@awaitEachGesture
                        val last = destinations.lastOrNull()?.let(::bounds) ?: return@awaitEachGesture
                        // Only a slide that starts on the view segments, not on the + button.
                        if (down.position.x !in first.left..last.right) return@awaitEachGesture
                        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
                        anchor = currentDestination
                        anchorContextClick = currentContextClick
                        fun follow(x: Float) {
                            val clamped = x.coerceIn(first.left, last.right)
                            val under = destinations.minByOrNull { d -> bounds(d)?.distanceTo(clamped) ?: Float.MAX_VALUE } ?: return
                            if (under != hovered) {
                                if (hovered != null) haptics.tick()
                                hovered = under
                                if (under != currentDestination) currentOnSelect(under)
                                bounds(under)?.let { scope.launch { width.animateTo(it.width, motion.standard()) } }
                            }
                            scope.launch { center.snapTo(clamped) }
                        }
                        follow(drag.position.x)
                        horizontalDrag(drag.id) { change ->
                            change.consume()
                            follow(change.position.x)
                        }
                        hovered = null
                        anchor = null
                        anchorContextClick = null
                    }
                }
                .blockTouchesBelow(),
        ) {
            Box(
                Modifier
                    .onGloballyPositioned { originX = it.positionInRoot().x }
                    .drawBehind {
                        if (!placed) return@drawBehind
                        val height = Sizes.pillAddButton.toPx()
                        drawRoundRect(
                            color = colors.primaryContainer,
                            topLeft = Offset(center.value - width.value / 2, (size.height - height) / 2),
                            size = Size(width.value, height),
                            cornerRadius = CornerRadius(height / 2),
                        )
                    }
            ) {
                Row(
                    Modifier
                        .height(Sizes.pillHeight)
                        .padding(horizontal = outerPadding)
                        .animateContentSize(motion.standard()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    destinations.forEach { destination ->
                        if (destination == HomeDestination.TASKS) PillDivider()
                        val track = Modifier.onGloballyPositioned { onScreen[destination] = SegmentBounds.of(it) }
                        if (destination == shown) {
                            ActiveSegment(destination, shownContextClick, track)
                        } else {
                            IconSegment(destination, highlighted = destination == hovered, modifier = track, onClick = { onSelect(destination) })
                        }
                    }
                }
            }
        }
    }
}

/** Where a segment sits along the pill, in px of the box that draws the container. */
private data class SegmentBounds(val left: Float, val right: Float) {
    val center get() = (left + right) / 2
    val width get() = right - left

    fun distanceTo(x: Float): Float = when {
        x < left -> left - x
        x > right -> x - right
        else -> 0f
    }

    fun shiftedBy(dx: Float) = SegmentBounds(left + dx, right + dx)

    companion object {
        fun of(segment: LayoutCoordinates): SegmentBounds {
            val left = segment.positionInRoot().x
            return SegmentBounds(left, left + segment.size.width)
        }
    }
}

@Composable
private fun IconSegment(destination: HomeDestination, highlighted: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val name = stringResource(destination.label)
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .size(Sizes.pillSegment)
            .springClickable(shape = CircleShape, pressedScale = 0.9f, onClick = onClick)
            .semantics {
                role = Role.Tab
                selected = false
                contentDescription = name
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(destination.icon, null, tint = if (highlighted) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(Sizes.icon))
    }
}

@Composable
private fun ActiveSegment(destination: HomeDestination, onContextClick: (() -> Unit)?, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val name = stringResource(destination.label)
    val pickDate = stringResource(R.string.pill_pick_date)
    // The tinted container behind it is drawn by the pill, so it can follow a finger.
    Row(
        modifier
            .height(Sizes.pillAddButton)
            .springClickable(enabled = onContextClick != null, onClickLabel = pickDate, shape = CircleShape) { onContextClick?.invoke() }
            .semantics {
                role = Role.Tab
                selected = true
                contentDescription = name
            }
            .padding(horizontal = 12.dp - 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(destination.icon, null, tint = colors.primary, modifier = Modifier.size(Sizes.icon))
        if (onContextClick != null) {
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Rounded.ExpandMore, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(ChevronSize))
        }
    }
}

@Composable
private fun PillDivider() {
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .size(width = Sizes.hairline, height = 24.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}
