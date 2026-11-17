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
import com.celander.app.calendar.device.DeviceCalendarSource
import com.celander.app.core.time.TimeSource
import com.celander.app.designsystem.CalendarColor
import com.celander.app.designsystem.GlassTokens
import com.celander.app.designsystem.celanderColors
import com.celander.app.settings.AppSettings
import com.celander.app.settings.SettingsRepository
import com.celander.app.settings.ThemeMode
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

    fun device(): DeviceCalendarSource
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
    private val light = celanderColors(dark = false, settings.accentHue)
    private val dark = celanderColors(dark = true, settings.accentHue)

    private fun pair(day: Color, night: Color): ColorProvider = when (mode) {
        ThemeMode.SYSTEM -> DayNightColorProvider(day = day, night = night)
        ThemeMode.LIGHT -> ColorProvider(day)
        ThemeMode.DARK -> ColorProvider(night)
    }

    private val transparency = settings.widgetTransparency

    /** The wallpaper shows blurred behind the card when it is placed (see [WallpaperBlur]). */
    val blur = settings.widgetBlur && WallpaperBlur.supported

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
    val ink = pair(light.textPrimary, dark.textPrimary)
    val secondary = pair(light.textSecondary, dark.textSecondary)
    val accent = pair(light.accent, dark.accent)
    val onAccent = pair(light.onAccent, dark.onAccent)
    val sunday = pair(light.sunday, dark.sunday)
    val danger = pair(light.danger, dark.danger)
    val divider = pair(light.divider, dark.divider)

    /** Tints of the card's glass masks, the app's glass edge and shadow. */
    val rim = pair(WidgetGlass.rimColor(light.glassEdgeLight, dark = false), WidgetGlass.rimColor(dark.glassEdgeLight, dark = true))
    val shadow = pair(light.shadow, dark.shadow)

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
 * The glass card every widget sits on, as in the app: a translucent fill, a sheen from above, a light
 * rim and a soft shadow around it ([WidgetGlass]). With the blur on, the wallpaper shows blurred
 * under the fill once the card was tapped to align it ([WallpaperBlur]). The card sits
 * [WidgetElevation] inside the widget so the shadow has room; its content sees the card's size as
 * [LocalSize]. Before Android 12 there is no rounded clipping, so a rounded drawable with the theme's
 * card color is used instead.
 */
@Composable
internal fun WidgetCard(colors: WidgetColors, modifier: GlanceModifier = GlanceModifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val size = LocalSize.current
    val density = context.resources.displayMetrics.density
    val memory = rememberPictureMemory(context)
    val glass = remember(size, density, memory) { WidgetGlass.render(size, density, memory) }
    val card = WidgetGlass.cardSize(size)
    val blur = rememberCardBlur(colors.blur, card)
    val surface = when {
        blur is CardBlur.Shown -> GlanceModifier.cornerRadius(WidgetTokens.corner)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> GlanceModifier.background(colors.card).cornerRadius(WidgetTokens.corner)
        else -> GlanceModifier.background(ImageProvider(R.drawable.widget_card), colorFilter = ColorFilter.tint(ColorProvider(colors.legacyCard(context))))
    }
    val fill = GlanceModifier.fillMaxSize()
    Box(modifier.fillMaxSize()) {
        Image(ImageProvider(glass.shadow), contentDescription = null, modifier = fill, contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(colors.shadow))
        Box(fill.padding(start = WidgetElevation.side, top = WidgetElevation.top, end = WidgetElevation.side, bottom = WidgetElevation.bottom)) {
            Box(fill.appWidgetBackground().then(surface)) {
                if (blur is CardBlur.Shown) {
                    Image(ImageProvider(blur.picture), contentDescription = null, modifier = fill, contentScale = ContentScale.FillBounds)
                    Box(fill.background(colors.card)) {}
                }
                Image(ImageProvider(colors.glassSheen), contentDescription = null, modifier = fill, contentScale = ContentScale.FillBounds)
                Image(ImageProvider(glass.rim), contentDescription = null, modifier = fill, contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(colors.rim))
                CompositionLocalProvider(LocalSize provides card) { content() }
                if (blur is CardBlur.NeedsTap) AndroidRemoteViews(blur.views, fill)
            }
        }
    }
}

/** What the card shows of the wallpaper. */
private sealed interface CardBlur {
    /** No blur: turned off, not allowed, a live wallpaper or before Android 12. */
    data object Off : CardBlur

    /** The blurred wallpaper behind the card. */
    class Shown(val picture: Bitmap) : CardBlur

    /** The card's place is not known yet: a hint over it asks for a tap ([WidgetAlignReceiver]). */
    class NeedsTap(val views: RemoteViews) : CardBlur
}

@Composable
private fun rememberCardBlur(on: Boolean, card: DpSize): CardBlur {
    if (!on) return CardBlur.Off
    val context = LocalContext.current
    val glanceId = LocalGlanceId.current
    val widgetId = remember(glanceId) { runCatching { GlanceAppWidgetManager(context).getAppWidgetId(glanceId) }.getOrNull() } ?: return CardBlur.Off
    // Read so a running session recomposes after a tap or a new wallpaper; the values below come from files.
    val revision by WallpaperBlur.revision.collectAsState()
    val wallpaper = remember(revision) { WallpaperBlur.current(context) } ?: return CardBlur.Off
    val placement = remember(revision, widgetId, card) { WidgetPlacements.of(context).get(widgetId, card) }
    return remember(wallpaper, placement, widgetId, card) {
        val picture = placement
            ?.takeIf { it.screenWidth == wallpaper.screenWidth && it.screenHeight == wallpaper.screenHeight }
            ?.let { wallpaper.behind(it.card) }
        if (picture != null) {
            CardBlur.Shown(picture)
        } else {
            val views = RemoteViews(context.packageName, R.layout.widget_align).apply {
                setTextViewText(R.id.widget_align_text, context.getString(R.string.widget_blur_align))
                setOnClickPendingIntent(R.id.widget_align, WidgetAlignReceiver.tap(context, widgetId, card))
            }
            CardBlur.NeedsTap(views)
        }
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
