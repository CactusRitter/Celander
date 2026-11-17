package com.celander.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives the tap that aligns a widget's blur. The launcher adds the tapped view's place on the
 * screen to the intent ([Intent.getSourceBounds]); the tapped view covers the card, so that is
 * where the card sits. Then every widget redraws with the wallpaper behind it.
 */
class WidgetAlignReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val bounds = intent.sourceBounds ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val card = DpSize(intent.getFloatExtra(EXTRA_CARD_WIDTH, 0f).dp, intent.getFloatExtra(EXTRA_CARD_HEIGHT, 0f).dp)
        val screen = WallpaperBlur.screenSize(context)
        WidgetPlacements.of(context).set(widgetId, card, Placement(PixelRect(bounds.left, bounds.top, bounds.right, bounds.bottom), screen.width(), screen.height()))
        WallpaperBlur.changed()
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WidgetUpdater.updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_CARD_WIDTH = "card_width"
        private const val EXTRA_CARD_HEIGHT = "card_height"

        /**
         * The tap for the card of [widgetId] at [card]. Mutable, because only then does the system
         * fill in the tapped view's bounds; the intent is explicit, so nothing else can be sent.
         */
        internal fun tap(context: Context, widgetId: Int, card: DpSize): PendingIntent {
            val intent = Intent(context, WidgetAlignReceiver::class.java)
                .setData("celander-widget://align/$widgetId/${card.width.value}/${card.height.value}".toUri())
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .putExtra(EXTRA_CARD_WIDTH, card.width.value)
                .putExtra(EXTRA_CARD_HEIGHT, card.height.value)
            return PendingIntent.getBroadcast(context, widgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        }
    }
}
