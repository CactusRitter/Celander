package com.celander.app.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

/**
 * Size of the app's window in dp. On tablets, foldables and in split screen it says more than the
 * device's orientation, so layouts decide by it: side by side when wider than tall, more margin and
 * readable line lengths once the window is wider than a phone.
 */
@Immutable
data class WindowLayout(val width: Dp, val height: Dp) {
    val widthClass: WidthClass = when {
        width < WindowTokens.mediumWidth -> WidthClass.COMPACT
        width < WindowTokens.expandedWidth -> WidthClass.MEDIUM
        else -> WidthClass.EXPANDED
    }

    /** Tablet, unfolded foldable or a wide split-screen window. */
    val isWide: Boolean get() = widthClass != WidthClass.COMPACT

    /** Wider than tall: panes go next to each other instead of one above the other. */
    val isLandscape: Boolean get() = width > height

    /** Margin wide windows add around the home views, on top of the views' own padding. */
    val extraGutter: Dp get() = if (isWide) Spacing.l else 0.dp

    companion object {
        /** Used before the window is measured and in previews: a typical phone. */
        val Phone = WindowLayout(411.dp, 891.dp)
    }
}

val LocalWindowLayout = staticCompositionLocalOf { WindowLayout.Phone }

/**
 * Horizontal padding that centres content of at most [maxWidth] in a container of [containerWidth],
 * never less than [minPadding]. Lists keep scrolling over their full width; only the rows stay at a
 * readable width.
 */
fun centeringPadding(containerWidth: Dp, maxWidth: Dp, minPadding: Dp): Dp = maxOf(minPadding, (containerWidth - maxWidth) / 2)
