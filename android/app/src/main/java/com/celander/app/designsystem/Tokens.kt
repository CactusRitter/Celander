package com.celander.app.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale. Screens use these instead of literal dp values. */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

object Radii {
    val chip = 6.dp
    val cell = 12.dp
    val card = 18.dp
    val sheet = 28.dp
    val pill = 30.dp
}

/** One layer of a drop shadow; [alpha] scales the theme's shadow color. */
data class ShadowLayer(val blur: Dp, val offsetY: Dp, val alpha: Float)

/**
 * Elevation scale. Each level pairs a tight contact shadow, which gives the edge its pop, with a wide
 * ambient one that lifts the whole surface off the background.
 */
object Elevation {
    /** Cards lifted off the content, like the day list under the month grid. */
    val raised = listOf(ShadowLayer(blur = 3.dp, offsetY = 1.dp, alpha = 0.7f), ShadowLayer(blur = 22.dp, offsetY = 10.dp, alpha = 0.9f))

    /** A small part lifted off its own control, like the selected segment; a hint of depth, no more. */
    val lifted = listOf(ShadowLayer(blur = 2.dp, offsetY = 1.dp, alpha = 0.6f), ShadowLayer(blur = 8.dp, offsetY = 3.dp, alpha = 0.55f))

    /** Controls floating above everything: pill, round buttons, snackbar, sheets. */
    val floating = listOf(ShadowLayer(blur = 4.dp, offsetY = 2.dp, alpha = 0.7f), ShadowLayer(blur = 30.dp, offsetY = 14.dp, alpha = 1f))
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

/**
 * Glass derived from the transparency picked in Settings › Design: 0 means solid surfaces without
 * blur, 1 fully clear glass. Every opacity runs linearly from opaque to its value at the default
 * (which keeps text at WCAG AA, see [opacity]) and from there to nothing at 1, so the default matches
 * the former "standard" glass and 0.4 the former "subtle" one. The blur itself is fixed.
 */
object GlassTokens {
    const val DEFAULT_TRANSPARENCY = 0.65f
    const val SUBTLE_TRANSPARENCY = 0.4f
    const val NOISE = 0.03f

    /** The same blur on every glass surface; the transparency only changes how much shows through. */
    val blur = 25.dp

    fun isSolid(transparency: Float): Boolean = transparency <= 0f

    fun blurRadius(transparency: Float): Dp = if (isSolid(transparency)) 0.dp else blur

    /**
     * Opacity of glass fills. Without blur (API < 31) surfaces stay much fuller up to the default so
     * text remains readable on the plain background.
     */
    fun fillAlpha(transparency: Float, dark: Boolean, blurActive: Boolean): Float = when {
        !blurActive -> opacity(transparency, if (dark) 0.870f else 0.883f)
        else -> opacity(transparency, if (dark) 0.519f else 0.584f)
    }

    /** Sheets carry forms and longer text, so they let less of the content behind them through. */
    fun sheetFillAlpha(transparency: Float, dark: Boolean, blurActive: Boolean): Float = when {
        !blurActive -> opacity(transparency, if (dark) 0.935f else 0.948f)
        else -> opacity(transparency, if (dark) 0.805f else 0.844f)
    }

    /**
     * Opacity of home screen widgets, from their own widget transparency (not the app's glass). They
     * cannot blur the wallpaper; up to the default, secondary text stays at WCAG AA on a black or
     * white wallpaper, at 1 only the glass rim, sheen and shadow remain.
     */
    fun widgetAlpha(transparency: Float, dark: Boolean): Float = opacity(transparency, if (dark) 0.857f else 0.870f)

    /** Opacity of translucent tiles inside content (cells, cards) that never blur. */
    fun tileAlpha(transparency: Float, dark: Boolean): Float = opacity(transparency, if (dark) 0.480f else 0.5645f)

    /**
     * Opaque at 0, [atDefault] at [DEFAULT_TRANSPARENCY], nothing at 1, linear in between. Above the
     * default the user trades legibility for a clear view, so 100 % really means fully transparent.
     */
    private fun opacity(transparency: Float, atDefault: Float): Float {
        val t = transparency.coerceIn(0f, 1f)
        return if (t <= DEFAULT_TRANSPARENCY) {
            1f - t / DEFAULT_TRANSPARENCY * (1f - atDefault)
        } else {
            atDefault * (1f - t) / (1f - DEFAULT_TRANSPARENCY)
        }
    }
}
