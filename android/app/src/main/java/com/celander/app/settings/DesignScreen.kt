package com.celander.app.settings

import android.app.WallpaperManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celander.app.R
import com.celander.app.core.ui.Formats
import com.celander.app.core.ui.rememberFormats
import com.celander.app.designsystem.AccentPalette
import com.celander.app.designsystem.AppBackground
import com.celander.app.designsystem.CalendarColor
import com.celander.app.designsystem.CelanderTheme
import com.celander.app.designsystem.ColorSwatch
import com.celander.app.designsystem.Elevation
import com.celander.app.designsystem.GlassConfig
import com.celander.app.designsystem.GlassSurface
import com.celander.app.designsystem.GlassTokens
import com.celander.app.designsystem.LocalGlass
import com.celander.app.designsystem.PillShape
import com.celander.app.designsystem.Radii
import com.celander.app.designsystem.Spacing
import com.celander.app.designsystem.celanderColors
import com.celander.app.designsystem.softShadow
import com.celander.app.widget.BlurAccess
import com.celander.app.widget.WallpaperBlur
import com.celander.app.widget.WidgetCardDark
import com.celander.app.widget.WidgetCardLight
import com.celander.app.widget.WidgetElevation
import com.celander.app.widget.WidgetGlass
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.roundToInt

/**
 * Settings › Design: light or dark, the app color, the transparency of the app's glass and of the
 * home screen widgets, and motion. Changes apply live; the widgets redraw once a value is let go.
 */
@Composable
fun DesignScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val current = settings ?: return
    val formats = rememberFormats(current.timeFormat)
    val appearanceTitle = stringResource(R.string.settings_section_appearance)
    val glassTitle = stringResource(R.string.design_section_glass)
    val motionTitle = stringResource(R.string.design_section_motion)

    SettingsScaffold(title = stringResource(R.string.settings_design), onBack = onBack) {
        settingsGroup(appearanceTitle) {
            SettingsChoiceRow(
                title = stringResource(R.string.settings_theme),
                options = listOf(
                    ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
                    ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
                    ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
                ),
                selected = current.themeMode,
                onSelect = { mode -> viewModel.update { it.copy(themeMode = mode) } },
            )
            SettingsDivider()
            AccentPicker(
                hue = current.accentHue,
                onPick = { hue -> viewModel.update { it.copy(accentHue = hue) } },
                onDrag = { hue -> viewModel.updateLive { it.copy(accentHue = hue) } },
            )
        }
        settingsGroup(glassTitle) {
            GlassControl(
                transparency = current.glassTransparency,
                formats = formats,
                onChange = { value -> viewModel.updateLive { it.copy(glassTransparency = value) } },
            )
            SettingsDivider()
            WidgetTransparencyControl(
                transparency = current.widgetTransparency,
                formats = formats,
                onCommit = { value -> viewModel.update { it.copy(widgetTransparency = value) } },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SettingsDivider()
                WidgetBlurRows(
                    on = current.widgetBlur,
                    onChange = { value -> viewModel.update { it.copy(widgetBlur = value) } },
                    onRefresh = viewModel::refreshWidgetBlur,
                    onRealign = viewModel::realignWidgets,
                )
            }
        }
        settingsGroup(motionTitle) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_reduce_motion),
                summary = stringResource(R.string.settings_reduce_motion_summary),
                checked = current.reduceMotion,
                onCheckedChange = { value -> viewModel.update { it.copy(reduceMotion = value) } },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentPicker(hue: Float, onPick: (Float) -> Unit, onDrag: (Float) -> Unit) {
    val colors = CelanderTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        Text(stringResource(R.string.design_color), style = CelanderTheme.typography.bodyLarge, color = colors.textPrimary)
        Text(
            stringResource(R.string.design_color_summary),
            style = CelanderTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 2.dp),
        )
        FlowRow(Modifier.fillMaxWidth().padding(top = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            val selected = AccentPalette.presetFor(hue)
            AccentPalette.presets.forEach { preset ->
                // Each swatch shows the accent exactly as it will look in the current light or dark theme.
                val swatch = remember(preset.hue, colors.isDark) { celanderColors(colors.isDark, preset.hue) }
                ColorSwatch(
                    color = swatch.accent,
                    selected = preset == selected,
                    label = stringResource(preset.name),
                    onClick = { onPick(preset.hue) },
                    checkTint = swatch.onAccent,
                )
            }
        }
        Text(
            stringResource(R.string.design_hue),
            style = CelanderTheme.typography.bodyMedium,
            color = colors.textPrimary,
            modifier = Modifier.padding(top = Spacing.m),
        )
        HueSlider(hue, onDrag)
    }
}

@Composable
private fun HueSlider(hue: Float, onChange: (Float) -> Unit) {
    val dark = CelanderTheme.colors.isDark
    val rainbow = remember(dark) { (0..RAINBOW_STOPS).map { celanderColors(dark, it * 360f / RAINBOW_STOPS).accent } }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val value = dragging ?: hue
    val thumb = remember(dark, value) { celanderColors(dark, value).accent }
    DesignSlider(
        value = value,
        valueRange = 0f..360f,
        onValueChange = {
            dragging = it
            onChange(it)
        },
        onValueChangeFinished = { dragging = null },
        thumbColor = thumb,
        label = stringResource(R.string.design_hue),
        state = value.roundToInt().let { degrees -> pluralStringResource(R.plurals.design_hue_value, degrees, degrees) },
    ) { _ ->
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(rainbow)))
    }
}

/** The app's glass: bars, sheets and backgrounds follow the finger while dragging. */
@Composable
private fun GlassControl(transparency: Float, formats: Formats, onChange: (Float) -> Unit) {
    val colors = CelanderTheme.colors
    var dragging by remember { mutableStateOf<Float?>(null) }
    TransparencySlider(
        title = stringResource(R.string.design_app_transparency),
        summary = stringResource(R.string.design_app_transparency_summary, formats.percent(0f)),
        value = dragging ?: transparency,
        formats = formats,
        onChange = {
            dragging = it
            onChange(it)
        },
        onFinished = { dragging = null },
    )
    Column(Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.s)) {
        GlassPreview()
        if (!GlassConfig.blurSupported) {
            Text(
                stringResource(R.string.design_no_blur),
                style = CelanderTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = Spacing.s),
            )
        }
    }
}

/**
 * The cards of all home screen widgets. Every saved change redraws each placed widget, so only the
 * preview follows the finger and the value is saved once it is let go.
 */
@Composable
private fun WidgetTransparencyControl(transparency: Float, formats: Formats, onCommit: (Float) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    // The let-go value until the saved one arrives, so the thumb does not jump back meanwhile.
    var committed by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(transparency) { committed = null }
    val value = dragging ?: committed ?: transparency
    TransparencySlider(
        title = stringResource(R.string.design_widget_transparency),
        summary = stringResource(R.string.design_widget_transparency_summary),
        value = value,
        formats = formats,
        onChange = { dragging = it },
        onFinished = {
            dragging?.let {
                committed = it
                onCommit(it)
            }
            dragging = null
        },
    )
    Box(Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.s)) {
        WidgetPreview(value)
    }
}

/**
 * The wallpaper blurred behind the widgets. Turning it on first asks for the access Android still
 * wants ([WallpaperBlur.missing]): "All files access" in the system settings, then photos through the
 * permission dialog; after a refusal for good Android shows no dialog any more, so the app's system
 * settings open. Once all is granted the blur turns on and the widgets redraw. While on, the
 * wallpaper can be blurred again after a change and the widgets realigned.
 */
@RequiresApi(Build.VERSION_CODES.S)
@Composable
private fun WidgetBlurRows(on: Boolean, onChange: (Boolean) -> Unit, onRefresh: () -> Unit, onRealign: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var missing by remember { mutableStateOf(WallpaperBlur.missing(context)) }
    var live by remember { mutableStateOf(false) }
    // Set while the system settings are open for this switch; coming back continues with the next step.
    var asked by rememberSaveable { mutableStateOf(false) }
    val enable = {
        onChange(true)
        onRefresh()
    }
    val openAppSettings = {
        asked = true
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        missing = WallpaperBlur.missing(context)
        when {
            missing.isEmpty() -> enable()
            // Refused for good: only the app's system settings can still allow it.
            !granted && activity?.shouldShowRequestPermissionRationale(WallpaperBlur.photoPermission) == false -> openAppSettings()
        }
    }
    val askNext = {
        when (missing.firstOrNull()) {
            null -> enable()
            BlurAccess.FILES -> {
                asked = true
                val app = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", context.packageName, null))
                runCatching { context.startActivity(app) }.recoverCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
            BlurAccess.PHOTOS -> request.launch(WallpaperBlur.photoPermission)
        }
    }
    LifecycleResumeEffect(Unit) {
        missing = WallpaperBlur.missing(context)
        live = WallpaperManager.getInstance(context).wallpaperInfo != null
        if (asked) {
            asked = false
            if (missing.firstOrNull() != BlurAccess.FILES) askNext()
        }
        onPauseOrDispose {}
    }
    val permitted = missing.isEmpty()
    SettingsSwitchRow(
        title = stringResource(R.string.design_widget_blur),
        checked = on && permitted,
        summary = stringResource(
            when {
                live -> R.string.design_widget_blur_live
                on && missing.firstOrNull() == BlurAccess.FILES -> R.string.design_widget_blur_no_access
                on && missing.firstOrNull() == BlurAccess.PHOTOS -> R.string.design_widget_blur_no_photos
                else -> R.string.design_widget_blur_summary
            },
        ),
        onCheckedChange = { value -> if (value) askNext() else onChange(false) },
    )
    if (on && permitted && !live) {
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.design_widget_blur_refresh),
            summary = stringResource(R.string.design_widget_blur_refresh_summary),
            onClick = onRefresh,
        )
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.design_widget_blur_realign),
            summary = stringResource(R.string.design_widget_blur_realign_summary),
            onClick = onRealign,
        )
    }
}

/** Title with the percentage, a summary and a 5 % step slider from solid to clearest. */
@Composable
private fun TransparencySlider(
    title: String,
    summary: String,
    value: Float,
    formats: Formats,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    val colors = CelanderTheme.colors
    val percent = formats.percent(value)
    Column(Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.l, top = Spacing.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = CelanderTheme.typography.bodyLarge, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text(percent, style = CelanderTheme.typography.bodyLarge.copy(fontFeatureSettings = TABULAR_FIGURES), color = colors.textSecondary)
        }
        Text(
            summary,
            style = CelanderTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 2.dp),
        )
        DesignSlider(
            value = value,
            valueRange = 0f..1f,
            steps = GLASS_STEPS,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            thumbColor = colors.accent,
            label = title,
            state = percent,
        ) { fraction ->
            Box(Modifier.fillMaxSize().background(colors.accentSoft)) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(colors.accent))
            }
        }
    }
}

/** Calendar-like bars under a glass pill, so the transparency is visible while dragging. */
@Composable
private fun GlassPreview() {
    val colors = CelanderTheme.colors
    val previewHaze = rememberHazeState()
    CompositionLocalProvider(LocalGlass provides LocalGlass.current.copy(hazeState = previewHaze)) {
        PreviewFrame(blurSource = previewHaze) {
            GlassSurface(shape = PillShape) {
                Text(
                    stringResource(R.string.design_preview),
                    style = CelanderTheme.typography.labelLarge,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.m),
                )
            }
        }
    }
}

/**
 * A widget card over the same bars, drawn as the widget draws it: widgets cannot blur what is behind
 * them, so it is their translucent fill with the sheen, light rim and shadow of [WidgetGlass], in the
 * light or dark card color they show now.
 */
@Composable
private fun WidgetPreview(transparency: Float) {
    val colors = CelanderTheme.colors
    val card = (if (colors.isDark) WidgetCardDark else WidgetCardLight).copy(alpha = GlassTokens.widgetAlpha(transparency, colors.isDark))
    val shape = RoundedCornerShape(Radii.card)
    val edge = WidgetGlass.rimColor(colors.glassEdgeLight, colors.isDark)
    val rim = Brush.linearGradient(*WidgetGlass.RIM.map { (stop, alpha) -> stop to edge.copy(alpha = edge.alpha * alpha) }.toTypedArray())
    PreviewFrame {
        Text(
            stringResource(R.string.design_preview),
            style = CelanderTheme.typography.labelLarge,
            color = colors.textPrimary,
            modifier = Modifier
                .softShadow(shape, colors.shadow, WidgetElevation.layers)
                .background(card, shape)
                .background(Brush.verticalGradient(0f to colors.sheen, WIDGET_SHEEN_END to Color.Transparent), shape)
                .border(WidgetElevation.rim, rim, shape)
                .padding(horizontal = Spacing.xl, vertical = Spacing.m),
        )
    }
}

/** Where the widget's sheen from above has faded out, as in its drawable. */
private const val WIDGET_SHEEN_END = 0.35f

/** The preview area: the app background with calendar bars (the source of [blurSource], if any), [content] on top. */
@Composable
private fun PreviewFrame(blurSource: HazeState? = null, content: @Composable BoxScope.() -> Unit) {
    val colors = CelanderTheme.colors
    Box(
        Modifier
            .padding(top = Spacing.s)
            .fillMaxWidth()
            .height(PreviewHeight)
            .clip(RoundedCornerShape(Radii.card))
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.matchParentSize().then(if (blurSource != null) Modifier.hazeSource(blurSource) else Modifier)) {
            AppBackground()
            Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                PreviewBars.forEach { (color, width) ->
                    Box(Modifier.fillMaxWidth(width).height(PreviewBarHeight).background(color.base(colors.isDark), RoundedCornerShape(Radii.chip)))
                }
            }
        }
        content()
    }
}

/** Slider with a rounded track drawn by [track] (given the filled fraction) and a raised round thumb. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesignSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    thumbColor: Color,
    label: String,
    state: String,
    steps: Int = 0,
    track: @Composable (fraction: Float) -> Unit,
) {
    val colors = CelanderTheme.colors
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = label
                stateDescription = state
            },
        thumb = {
            Box(
                Modifier
                    .size(ThumbSize)
                    .softShadow(CircleShape, colors.shadow, Elevation.raised)
                    .background(thumbColor, CircleShape)
                    .border(ThumbRing, Color.White, CircleShape),
            )
        },
        track = { sliderState ->
            val range = sliderState.valueRange
            val fraction = ((sliderState.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(TrackHeight).clip(PillShape)) { track(fraction) }
        },
    )
}

private const val RAINBOW_STOPS = 12

/** 5 % steps: 21 values from 0 to 100 %. */
private const val GLASS_STEPS = 19
/** Digits of equal width, so the percentage does not jitter while dragging. */
private const val TABULAR_FIGURES = "tnum"
private val ThumbSize = 26.dp
private val ThumbRing = 3.dp
private val TrackHeight = 10.dp
private val PreviewHeight = 112.dp
private val PreviewBarHeight = 18.dp
private val PreviewBars = listOf(
    CalendarColor.LAVENDER to 0.55f,
    CalendarColor.CORAL to 0.85f,
    CalendarColor.TEAL to 0.4f,
    CalendarColor.MUSTARD to 0.7f,
)
