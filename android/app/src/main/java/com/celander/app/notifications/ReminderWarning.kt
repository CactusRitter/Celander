package com.celander.app.notifications

import androidx.compose.foundation.layout.Arrangement
import com.celander.app.ui.glass.glassTile
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
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
import com.celander.app.ui.theme.CelanderTheme
import com.celander.app.ui.Sizes
import com.celander.app.ui.TextAction

/** Shown where reminders are set when they would not appear, with the way to turn them on. */
@Composable
fun ReminderWarning(access: NotificationAccess, task: Boolean, modifier: Modifier = Modifier) {
    if (!access.status.blocks(task)) return
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .glassTile(RoundedCornerShape(12.dp), tint = colors.error.copy(alpha = 0.12f))
            .padding(start = 12.dp, end = 2.dp, top = 8.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.NotificationsOff, null, tint = colors.error, modifier = Modifier.size(Sizes.iconSmall + 2.dp))
            Text(
                stringResource(R.string.reminders_blocked),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurface,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
        }
        TextAction(stringResource(R.string.reminders_turn_on), onClick = { access.fix(task) }, modifier = Modifier.align(Alignment.End))
    }
}
