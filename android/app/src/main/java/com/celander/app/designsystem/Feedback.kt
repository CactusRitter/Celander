package com.celander.app.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.celander.app.core.ui.SnackbarMessage
import com.celander.app.core.ui.asString
import kotlinx.coroutines.delay

/** Floating glass snackbar with an optional action (usually "Undo"). */
@Composable
fun GlassSnackbarHost(
    message: SnackbarMessage?,
    onAction: (SnackbarMessage) -> Unit,
    onTimeout: (SnackbarMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = CelanderTheme.motion
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(message?.id) {
        val current = message ?: return@LaunchedEffect
        // Screen reader users get the longer timeout the system recommends.
        val timeout = accessibility?.calculateRecommendedTimeoutMillis(
            current.durationMillis,
            containsIcons = false,
            containsText = true,
            containsControls = current.action != null,
        ) ?: current.durationMillis
        delay(timeout)
        onTimeout(current)
    }
    // Keep the last message visible during the exit animation.
    val last = remember { mutableStateOf(message) }
    if (message != null) last.value = message
    val shown = last.value
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = if (motion.reduced) fadeIn(motion.fade()) else fadeIn(motion.fade()) + slideInVertically(motion.standard()) { it / 2 },
        exit = if (motion.reduced) fadeOut(motion.fade()) else fadeOut(motion.fade()) + slideOutVertically(motion.standard()) { it / 2 },
    ) {
        val msg = shown ?: return@AnimatedVisibility
        GlassSurface(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .blockTouchesBelow()
                .semantics { liveRegion = LiveRegionMode.Polite },
            shape = RoundedCornerShape(Radii.card),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = Spacing.l, end = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    msg.text.asString(),
                    style = CelanderTheme.typography.bodyMedium,
                    color = CelanderTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f).padding(vertical = Spacing.s),
                )
                msg.actionLabel?.let { label -> TextAction(label.asString(), onClick = { onAction(msg) }) }
            }
        }
    }
}

/** Pulsing placeholder block in the glass look, used instead of spinners. */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, height: Dp = 16.dp, shape: RoundedCornerShape = RoundedCornerShape(Radii.chip)) {
    val colors = CelanderTheme.colors
    val alpha = if (CelanderTheme.motion.reduced) {
        0.6f
    } else {
        val transition = rememberInfiniteTransition(label = "skeleton")
        val value by transition.animateFloat(0.35f, 0.75f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "skeletonAlpha")
        value
    }
    Box(
        modifier
            .height(height)
            .alpha(alpha)
            .background(colors.textTertiary.copy(alpha = 0.22f), shape),
    )
}

/** Skeleton list rows matching the event row layout. */
@Composable
fun SkeletonRows(count: Int, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        repeat(count) { index ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonBlock(Modifier.size(width = 4.dp, height = 36.dp))
                Spacer(Modifier.size(Spacing.m))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    SkeletonBlock(Modifier.fillMaxWidth(if (index % 2 == 0) 0.7f else 0.5f))
                    SkeletonBlock(Modifier.fillMaxWidth(0.35f), height = 12.dp)
                }
            }
        }
    }
}

/** Friendly empty state with concrete actions instead of a lone icon. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    primaryAction: Pair<String, () -> Unit>? = null,
    secondaryAction: Pair<String, () -> Unit>? = null,
) {
    val colors = CelanderTheme.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = Spacing.xxl, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp).background(colors.accentSoft, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = colors.accent, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(Spacing.l))
        Text(title, style = CelanderTheme.typography.titleMedium, color = colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.xs))
        Text(message, style = CelanderTheme.typography.bodyMedium, color = colors.textSecondary, textAlign = TextAlign.Center)
        if (primaryAction != null || secondaryAction != null) {
            Spacer(Modifier.height(Spacing.l))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                primaryAction?.let { (label, action) -> AccentButton(label, action) }
                secondaryAction?.let { (label, action) -> SoftButton(label, action) }
            }
        }
    }
}

/** Short error message with a retry action. */
@Composable
fun ErrorState(message: String, retryLabel: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(Spacing.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = CelanderTheme.typography.bodyMedium, color = CelanderTheme.colors.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.m))
        SoftButton(retryLabel, onRetry)
    }
}
