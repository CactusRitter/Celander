package com.celander.app.ui

import androidx.activity.compose.BackHandler
import com.celander.app.ui.glass.GlassKind
import com.celander.app.ui.glass.glass
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.celander.app.R

/**
 * Confirmation in the middle of the screen, frosted like the sheets: shown inside a screen that
 * hosts glass, it blurs the content behind it. Back and a tap on the scrim cancel.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun GlassDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotion.current
    val cancelLabel = stringResource(R.string.action_cancel)
    BackHandler(enabled = visible, onBack = onDismiss)
    AnimatedVisibility(visible, enter = fadeIn(motion.fade()), exit = fadeOut(motion.fade())) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .semantics {
                    contentDescription = cancelLabel
                    onClick { onDismiss(); true }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .padding(24.dp)
                    .widthIn(max = MaxDialogWidth)
                    .fillMaxWidth()
                    .animateEnterExit(
                        enter = if (motion.reduced) fadeIn(motion.fade()) else fadeIn(motion.enter()) + scaleIn(motion.enter(), MotionTokens.ZOOM_IN_SCALE),
                        exit = if (motion.reduced) fadeOut(motion.fade()) else fadeOut(motion.exit()) + scaleOut(motion.exit(), DIALOG_EXIT_SCALE),
                    )
                    .blockTouchesBelow()
                    .semantics {
                        paneTitle = title
                        dialog()
                    }
                    .glass(RoundedCornerShape(28.dp), GlassKind.Menu, elevation = 10.dp),
            ) {
                Column(Modifier.padding(start = 24.dp, end = 16.dp, top = 24.dp, bottom = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = colors.onSurface, modifier = Modifier.padding(end = 8.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, end = 8.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    ) {
                        TextAction(cancelLabel, onClick = onDismiss)
                        TextAction(confirmLabel, onClick = onConfirm, color = if (destructive) colors.error else colors.primary)
                    }
                }
            }
        }
    }
}

private val MaxDialogWidth = 420.dp
private const val DIALOG_EXIT_SCALE = 0.96f
