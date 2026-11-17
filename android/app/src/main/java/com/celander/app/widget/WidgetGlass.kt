package com.celander.app.widget

import androidx.compose.ui.unit.dp

/**
 * The glass edge of a widget card: a thin light rim, [R.drawable.widget_rim] tinted by [rimColor]. Widgets
 * can neither blur the wallpaper nor cast a shadow outside their own bounds without it showing as a dark
 * frame on light wallpapers, so the card fills the whole widget and its glass is the translucent fill, the
 * sheen from above and this edge.
 */
internal object WidgetGlass {
    /**
     * The rim's color for the app's glass edge [edge]: on a dark card the widget has no blur to set
     * its edge off, so the rim shines brighter there than in the app.
     */
    fun rimColor(edge: androidx.compose.ui.graphics.Color, dark: Boolean): androidx.compose.ui.graphics.Color =
        if (dark) edge.copy(alpha = DARK_RIM_ALPHA) else edge

    /** The in-app preview's rim strength from the top left corner to the bottom right one, as (position, alpha). */
    val RIM = listOf(0f to 1f, 0.4f to 0.35f, 0.65f to 0.27f, 1f to 0.7f)

    private const val DARK_RIM_ALPHA = 0.4f
}

/** The thin glass edge of the widget card. */
internal object WidgetElevation {
    val rim = 1.dp
}
