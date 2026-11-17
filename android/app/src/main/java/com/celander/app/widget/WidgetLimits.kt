package com.celander.app.widget

import android.appwidget.AppWidgetManager
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.os.BundleCompat
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/**
 * The sizes the launcher reports for a widget in its [options]; in `SizeMode.Exact` Glance renders
 * one layout per distinct size. From Android 12 on the launcher lists them (foldables up to four),
 * before that they are portrait and landscape. Empty when the launcher reports nothing.
 */
internal fun reportedSizes(options: Bundle): List<DpSize> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val sizes = BundleCompat.getParcelableArrayList(options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
        if (!sizes.isNullOrEmpty()) return sizes.map { DpSize(it.width.dp, it.height.dp) }.distinct()
    }
    val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
    val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
    val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
    val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
    if (minWidth == 0 || maxWidth == 0 || minHeight == 0 || maxHeight == 0) return emptyList()
    return listOf(DpSize(minWidth.dp, maxHeight.dp), DpSize(maxWidth.dp, minHeight.dp)).distinct()
}

/**
 * Rows a widget list holds at most. From Android 12 on a list goes to the launcher in one binder
 * transaction with all its rows, which fails above about a megabyte; full calendars would pass that.
 */
internal const val MAX_LIST_ROWS = 60

/** [MAX_LIST_ROWS] shared by the layouts of [layouts] sizes, as `SizeMode.Exact` sends a list for each. */
internal fun listRowLimit(layouts: Int): Int = (MAX_LIST_ROWS / layouts.coerceAtLeast(1)).coerceAtLeast(MIN_LIST_ROWS)

private const val MIN_LIST_ROWS = 10

/**
 * Item id of the "more" line: below the ids the lists derive from dates, hash codes and task ids,
 * above the range Glance reserves for items without an id.
 */
internal const val MORE_ROW_ID = -(1L shl 40)

/** A list cut for a widget: the [rows] it shows and how many entries did not fit ([hidden]). */
internal data class CappedRows<T>(val rows: List<T>, val hidden: Int)

/**
 * The first [max] of [rows]. [isEntry] tells entries from headings: a heading cut off from its
 * entries goes too, and only entries count as hidden.
 */
internal fun <T> capRows(rows: List<T>, max: Int, isEntry: (T) -> Boolean): CappedRows<T> {
    if (rows.size <= max) return CappedRows(rows, 0)
    val kept = rows.take(max).dropLastWhile { !isEntry(it) }
    return CappedRows(kept, rows.count(isEntry) - kept.count(isEntry))
}

/** The last line of a capped list: how many entries did not fit; a tap shows them in the app. */
@Composable
internal fun MoreLine(text: String, colors: WidgetColors, onClick: Action) {
    Text(
        text,
        style = TextStyle(color = colors.accent, fontSize = WidgetTokens.bodySize, fontWeight = FontWeight.Medium),
        maxLines = 1,
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 10.dp).clickable(onClick),
    )
}
