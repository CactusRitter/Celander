package com.celander.app.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.celander.app.R
import com.celander.app.designsystem.CelanderTheme
import com.celander.app.designsystem.Radii
import com.celander.app.designsystem.Sizes
import com.celander.app.designsystem.Spacing
import com.celander.app.designsystem.TextAction
import com.celander.app.designsystem.glassTile

/** Shown where reminders are set when they would not appear, with the way to turn them on. */
@Composable
fun ReminderWarning(access: NotificationAccess, task: Boolean, modifier: Modifier = Modifier) {
    if (!access.status.blocks(task)) return
    val colors = CelanderTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .glassTile(RoundedCornerShape(Radii.cell), tint = colors.danger.copy(alpha = 0.12f))
            .padding(start = Spacing.m, end = Spacing.xxs, top = Spacing.s)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Icon(Icons.Outlined.NotificationsOff, null, tint = colors.danger, modifier = Modifier.size(Sizes.iconSmall + Spacing.xxs))
            Text(
                stringResource(R.string.reminders_blocked),
                style = CelanderTheme.typography.bodySmall,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f).padding(end = Spacing.s),
            )
        }
        TextAction(stringResource(R.string.reminders_turn_on), onClick = { access.fix(task) }, modifier = Modifier.align(Alignment.End))
    }
}
