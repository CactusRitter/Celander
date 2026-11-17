package com.celander.app.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import kotlin.math.max

/** Calm, pattern-free background: a base tone with three very soft color washes. */
@Composable
fun AppBackground(modifier: Modifier = Modifier) {
    val colors = CelanderTheme.colors
    Box(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .drawBehind {
                val radius = max(size.width, size.height) * 0.75f
                val alphas = if (colors.isDark) floatArrayOf(0.55f, 0.42f, 0.38f) else floatArrayOf(0.60f, 0.48f, 0.42f)
                val centers = listOf(
                    Offset(size.width * 0.05f, size.height * 0.08f),
                    Offset(size.width * 1.0f, size.height * 0.45f),
                    Offset(size.width * 0.15f, size.height * 0.98f),
                )
                colors.washes.forEachIndexed { i, wash ->
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(wash.copy(alpha = alphas[i]), wash.copy(alpha = 0f)),
                            center = centers[i],
                            radius = radius,
                        ),
                        radius = radius,
                        center = centers[i],
                    )
                }
            },
    )
}

/**
 * Layered shadow drawn only outside [shape], so translucent glass is not darkened from below.
 * Rendered by the hardware pipeline from API 28; older devices simply show no shadow.
 */
fun Modifier.softShadow(shape: Shape, color: Color, layers: List<ShadowLayer>): Modifier =
    drawWithCache {
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        val outline = path.asAndroidPath()
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            this.color = color.copy(alpha = 1f).toArgb()
        }
        onDrawBehind {
            if (color.alpha == 0f) return@onDrawBehind
            drawIntoCanvas { canvas ->
                canvas.save()
                canvas.clipPath(path, ClipOp.Difference)
                for (layer in layers) {
                    paint.setShadowLayer(layer.blur.toPx(), 0f, layer.offsetY.toPx(), color.copy(alpha = color.alpha * layer.alpha).toArgb())
                    canvas.nativeCanvas.drawPath(outline, paint)
                }
                canvas.restore()
            }
        }
    }

/** Fine light edge: brighter at the top, fading towards the bottom. */
@Composable
fun Modifier.glassEdge(shape: Shape): Modifier {
    val colors = CelanderTheme.colors
    return border(
        width = Sizes.hairline,
        brush = Brush.verticalGradient(listOf(colors.glassEdgeLight, colors.glassEdgeShade)),
        shape = shape,
    )
}

/**
 * Frosted glass surface for large floating elements (pill, sheets, header, snackbar). Blurs the
 * content registered as haze source behind it; without blur support it falls back to a translucent
 * fill, and to an opaque one when glass is turned off. [dense] surfaces (sheets) let less through.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radii.card),
    shadow: Boolean = true,
    dense: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = CelanderTheme.colors
    Box(
        modifier
            .then(if (shadow) Modifier.softShadow(shape, colors.shadow, Elevation.floating) else Modifier)
            .clip(shape)
            .frosted(dense)
            .glassEdge(shape),
        content = content,
    )
}

/**
 * Glass card inside scrolling content, like the day list under the month grid: the same frost as
 * the pill, blurring only the background layer behind it ([GlassLayers]), rounded on all sides,
 * with a soft shadow and a sheen from above for depth.
 */
@Composable
fun Modifier.glassCard(shape: Shape = RoundedCornerShape(Radii.sheet)): Modifier {
    val colors = CelanderTheme.colors
    return softShadow(shape, colors.shadow, Elevation.raised)
        .clip(shape)
        .frosted(dense = false)
        .background(Brush.verticalGradient(0f to colors.sheen, SHEEN_END to Color.Transparent))
        .glassEdge(shape)
}

/**
 * Blurs the haze sources behind this node and tints them; without blur support it falls back to a
 * translucent fill, and to an opaque one at 0 % transparency. [dense] surfaces (sheets) let less through.
 */
@Composable
private fun Modifier.frosted(dense: Boolean): Modifier {
    val colors = CelanderTheme.colors
    val glass = CelanderTheme.glass
    val alpha = if (dense) {
        GlassTokens.sheetFillAlpha(glass.transparency, colors.isDark, glass.blurActive)
    } else {
        GlassTokens.fillAlpha(glass.transparency, colors.isDark, glass.blurActive)
    }
    val fill = colors.glassTint.copy(alpha = alpha)
    val state = glass.hazeState
    return if (glass.blurActive && state != null) {
        hazeBlur(
            input = HazeInput.Sources(state),
            style = HazeBlurStyle {
                blurRadius(GlassTokens.blurRadius(glass.transparency))
                backgroundColor(colors.background)
                colorEffects(listOf(HazeColorEffect.tint(fill)))
                noiseFactor(GlassTokens.NOISE)
            },
        )
    } else {
        background(fill)
    }
}

/**
 * Haze source layers of a screen. Glass inside the content (cards) only blurs layers below its own,
 * i.e. the background; floating glass outside all sources blurs every layer.
 */
object GlassLayers {
    const val BACKGROUND = 0f
    const val CONTENT = 1f
}

/** Translucent tile for content inside scrolling areas (cells, cards). Never blurs. */
@Composable
fun Modifier.glassTile(shape: Shape = RoundedCornerShape(Radii.cell), tint: Color? = null): Modifier {
    val colors = CelanderTheme.colors
    val glass = CelanderTheme.glass
    val base = colors.tile.copy(alpha = GlassTokens.tileAlpha(glass.transparency, colors.isDark))
    return clip(shape)
        .background(base)
        .then(if (tint != null) Modifier.background(tint) else Modifier)
}

/** The sheen fades out at this fraction of the card's height. */
private const val SHEEN_END = 0.4f

val PillShape = RoundedCornerShape(Radii.pill)
val SheetShape = RoundedCornerShape(topStart = Radii.sheet, topEnd = Radii.sheet)
val CircleGlassShape = CircleShape

/** Fades content out over [height] at the top edge, for lists that scroll below floating controls. */
fun Modifier.fadingTopEdge(height: Dp): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fraction = (height.toPx() / size.height).coerceIn(0f, 1f)
        drawRect(Brush.verticalGradient(0f to Color.Transparent, fraction to Color.Black), blendMode = BlendMode.DstIn)
    }

/** Fades content out over [height] at the bottom edge, so a list that continues reads as continuing. */
fun Modifier.fadingBottomEdge(height: Dp): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fraction = (height.toPx() / size.height).coerceIn(0f, 1f)
        drawRect(Brush.verticalGradient(1f - fraction to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }
