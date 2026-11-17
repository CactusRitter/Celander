package com.celander.app.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Keeps touches on a floating card from reaching what lies beneath it. Being hit, the card hides
 * them from the content underneath. Presses and releases that nothing inside the card took are
 * consumed after its children had them, so an ancestor such as a dialog's scrim does not read them
 * as a tap on itself. Moves stay untouched: consuming them would cancel buttons and drags inside.
 */
fun Modifier.blockTouchesBelow(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Main).changes.forEach { change ->
                if (change.pressed != change.previousPressed) change.consume()
            }
        }
    }
}

/** Shrinks the element slightly while pressed, giving tactile feedback without moving layout. */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource, pressedScale: Float = MotionTokens.PRESS_SCALE): Modifier {
    if (CelanderTheme.motion.reduced) return this
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** clickable + ripple + press scale; place it before background/clip so the whole element scales. */
@Composable
fun Modifier.springClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    shape: Shape? = null,
    pressedScale: Float = MotionTokens.PRESS_SCALE,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return pressScale(source, pressedScale)
        .then(if (shape != null) Modifier.clip(shape) else Modifier)
        .clickable(
            interactionSource = source,
            indication = ripple(),
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
}

/**
 * True for a short moment after [key] changes. Lists use it so items fade in only when the list
 * first appears (e.g. a newly selected day), not every time an item scrolls back into view.
 */
@Composable
fun rememberEntranceWindow(key: Any?): State<Boolean> {
    val open = remember(key) { mutableStateOf(true) }
    LaunchedEffect(key) {
        delay(MotionTokens.LONG.toLong() + MotionTokens.STAGGER * MotionTokens.STAGGER_MAX_ITEMS)
        open.value = false
    }
    return open
}

/** Fades and lifts an item in with a staggered delay when [enabled]. */
@Composable
fun Modifier.staggeredEntrance(index: Int, enabled: Boolean): Modifier {
    val motion = CelanderTheme.motion
    if (!enabled || motion.reduced) return this
    val progress = remember { Animatable(0f) }
    val offsetPx = with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(Unit) {
        delay(motion.staggerDelay(index).toLong())
        progress.animateTo(1f, tween(MotionTokens.LONG, easing = MotionTokens.EnterEasing))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * offsetPx
    }
}

/** Briefly enlarges the element whenever [trigger] changes (used for "today" after a jump). */
@Composable
fun Modifier.pulseOn(trigger: Int, enabled: Boolean = true): Modifier {
    val motion = CelanderTheme.motion
    val scale = remember { Animatable(1f) }
    val initial = remember { trigger }
    LaunchedEffect(trigger) {
        if (!enabled || motion.reduced || trigger == initial) return@LaunchedEffect
        scale.animateTo(1.22f, tween(MotionTokens.SHORT, easing = MotionTokens.EnterEasing))
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
