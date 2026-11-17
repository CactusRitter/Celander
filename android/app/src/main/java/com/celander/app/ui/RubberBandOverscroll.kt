package com.celander.app.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Leftover deltas and velocities below this (px, px/s) are rounding noise. */
private const val NOISE = 0.5f

/** Critically damped: springs back without crossing the edge, like iOS. */
private val RubberBandSpring: AnimationSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = MotionTokens.RUBBER_BAND_STIFFNESS)

/** Angular frequency of [RubberBandSpring] (mass 1). */
private val SpringOmega = sqrt(MotionTokens.RUBBER_BAND_STIFFNESS)

/**
 * iOS rubber band: [overshoot] px of finger travel past the edge move the content by
 * `(1 - 1 / (x * 0.55 / d + 1)) * d`, which approaches [dimension] but never reaches it.
 */
fun rubberBand(overshoot: Float, dimension: Float): Float {
    if (dimension <= 0f) return 0f
    val x = abs(overshoot)
    return sign(overshoot) * (1f - 1f / (x * MotionTokens.RUBBER_BAND_RESISTANCE / dimension + 1f)) * dimension
}

/** Inverse of [rubberBand]: the finger travel that produces the displayed [offset]. */
fun rubberBandInverse(offset: Float, dimension: Float): Float {
    if (dimension <= 0f) return 0f
    val y = abs(offset).coerceAtMost(dimension * 0.999f)
    return sign(offset) * dimension / MotionTokens.RUBBER_BAND_RESISTANCE * y / (dimension - y)
}

/** One axis of the effect: displayed translation plus the spring that brings it back to 0. */
@Stable
private class RubberBandAxis {
    /**
     * Translation of the content in px; positive moves it right/down. Same space as the deltas the
     * effect gets, which point the way the finger moves (not the way the list scrolls).
     */
    var offset by mutableFloatStateOf(0f)
        private set
    private var springVelocity = 0f
    private var spring: Job? = null

    val isActive: Boolean get() = offset != 0f || spring?.isActive == true

    fun stop() {
        spring?.cancel()
        spring = null
        springVelocity = 0f
    }

    fun reset() {
        stop()
        offset = 0f
    }

    /** A delta pointing back toward the content first shrinks the stretch; returns the consumed part. */
    fun relax(delta: Float, dimension: Float): Float {
        if (offset == 0f || delta == 0f || sign(delta) == sign(offset)) return 0f
        val raw = rubberBandInverse(offset, dimension)
        val next = raw + delta
        return if (sign(next) != sign(raw)) {
            offset = 0f
            -raw
        } else {
            offset = rubberBand(next, dimension)
            delta
        }
    }

    /** Drag left over at the edge stretches the content with growing resistance; consumes all of it. */
    fun pull(delta: Float, dimension: Float): Float {
        if (abs(delta) < NOISE) return 0f
        offset = rubberBand(rubberBandInverse(offset, dimension) + delta, dimension)
        return delta
    }

    /**
     * Released while stretched: returns the part of [velocity] the content should still fling with.
     * Velocity toward the content beyond what the critically damped spring can take without
     * overshooting the edge is handed back, so a flick out of a stretch keeps scrolling.
     */
    fun CoroutineScope.release(velocity: Float, maxVelocity: Float): Float {
        if (offset == 0f && spring?.isActive != true) return velocity
        val towardContent = velocity != 0f && sign(velocity) != sign(offset)
        val springVelocity = when {
            towardContent -> sign(velocity) * min(abs(velocity), SpringOmega * abs(offset))
            else -> velocity.coerceIn(-maxVelocity, maxVelocity)
        }
        launch { springTo0(springVelocity) }
        return if (towardContent) velocity - springVelocity else 0f
    }

    /** Overshoot after a fling hit the edge: out with [velocity], then back to 0. */
    fun CoroutineScope.bounce(velocity: Float, maxVelocity: Float) {
        if (abs(velocity) < NOISE) return
        launch { springTo0(velocity.coerceIn(-maxVelocity, maxVelocity)) }
    }

    private suspend fun springTo0(initialVelocity: Float) = coroutineScope {
        // A tap on a bouncing list keeps its momentum instead of restarting from rest.
        val velocity = if (initialVelocity == 0f && spring?.isActive == true) springVelocity else initialVelocity
        stop()
        val start = offset
        if (start == 0f && abs(velocity) < NOISE) return@coroutineScope
        spring = launch {
            animate(start, 0f, velocity, RubberBandSpring) { value, v ->
                offset = value
                springVelocity = v
            }
        }
    }
}

/**
 * iOS-like overscroll: past the edge the content follows the finger with growing resistance
 * ([rubberBand]), springs back on release and briefly overshoots when a fling hits the edge.
 *
 * Only what the scrollable and its nested-scroll parents left over is used, so a parent that
 * consumes (e.g. a sheet that follows a downward drag while its list is at the top) still wins.
 */
@Stable
class RubberBandOverscrollEffect(private val maxBounceDistancePx: Float) : OverscrollEffect {
    private val x = RubberBandAxis()
    private val y = RubberBandAxis()
    private var width = 0f
    private var height = 0f

    override val isInProgress: Boolean get() = x.isActive || y.isActive

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        // Fling deltas pass through untouched: the edge must stay unconsumed so the fling stops
        // there and hands its remaining velocity to applyToFling, which turns it into the bounce.
        if (source != NestedScrollSource.UserInput) return performScroll(delta)

        x.stop()
        y.stop()
        val relaxed = Offset(x.relax(delta.x, width), y.relax(delta.y, height))
        val forScroll = delta - relaxed
        val consumedByScroll = performScroll(forScroll)
        val leftover = forScroll - consumedByScroll
        val pulled = Offset(x.pull(leftover.x, width), y.pull(leftover.y, height))
        return relaxed + consumedByScroll + pulled
    }

    /**
     * A drag toward the content shrinks a stretch before anything scrolls; returns the consumed
     * part. For containers that see the drag before the list inside them does.
     */
    fun relax(delta: Offset): Offset {
        if (!isInProgress) return Offset.Zero
        x.stop()
        y.stop()
        return Offset(x.relax(delta.x, width), y.relax(delta.y, height))
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        coroutineScope {
            val maxX = maxBounceVelocity(width)
            val maxY = maxBounceVelocity(height)
            val forContent = Velocity(
                with(x) { release(velocity.x, maxX) },
                with(y) { release(velocity.y, maxY) },
            )
            // Called exactly once so nested-scroll parents still get onPreFling/onPostFling.
            val left = forContent - performFling(forContent)
            with(x) { bounce(left.x, maxX) }
            with(y) { bounce(left.y, maxY) }
        }
    }

    /** Peak of a critically damped spring started at 0 with v is v / (omega * e); cap that peak. */
    private fun maxBounceVelocity(dimension: Float): Float {
        val peak = if (dimension > 0f) min(maxBounceDistancePx, dimension * MotionTokens.RUBBER_BAND_MAX_BOUNCE_FRACTION) else maxBounceDistancePx
        return peak * SpringOmega * E.toFloat()
    }

    /** Placed by the scroll container right inside its clip; translates the content in a layer. */
    override val node: DelegatableNode = RubberBandNode()

    private inner class RubberBandNode : Modifier.Node(), LayoutModifierNode {
        override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
            val placeable = measurable.measure(constraints)
            width = placeable.width.toFloat()
            height = placeable.height.toFloat()
            return layout(placeable.width, placeable.height) {
                // Not placeRelative: deltas are in screen space, so RTL must not mirror the offset.
                placeable.placeWithLayer(0, 0) {
                    translationX = x.offset
                    translationY = y.offset
                }
            }
        }

        override fun onDetach() {
            x.reset()
            y.reset()
        }
    }
}

/** Creates a [RubberBandOverscrollEffect] per scroll container; provide it via LocalOverscrollFactory. */
class RubberBandOverscrollFactory(private val maxBounceDistancePx: Float) : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = RubberBandOverscrollEffect(maxBounceDistancePx)

    override fun equals(other: Any?): Boolean =
        other is RubberBandOverscrollFactory && other.maxBounceDistancePx == maxBounceDistancePx

    override fun hashCode(): Int = maxBounceDistancePx.hashCode()
}

/** The app-wide factory, or null (no overscroll at all) when [enabled] is false. */
@Composable
fun rememberRubberBandOverscrollFactory(enabled: Boolean = true): OverscrollFactory? {
    val maxBouncePx = with(LocalDensity.current) { MotionTokens.RubberBandMaxBounce.toPx() }
    return remember(enabled, maxBouncePx) { if (enabled) RubberBandOverscrollFactory(maxBouncePx) else null }
}

/**
 * Elastic edge for a container whose list may be too short to scroll: scroll containers only
 * report overscroll when they can scroll, so this catches what the list inside leaves over and
 * stretches the content just the same. Long lists keep using their own effect.
 */
@Composable
fun Modifier.elastic(): Modifier {
    val effect = rememberOverscrollEffect() ?: return this
    val scope = rememberCoroutineScope()
    val connection = remember(effect, scope) { ElasticConnection(effect, scope) }
    return overscroll(effect).nestedScroll(connection)
}

private class ElasticConnection(private val effect: OverscrollEffect, private val scope: CoroutineScope) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput) (effect as? RubberBandOverscrollEffect)?.relax(available) ?: Offset.Zero else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput && (available != Offset.Zero || effect.isInProgress)) {
            effect.applyToScroll(available, source) { Offset.Zero }
        } else {
            Offset.Zero
        }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!effect.isInProgress) return Velocity.Zero
        // The spring back takes part of the velocity; only the rest goes to the list inside, and
        // the effect does not bounce again with what the list flings.
        val forContent = CompletableDeferred<Velocity>()
        scope.launch {
            effect.applyToFling(available) { velocity ->
                forContent.complete(velocity)
                velocity
            }
        }
        return available - forContent.await()
    }
}
