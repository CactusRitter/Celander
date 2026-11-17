package com.celander.app.designsystem

import android.os.Build
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState

@Immutable
data class CelanderColors(
    val isDark: Boolean,
    val background: Color,
    val washes: List<Color>,
    val surface: Color,
    val glassTint: Color,
    val glassEdgeLight: Color,
    val glassEdgeShade: Color,
    val tile: Color,
    val sheen: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val today: Color,
    val onToday: Color,
    val sunday: Color,
    val danger: Color,
    val success: Color,
    val divider: Color,
    val outline: Color,
    val shadow: Color,
    /** Segmented control: the recessed track and the raised pill of the selected option. */
    val segmentTrack: Color,
    val segmentThumb: Color,
    val segmentThumbEdge: Color,
    val scrim: Color,
    val nowLine: Color,
)

private val LightColors = CelanderColors(
    isDark = false,
    background = Color(0xFFF2F3F7),
    washes = listOf(Color(0xFFD9D3FF), Color(0xFFFFE2D0), Color(0xFFD3F0E3)),
    surface = Color(0xFFFFFFFF),
    glassTint = Color(0xFFFFFFFF),
    glassEdgeLight = Color.White.copy(alpha = 0.9f),
    glassEdgeShade = Color(0xFF15171C).copy(alpha = 0.08f),
    tile = Color(0xFFFFFFFF),
    sheen = Color.White.copy(alpha = 0.70f),
    textPrimary = Color(0xFF15171C),
    textSecondary = Color(0xFF4F5462),
    textTertiary = Color(0xFF6E7381),
    accent = Color(0xFF4A52D6),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFE1E3FF),
    today = Color(0xFF15171C),
    onToday = Color(0xFFFFFFFF),
    sunday = Color(0xFFBF3B35),
    danger = Color(0xFFC12A37),
    success = Color(0xFF237A41),
    divider = Color(0xFF15171C).copy(alpha = 0.08f),
    outline = Color(0xFF15171C).copy(alpha = 0.16f),
    shadow = Color(0xFF1D2340).copy(alpha = 0.20f),
    segmentTrack = Color(0xFF15171C).copy(alpha = 0.07f),
    segmentThumb = Color(0xFFFFFFFF),
    segmentThumbEdge = Color(0xFF15171C).copy(alpha = 0.06f),
    scrim = Color(0xFF10131A).copy(alpha = 0.28f),
    nowLine = Color(0xFFD9443C),
)

private val DarkColors = CelanderColors(
    isDark = true,
    background = Color(0xFF0E1015),
    washes = listOf(Color(0xFF2B3170), Color(0xFF0F4A47), Color(0xFF4A2550)),
    surface = Color(0xFF1A1D24),
    glassTint = Color(0xFF1E2129),
    glassEdgeLight = Color.White.copy(alpha = 0.20f),
    glassEdgeShade = Color.White.copy(alpha = 0.04f),
    tile = Color(0xFF1C1F27),
    sheen = Color.White.copy(alpha = 0.06f),
    textPrimary = Color(0xFFF2F3F7),
    textSecondary = Color(0xFFB7BBC7),
    textTertiary = Color(0xFF8E93A3),
    accent = Color(0xFF9BA3FF),
    onAccent = Color(0xFF12152E),
    accentSoft = Color(0xFF2C3160),
    today = Color(0xFFF2F3F7),
    onToday = Color(0xFF101217),
    sunday = Color(0xFFFF8F86),
    danger = Color(0xFFFF7A86),
    success = Color(0xFF6BD48A),
    divider = Color.White.copy(alpha = 0.08f),
    outline = Color.White.copy(alpha = 0.16f),
    shadow = Color.Black.copy(alpha = 0.65f),
    segmentTrack = Color.Black.copy(alpha = 0.28f),
    segmentThumb = Color(0xFF3B404D),
    segmentThumbEdge = Color.White.copy(alpha = 0.14f),
    scrim = Color.Black.copy(alpha = 0.50f),
    nowLine = Color(0xFFFF6B61),
)

/** The full palette for a theme and the accent hue picked in Settings › Design. */
fun celanderColors(dark: Boolean, accentHue: Float): CelanderColors = AccentPalette.colors(if (dark) DarkColors else LightColors, accentHue)

/** Glass configuration for the current composition. [hazeState] is set by the screen that hosts glass. */
@Immutable
data class GlassConfig(
    val transparency: Float,
    val hazeState: HazeState? = null,
) {
    /** Real backdrop blur needs RenderEffect (API 31) and is skipped for solid surfaces. */
    val blurActive: Boolean
        get() = !GlassTokens.isSolid(transparency) && hazeState != null && blurSupported

    companion object {
        val blurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}

/**
 * Motion settings. Containers move on springs, so movements can be interrupted, keep their velocity
 * and settle naturally; fades stay short tweens. With [reduced] every movement becomes a short fade
 * or snaps.
 */
@Immutable
data class MotionConfig(val reduced: Boolean) {
    /** State changes inside a component (expand, resize, move, color). */
    fun <T> standard(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(MotionTokens.STANDARD_DAMPING, MotionTokens.STANDARD_STIFFNESS)

    /** Containers appearing: a soft spring with a hint of overshoot. */
    fun <T> enter(): FiniteAnimationSpec<T> =
        if (reduced) tween(MotionTokens.SHORT, easing = FastOutSlowInEasing) else spring(MotionTokens.ENTER_DAMPING, MotionTokens.ENTER_STIFFNESS)

    /** Containers leaving: a stiffer spring without overshoot, so exits finish sooner than entries. */
    fun <T> exit(): FiniteAnimationSpec<T> =
        if (reduced) tween((MotionTokens.SHORT * MotionTokens.EXIT_FACTOR).toInt(), easing = MotionTokens.ExitEasing) else spring(Spring.DampingRatioNoBouncy, MotionTokens.EXIT_STIFFNESS)

    /** Panels following a gesture: quick and without overshoot, so no edge ever shows past its place. */
    fun <T> page(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(Spring.DampingRatioNoBouncy, MotionTokens.PAGE_STIFFNESS)

    /** Sliding from one page to the next; a gesture (predictive back) can still scrub it. */
    fun <T> navigation(): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(MotionTokens.NAVIGATION, easing = FastOutSlowInEasing)

    fun <T> fade(durationMillis: Int = MotionTokens.SHORT): FiniteAnimationSpec<T> = tween(durationMillis)

    fun <T> sheetSpring(): FiniteAnimationSpec<T> =
        if (reduced) tween(MotionTokens.SHORT) else spring(MotionTokens.SHEET_DAMPING, MotionTokens.SHEET_STIFFNESS)

    fun <T> bouncy(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)

    /** A section that opens in place. Reduced: a short fade, the space appears at once. */
    fun expandIn(): EnterTransition = if (reduced) fadeIn(tween(MotionTokens.SHORT)) else fadeIn(enter()) + expandVertically(enter())

    fun shrinkOut(): ExitTransition = if (reduced) fadeOut(tween(MotionTokens.SHORT)) else fadeOut(exit()) + shrinkVertically(exit())

    /** Floating content entering from below (or above with a negative [fraction]). Reduced: fade only. */
    fun riseIn(fraction: Int = 3): EnterTransition =
        if (reduced) fadeIn(tween(MotionTokens.SHORT)) else fadeIn(enter()) + slideInVertically(enter()) { it / fraction }

    fun sinkOut(fraction: Int = 3): ExitTransition =
        if (reduced) fadeOut(tween(MotionTokens.SHORT)) else fadeOut(exit()) + slideOutVertically(exit()) { it / fraction }

    /** Delay for the n-th item of a list that appears, capped so long lists do not drag. */
    fun staggerDelay(index: Int): Int = if (reduced) 0 else minOf(index, MotionTokens.STAGGER_MAX_ITEMS) * MotionTokens.STAGGER
}

val LocalCelanderColors = staticCompositionLocalOf { LightColors }
val LocalGlass = compositionLocalOf { GlassConfig(GlassTokens.DEFAULT_TRANSPARENCY) }
val LocalMotion = staticCompositionLocalOf { MotionConfig(reduced = false) }

object CelanderTheme {
    val colors: CelanderColors
        @Composable @ReadOnlyComposable get() = LocalCelanderColors.current
    val glass: GlassConfig
        @Composable @ReadOnlyComposable get() = LocalGlass.current
    val motion: MotionConfig
        @Composable @ReadOnlyComposable get() = LocalMotion.current
    val typography: Typography
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography
    val window: WindowLayout
        @Composable @ReadOnlyComposable get() = LocalWindowLayout.current
}

private fun CelanderColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = textPrimary,
        secondary = textSecondary,
        onSecondary = surface,
        secondaryContainer = accentSoft,
        onSecondaryContainer = textPrimary,
        tertiary = accent,
        background = background,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = if (isDark) Color(0xFF262A33) else Color(0xFFE8E9F0),
        onSurfaceVariant = textSecondary,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = if (isDark) Color(0xFF2A2E38) else Color(0xFFECEDF3),
        outline = outline,
        outlineVariant = divider,
        error = danger,
        onError = surface,
        scrim = scrim,
    )
}

private val BaseTypography = Typography()

private val CelanderTypography = Typography(
    displaySmall = BaseTypography.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = BaseTypography.bodyLarge,
    bodyMedium = BaseTypography.bodyMedium,
    bodySmall = BaseTypography.bodySmall,
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = BaseTypography.labelMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
)

/** Compact text for event chips inside the month grid. */
val ChipTextStyle = TextStyle(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium)

@Composable
fun CelanderTheme(
    darkTheme: Boolean,
    accentHue: Float,
    glassTransparency: Float,
    reduceMotion: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = remember(darkTheme, accentHue) { celanderColors(darkTheme, accentHue) }
    val scheme = remember(colors) { colors.toMaterial() }
    val windowSize = LocalWindowInfo.current.containerDpSize
    // Unmeasured (zero) before the first layout; the phone default avoids a flash of a wide layout.
    val window = remember(windowSize) {
        if (windowSize.width.value > 0f && windowSize.height.value > 0f) WindowLayout(windowSize.width, windowSize.height) else WindowLayout.Phone
    }
    MaterialTheme(colorScheme = scheme, typography = CelanderTypography) {
        CompositionLocalProvider(
            LocalCelanderColors provides colors,
            LocalGlass provides GlassConfig(glassTransparency),
            LocalMotion provides MotionConfig(reduceMotion),
            LocalWindowLayout provides window,
            // Elastic edges on every scroll container; none at all when motion is reduced.
            LocalOverscrollFactory provides rememberRubberBandOverscrollFactory(enabled = !reduceMotion),
            content = content,
        )
    }
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
