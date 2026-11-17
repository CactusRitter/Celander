package com.celander.app.widget

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.unit.DpSize
import androidx.core.content.edit
import kotlin.math.roundToInt

/**
 * Where each widget's card sits on the screen, learned from one tap on it (see [WidgetAlignReceiver]):
 * a widget is never told its position. Kept per widget and card size, since portrait and landscape
 * place it differently. Only true for this device, so the file is left out of backups.
 */
internal class WidgetPlacements(private val prefs: SharedPreferences) {
    fun get(widgetId: Int, card: DpSize): Placement? = prefs.getString(key(widgetId, card), null)?.let(Placement::parse)

    fun set(widgetId: Int, card: DpSize, placement: Placement) = prefs.edit { putString(key(widgetId, card), placement.encode()) }

    /** Forgets every position; each widget asks for a tap again. */
    fun clear() = prefs.edit { clear() }

    private fun key(widgetId: Int, card: DpSize) = "$widgetId:${card.width.value.roundToInt()}x${card.height.value.roundToInt()}"

    companion object {
        /** File name; `widget_placements.xml` is excluded in the backup rules. */
        const val FILE = "widget_placements"

        fun of(context: Context) = WidgetPlacements(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}

/** A card at [card] in screen pixels on a [screenWidth] x [screenHeight] screen. */
internal data class Placement(val card: PixelRect, val screenWidth: Int, val screenHeight: Int) {
    fun encode(): String = listOf(card.left, card.top, card.right, card.bottom, screenWidth, screenHeight).joinToString(",")

    companion object {
        fun parse(value: String): Placement? {
            val v = value.split(",").mapNotNull { it.toIntOrNull() }.takeIf { it.size == FIELDS } ?: return null
            return Placement(PixelRect(v[0], v[1], v[2], v[3]), v[4], v[5])
        }

        private const val FIELDS = 6
    }
}
