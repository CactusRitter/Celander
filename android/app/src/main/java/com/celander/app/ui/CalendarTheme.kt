package com.celander.app.ui

import com.ce.kit.ui.*
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


/** Sundays and holidays in the month grid. */
val ColorScheme.sunday: Color get() = if (isDark) Color(0xFFFF8F86) else Color(0xFFBF3B35)

/** The line of the current time in the day view and the agenda. */
val ColorScheme.nowLine: Color get() = if (isDark) Color(0xFFFF6B61) else Color(0xFFD9443C)

/** The disc of today in the grids, and the colour of the number on it. */
val ColorScheme.today: Color get() = onSurface
val ColorScheme.onToday: Color get() = surface

/** Done, saved, connected. */
val ColorScheme.success: Color get() = if (isDark) Color(0xFF6BD48A) else Color(0xFF237A41)


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
    val touchTarget = com.ce.kit.ui.CeSizes.touchTarget
    val pillHeight = 60.dp
    val pillSegment = 48.dp
    val pillAddButton = 44.dp
    val topBar = 56.dp
    val hourHeight = 60.dp
    val iconSmall = com.ce.kit.ui.CeSizes.iconSmall
    val icon = com.ce.kit.ui.CeSizes.icon
    val dot = 5.dp
    val hairline = com.ce.kit.ui.CeSizes.hairline

    /** Sheets, dialogs' larger cousins and settings pages never get wider than this. */
    val maxFormWidth = com.ce.kit.ui.CeSizes.maxFormWidth

    /** Lists (agenda, tasks, search) keep their rows this wide at most and centre them in wide windows. */
    val maxListWidth = 720.dp

    /** The month's day list beside the grid stops growing here; the grid takes the rest. */
    val maxDayPanelWidth = 400.dp

    /** The day view shows the whole week once seven timeline columns get at least this wide. */
    val minDayColumnWidth = 88.dp

    /** A sheet floating in a wide window stays a card instead of growing to the full height. */
    val maxFloatingSheetHeight = com.ce.kit.ui.CeSizes.maxFloatingSheetHeight
}


