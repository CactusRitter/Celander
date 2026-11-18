package com.celander.app.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalGlanceId
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider as DayNightColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.celander.app.R
import com.celander.app.calendar.data.CalendarContentRepository
import com.celander.app.calendar.data.CalendarsRepository
import com.celander.app.core.time.TimeSource
import com.celander.app.ui.CalendarColor
import com.ce.kit.ui.glass.GlassTokens
import com.celander.app.ui.sunday
import com.ce.kit.ui.theme.glassColors
import com.ce.kit.ui.theme.glassScheme
import com.celander.app.settings.AppSettings
import com.celander.app.settings.SettingsRepository
import com.ce.kit.ui.theme.ThemeMode
import com.celander.app.task.TaskRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Widgets are created by the system, not by Hilt; they reach the app's singletons through this. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun content(): CalendarContentRepository

    fun calendars(): CalendarsRepository

    fun tasks(): TaskRepository

    fun settings(): SettingsRepository

    fun time(): TimeSource
}

internal fun Context.widgetDeps(): WidgetEntryPoint = EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java)

/** Sizes and type of the widgets, modeled on the Samsung calendar widgets. */
internal object WidgetTokens {
    val corner = 24.dp
    val padding = 16.dp
    val headerHeight = 48.dp
    val iconTarget = 40.dp
    val icon = 22.dp
    val addButton = 30.dp
    val addIcon = 18.dp
    val bar = 4.dp
    val barHeight = 16.dp
    val check = 18.dp
    val checkTarget = 36.dp
    val titleSize = 17.sp
    val titleSizeNarrow = 15.sp
    val subtitleSize = 13.sp
    val bodySize = 14.sp
    val smallSize = 12.sp
    val sectionSize = 13.sp
}

/**
 * Colors of a widget for the app's theme setting. Following the system, every color is a day/night
 * pair the launcher switches by itself; with a fixed theme it is a single color. Accent and the
 * widget transparency (apart from the app's glass) come from Settings › Design.
 */
internal class WidgetColors(settings: AppSettings) {
    private val mode = settings.themeMode
    private val glassLight = glassColors(dark = false, settings.transparency, settings.accentPalette)
    private val glassDark = glassColors(dark = true, settings.transparency, settings.accentPalette)
    private val light = glassScheme(dark = false, settings.accentPalette, glassLight)
    private val dark = glassScheme(dark = true, settings.accentPalette, glassDark)

    private fun pair(day: Color, night: Color): ColorProvider = when (mode) {
        ThemeMode.SYSTEM -> DayNightColorProvider(day = day, night = night)
        ThemeMode.LIGHT -> ColorProvider(day)
        ThemeMode.DARK -> ColorProvider(night)
    }

    private val transparency = settings.widgetTransparency


    /** The sheen from above of the app's glass, for the day or night card like [card]. */
    val glassSheen: Int = when (mode) {
        ThemeMode.SYSTEM -> R.drawable.widget_glass
        ThemeMode.LIGHT -> R.drawable.widget_glass_light
        ThemeMode.DARK -> R.drawable.widget_glass_dark
    }

    val card = pair(
        WidgetCardLight.copy(alpha = GlassTokens.widgetAlpha(transparency, dark = false)),
        WidgetCardDark.copy(alpha = GlassTokens.widgetAlpha(transparency, dark = true)),
    )
    val ink = pair(light.onSurface, dark.onSurface)
    val secondary = pair(light.onSurfaceVariant, dark.onSurfaceVariant)
    val accent = pair(light.primary, dark.primary)
    val onAccent = pair(light.onPrimary, dark.onPrimary)
    val sunday = pair(light.sunday, dark.sunday)
    val danger = pair(light.error, dark.error)
    val divider = pair(light.onSurface.copy(alpha = 0.08f), dark.onSurface.copy(alpha = 0.08f))

    /** Tints of the card's glass masks. */
    val rim = pair(WidgetGlass.rimColor(glassLight.borderTop, dark = false), WidgetGlass.rimColor(glassDark.borderTop, dark = true))

    fun calendar(colorKey: String): ColorProvider = CalendarColor.fromKey(colorKey).let { pair(it.base(dark = false), it.base(dark = true)) }

    /** A calendar's soft tint, as behind the month chips in the app; text on it stays [ink]. */
    fun calendarSoft(colorKey: String): ColorProvider = CalendarColor.fromKey(colorKey).let {
        pair(it.container(dark = false, light.surface), it.container(dark = true, dark.surface))
    }

    val transparent = ColorProvider(Color.Transparent)

    /**
     * The card before Android 12, which cannot switch colors between day and night by itself: the
     * color for the theme the system shows now, with its opacity.
     */
    fun legacyCard(context: Context): Color {
        val dark = when (mode) {
            ThemeMode.SYSTEM -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        return (if (dark) WidgetCardDark else WidgetCardLight).copy(alpha = GlassTokens.widgetAlpha(transparency, dark))
    }
}

internal val WidgetCardLight = Color.White
internal val WidgetCardDark = Color(0xFF121318)

/**
 * The glass card every widget sits on, as in the app: a translucent fill, a sheen from above and a
 * light rim ([WidgetGlass]). It fills the whole widget: no shadow is drawn around it, because a widget
 * cannot blur what is behind it and a shadow outside the card shows as a dark frame on light
 * wallpapers. Before Android 12 there is no rounded clipping, so a rounded drawable with the theme's
 * card color is used instead.
 */
@Composable
internal fun WidgetCard(colors: WidgetColors, modifier: GlanceModifier = GlanceModifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val surface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        GlanceModifier.background(colors.card).cornerRadius(WidgetTokens.corner)
    } else {
        GlanceModifier.background(ImageProvider(R.drawable.widget_card), colorFilter = ColorFilter.tint(ColorProvider(colors.legacyCard(context))))
    }
    val fill = GlanceModifier.fillMaxSize()
    Box(modifier.fillMaxSize().appWidgetBackground().then(surface)) {
        Image(ImageProvider(colors.glassSheen), contentDescription = null, modifier = fill, contentScale = ContentScale.FillBounds)
        Image(ImageProvider(R.drawable.widget_rim), contentDescription = null, modifier = fill, contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(colors.rim))
        content()
    }
}

/**
 * Centered heading with an optional second line. [leading] and [trailing] get the same width on
 * both sides ([sideSlots] icon buttons each), so the heading stays in the middle. Grids pass a fixed
 * [height] because they size their drawing by it; lists let the header grow with the font.
 */
@Composable
internal fun WidgetHeader(
    title: String,
    colors: WidgetColors,
    onTitle: Action,
    sideSlots: Int,
    subtitle: String? = null,
    height: Dp? = null,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    val narrow = LocalSize.current.width < NARROW_WIDGET
    val side = WidgetTokens.iconTarget * sideSlots
    val rowModifier = if (height != null) GlanceModifier.fillMaxWidth().height(height) else GlanceModifier.fillMaxWidth().padding(vertical = 4.dp)
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Row(GlanceModifier.width(side), verticalAlignment = Alignment.CenterVertically) { leading() }
        Column(GlanceModifier.defaultWeight().clickable(onTitle), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                style = TextStyle(
                    color = colors.ink,
                    fontSize = if (narrow) WidgetTokens.titleSizeNarrow else WidgetTokens.titleSize,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = TextStyle(color = colors.secondary, fontSize = WidgetTokens.subtitleSize, textAlign = TextAlign.Center),
                    maxLines = 1,
                )
            }
        }
        Row(GlanceModifier.width(side), horizontalAlignment = Alignment.End, verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}

/** Header height for the drawn grids: one title line, grown with the font scale. */
internal fun gridHeaderHeight(fontScale: Float): Dp = maxOf(WidgetTokens.headerHeight, (WidgetTokens.titleSize.value * fontScale * TITLE_LINE + HEADER_PADDING).dp)

private const val TITLE_LINE = 1.35f
private const val HEADER_PADDING = 8f
private val NARROW_WIDGET = 230.dp

@Composable
internal fun WidgetIconButton(@DrawableRes icon: Int, description: String, tint: ColorProvider, onClick: Action) {
    Box(
        GlanceModifier.size(WidgetTokens.iconTarget).clickable(onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Image(ImageProvider(icon), contentDescription = null, modifier = GlanceModifier.size(WidgetTokens.icon), colorFilter = ColorFilter.tint(tint))
    }
}

/** The round "+" of the Samsung widgets, in the app color. */
@Composable
internal fun WidgetAddButton(description: String, colors: WidgetColors, onClick: Action) {
    Box(
        GlanceModifier.size(WidgetTokens.iconTarget).clickable(onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(ImageProvider(R.drawable.widget_circle), contentDescription = null, modifier = GlanceModifier.size(WidgetTokens.addButton), colorFilter = ColorFilter.tint(colors.accent))
            Image(ImageProvider(R.drawable.widget_ic_add), contentDescription = null, modifier = GlanceModifier.size(WidgetTokens.addIcon), colorFilter = ColorFilter.tint(colors.onAccent))
        }
    }
}
