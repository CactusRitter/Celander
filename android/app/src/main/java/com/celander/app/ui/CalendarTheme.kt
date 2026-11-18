package com.celander.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// What only a calendar needs, written on top of the common design system (ui/theme, ui/glass): colours that Material has no name for,
// motion that can be reduced, measures of the grids and the window layout. Nothing here draws glass.

/** Whether this colour scheme is a dark one (the look may be dark without the phone being dark: AMOLED). */
val ColorScheme.isDark: Boolean get() = background.luminance() < 0.5f

/** Sundays and holidays in the month grid. */
val ColorScheme.sunday: Color get() = if (isDark) Color(0xFFFF8F86) else Color(0xFFBF3B35)

/** The line of the current time in the day view and the agenda. */
val ColorScheme.nowLine: Color get() = if (isDark) Color(0xFFFF6B61) else Color(0xFFD9443C)

/** The disc of today in the grids, and the colour of the number on it. */
val ColorScheme.today: Color get() = onSurface
val ColorScheme.onToday: Color get() = surface

/** Done, saved, connected. */
val ColorScheme.success: Color get() = if (isDark) Color(0xFF6BD48A) else Color(0xFF237A41)

/**
 * Motion settings. Containers move on springs, so movements can be interrupted, keep their velocity
 * and settle naturally; fades stay short tweens. With [reduced] every movement becomes a short fade
 * or snaps.
 */
@Immutable
data class MotionConfig(val reduced: Boolean) {
    /** State changes inside a component (expand, resize, move, color). */
    fun <T> standard(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(MotionTokens.STANDARD_DAMPING, MotionTokens.STANDARD_STIFFNESS)

    /** Containers appearing: a soft spring with a hint of overshoot. */
    fun <T> enter(): FiniteAnimationSpec<T> =
        if (reduced) tween(MotionTokens.SHORT, easing = FastOutSlowInEasing) else spring(MotionTokens.ENTER_DAMPING, MotionTokens.ENTER_STIFFNESS)

    /** Containers leaving: a stiffer spring without overshoot, so exits finish sooner than entries. */
    fun <T> exit(): FiniteAnimationSpec<T> =
        if (reduced) tween((MotionTokens.SHORT * MotionTokens.EXIT_FACTOR).toInt(), easing = MotionTokens.ExitEasing) else spring(Spring.DampingRatioNoBouncy, MotionTokens.EXIT_STIFFNESS)

    /** Panels following a gesture: quick and without overshoot, so no edge ever shows past its place. */
    fun <T> page(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(Spring.DampingRatioNoBouncy, MotionTokens.PAGE_STIFFNESS)

    /** Sliding from one page to the next; a gesture (predictive back) can still scrub it. */
    fun <T> navigation(): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(MotionTokens.NAVIGATION, easing = FastOutSlowInEasing)

    fun <T> fade(durationMillis: Int = MotionTokens.SHORT): FiniteAnimationSpec<T> = tween(durationMillis)

    fun <T> sheetSpring(): FiniteAnimationSpec<T> =
        if (reduced) tween(MotionTokens.SHORT) else spring(MotionTokens.SHEET_DAMPING, MotionTokens.SHEET_STIFFNESS)

    fun <T> bouncy(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)

    /** A section that opens in place. Reduced: a short fade, the space appears at once. */
    fun expandIn(): EnterTransition = if (reduced) fadeIn(tween(MotionTokens.SHORT)) else fadeIn(enter()) + expandVertically(enter())

    fun shrinkOut(): ExitTransition = if (reduced) fadeOut(tween(MotionTokens.SHORT)) else fadeOut(exit()) + shrinkVertically(exit())

    /** Floating content entering from below (or above with a negative [fraction]). Reduced: fade only. */
    fun riseIn(fraction: Int = 3): EnterTransition =
        if (reduced) fadeIn(tween(MotionTokens.SHORT)) else fadeIn(enter()) + slideInVertically(enter()) { it / fraction }

    fun sinkOut(fraction: Int = 3): ExitTransition =
        if (reduced) fadeOut(tween(MotionTokens.SHORT)) else fadeOut(exit()) + slideOutVertically(exit()) { it / fraction }

    /** Delay for the n-th item of a list that appears, capped so long lists do not drag. */
    fun staggerDelay(index: Int): Int = if (reduced) 0 else minOf(index, MotionTokens.STAGGER_MAX_ITEMS) * MotionTokens.STAGGER
}

val LocalMotion = staticCompositionLocalOf { MotionConfig(reduced = false) }

/** Compact text for event chips inside the month grid. */
val ChipTextStyle = TextStyle(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium)

/**
 * Provides what the calendar screens read besides the theme: reduced motion, the window layout and the elastic overscroll.
 * Goes inside `CeTheme`.
 */
@Composable
fun CalendarEnvironment(reduceMotion: Boolean, content: @Composable () -> Unit) {
    val windowSize = LocalWindowInfo.current.containerDpSize
    // Unmeasured (zero) before the first layout; the phone default avoids a flash of a wide layout.
    val window = remember(windowSize) {
        if (windowSize.width.value > 0f && windowSize.height.value > 0f) WindowLayout(windowSize.width, windowSize.height) else WindowLayout.Phone
    }
    CompositionLocalProvider(
        LocalMotion provides MotionConfig(reduceMotion),
        LocalWindowLayout provides window,
        // Elastic edges on every scroll container; none at all when motion is reduced.
        LocalOverscrollFactory provides rememberRubberBandOverscrollFactory(enabled = !reduceMotion),
        content = content,
    )
}

/** Text in dense grids (month, week strip, timeline, year) grows at most this much; lists grow fully. */
const val MAX_GRID_FONT_SCALE = 1.3f

/**
 * Dense calendar grids have fixed cell sizes, so a 200 % system font would cut their numbers off.
 * Their text scales up to [MAX_GRID_FONT_SCALE]; lists, sheets and settings follow the full scale.
 */
@Composable
fun LimitedFontScale(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (density.fontScale <= MAX_GRID_FONT_SCALE) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides Density(density.density, MAX_GRID_FONT_SCALE), content = content)
    }
}

object Sizes {
    val touchTarget = 48.dp
    val pillHeight = 60.dp
    val pillSegment = 48.dp
    val pillAddButton = 44.dp
    val topBar = 56.dp
    val hourHeight = 60.dp
    val iconSmall = 16.dp
    val icon = 22.dp
    val dot = 5.dp
    val hairline = 1.dp

    /** Sheets, dialogs' larger cousins and settings pages never get wider than this. */
    val maxFormWidth = 640.dp

    /** Lists (agenda, tasks, search) keep their rows this wide at most and centre them in wide windows. */
    val maxListWidth = 720.dp

    /** The month's day list beside the grid stops growing here; the grid takes the rest. */
    val maxDayPanelWidth = 400.dp

    /** The day view shows the whole week once seven timeline columns get at least this wide. */
    val minDayColumnWidth = 88.dp

    /** A sheet floating in a wide window stays a card instead of growing to the full height. */
    val maxFloatingSheetHeight = 860.dp
}

/**
 * Window size classes (Material breakpoints). Layouts go by the window, not the device's
 * orientation, so tablets, foldables and split screen get the layout that fits their space.
 */
object WindowTokens {
    val mediumWidth = 600.dp
    val expandedWidth = 840.dp

    /** Below this height (phones in landscape) every bit of height counts: no floating sheets. */
    val mediumHeight = 480.dp
}

/**
 * Springs for everything that moves, durations and easings for fades. Entering containers spring
 * softly with a little overshoot; exits use a stiffer spring without overshoot.
 */
object MotionTokens {
    const val SHORT = 150
    const val MEDIUM = 220
    const val LONG = 300
    const val EXIT_FACTOR = 0.65f
    const val STANDARD_DAMPING = 0.85f
    const val STANDARD_STIFFNESS = 450f
    const val ENTER_DAMPING = 0.78f
    const val ENTER_STIFFNESS = 320f
    const val EXIT_STIFFNESS = 700f

    /** The month's day list following the finger: fast, critically damped. */
    const val PAGE_STIFFNESS = 900f

    /**
     * Sliding between pages (home, settings): time-based and slow to start, so a heavy first frame
     * of the new page does not swallow the movement the way it would a front-loaded spring.
     */
    const val NAVIGATION = 400

    /** Resistance of the elastic overscroll (UIScrollView's constant): the further, the stiffer. */
    const val RUBBER_BAND_RESISTANCE = 0.55f
    const val RUBBER_BAND_STIFFNESS = 400f

    /** A fling bounces at most this share of its container and at most [RubberBandMaxBounce]. */
    const val RUBBER_BAND_MAX_BOUNCE_FRACTION = 0.2f
    val RubberBandMaxBounce = 120.dp

    /** How far a sheet pulled past fully open can stretch. */
    val SheetStretch = 48.dp

    /** A sheet let go at least this fast (per second) moves on to its next height or closes. */
    val SheetFlingVelocity = 500.dp

    /** Swipe up on the month this far, or flick at this speed per second, to open the day list. */
    val PanelSwipeThreshold = 56.dp
    val PanelSwipeVelocity = 900.dp
    const val STAGGER = 35
    const val STAGGER_MAX_ITEMS = 8
    const val SHEET_DAMPING = 0.86f
    const val SHEET_STIFFNESS = 380f
    const val PRESS_SCALE = 0.97f
    const val ZOOM_IN_SCALE = 0.92f
    const val ZOOM_OUT_SCALE = 1.06f

    val EnterEasing: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    val ExitEasing: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
}

