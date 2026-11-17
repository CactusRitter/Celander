package com.celander.app.calendar

import androidx.compose.animation.AnimatedContent
import com.celander.app.ui.glass.GlassKind
import com.celander.app.ui.glass.glass
import androidx.compose.material3.MaterialTheme
import com.celander.app.ui.LocalMotion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celander.app.R
import com.celander.app.ui.theme.CelanderTheme
import com.celander.app.ui.glass.GlassIconButton
import com.celander.app.ui.Sizes
import com.celander.app.ui.springClickable
import java.time.LocalDate

/**
 * Top row: settings on the left, "today" and a pill with search and "new entry" on the right, the
 * heading of the view in the middle. "Today" appears only when the visible period lacks today. The
 * heading stays centred on the screen; it first gets smaller and only moves off centre when it
 * still would not fit.
 */
@Composable
fun HomeTopBar(
    title: String,
    today: LocalDate,
    showToday: Boolean,
    onToday: () -> Unit,
    onAdd: () -> Unit,
    onSearch: (() -> Unit)?,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotion.current
    val colors = MaterialTheme.colorScheme
    Layout(
        modifier = modifier.fillMaxWidth().height(Sizes.topBar).padding(horizontal = 16.dp),
        content = {
            GlassIconButton(Icons.Outlined.Settings, stringResource(R.string.action_settings), onSettings)
            AnimatedContent(
                targetState = title,
                transitionSpec = { fadeIn(motion.fade()) togetherWith fadeOut(motion.fade()) },
                label = "heading",
            ) { text ->
                BasicText(
                    text,
                    style = MaterialTheme.typography.titleLarge.copy(color = colors.onSurface, textAlign = TextAlign.Center),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = HeadingMinSize, maxFontSize = MaterialTheme.typography.titleLarge.fontSize),
                    modifier = Modifier.semantics { heading() },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(
                    visible = showToday,
                    enter = fadeIn(motion.fade()) + if (motion.reduced) fadeIn(motion.fade()) else scaleIn(motion.standard(), initialScale = 0.8f),
                    exit = fadeOut(motion.fade()) + if (motion.reduced) fadeOut(motion.fade()) else scaleOut(motion.standard(), targetScale = 0.8f),
                ) {
                    TodayButton(today, onToday)
                }
                ActionPill(onAdd, onSearch)
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val leading = measurables[0].measure(loose)
        val trailing = measurables[2].measure(loose)
        val gap = 8.dp.roundToPx()
        val width = constraints.maxWidth
        val centred = width - 2 * (maxOf(leading.width, trailing.width) + gap)
        val free = width - leading.width - trailing.width - 2 * gap
        // The heading may use all the room between the buttons; it is centred whenever it fits.
        val heading = measurables[1].measure(loose.copy(maxWidth = free.coerceAtLeast(0)))
        val x = if (heading.width <= centred) (width - heading.width) / 2 else leading.width + gap + (free - heading.width) / 2
        val height = constraints.maxHeight
        layout(width, height) {
            leading.placeRelative(0, (height - leading.height) / 2)
            heading.placeRelative(x, (height - heading.height) / 2)
            trailing.placeRelative(width - trailing.width, (height - trailing.height) / 2)
        }
    }
}

private val HeadingMinSize = 16.sp

/** Search and "new entry" side by side on one glass pill, both plain icons like the other buttons. */
@Composable
private fun ActionPill(onAdd: () -> Unit, onSearch: (() -> Unit)?) {
    Box(Modifier.glass(CircleShape, GlassKind.Chrome, elevation = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onSearch != null) PillAction(Icons.Outlined.Search, stringResource(R.string.action_search), onSearch)
            PillAction(Icons.Rounded.Add, stringResource(R.string.action_new_entry), onAdd)
        }
    }
}

@Composable
private fun PillAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(Sizes.touchTarget)
            .springClickable(role = Role.Button, shape = CircleShape, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(Sizes.icon))
    }
}

/** Same icon everywhere: a small calendar sheet with today's day number. */
@Composable
private fun TodayButton(today: LocalDate, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    GlassIconButton(
        icon = null,
        contentDescription = stringResource(R.string.action_today),
        onClick = onClick,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .border(1.6.dp, colors.onSurface, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                today.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 11.sp),
                color = colors.onSurface,
            )
        }
    }
}
