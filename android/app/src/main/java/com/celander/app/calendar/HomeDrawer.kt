package com.celander.app.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ce.kit.ui.glass.GlassKind
import com.ce.kit.ui.glass.glass
import com.ce.kit.ui.glass.pressScale
import com.celander.app.R
import com.celander.app.core.model.CalendarInfo
import com.celander.app.ui.CalendarColor
import com.celander.app.ui.color

/**
 * Contents of the side drawer (the ☰ of the other Ce apps): the views, the calendars with a switch for
 * each (grouped by account), and the way to Accounts and Settings. The panel is floating glass, so it
 * blurs everything behind it; the sheet around it is transparent and a tap outside closes it.
 */
@Composable
fun HomeDrawerContent(
    current: HomeDestination,
    calendars: List<CalendarInfo>,
    onSelectView: (HomeDestination) -> Unit,
    onToggleCalendar: (CalendarInfo) -> Unit,
    onManageCalendars: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp)
            .fillMaxWidth(0.82f)
            // Swallows taps on the panel so only the area around it closes the drawer.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .glass(RoundedCornerShape(28.dp), GlassKind.Card, elevation = 12.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .pressScale(0.85f)
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_settings), onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Settings, stringResource(R.string.action_settings), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SectionTitle(R.string.drawer_views)
        HomeDestination.entries.forEach { destination ->
            val selected = destination == current
            DrawerRow(
                icon = { Icon(destination.icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                text = stringResource(destination.label),
                selected = selected,
                onClick = { onSelectView(destination) },
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp, horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SectionTitle(R.string.drawer_calendars)
        // Local calendars first, then one block per account.
        calendars.groupBy { it.account }.toList().sortedBy { it.first != null }.forEach { (account, list) ->
            if (account != null) {
                Text(
                    account,
                    Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            list.forEach { calendar ->
                DrawerRow(
                    icon = {
                        Icon(
                            if (calendar.visible) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                            null,
                            tint = CalendarColor.fromKey(calendar.colorKey).color(),
                        )
                    },
                    text = calendar.name,
                    selected = false,
                    onClick = { onToggleCalendar(calendar) },
                )
            }
        }
        DrawerRow(icon = { Icon(Icons.Outlined.Tune, null) }, text = stringResource(R.string.drawer_manage_calendars), selected = false, onClick = onManageCalendars)
        HorizontalDivider(Modifier.padding(vertical = 6.dp, horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        DrawerRow(icon = { Icon(Icons.Outlined.AccountCircle, null) }, text = stringResource(R.string.drawer_accounts), selected = false, onClick = onOpenAccounts)
    }
}

@Composable
private fun SectionTitle(text: Int) {
    Text(
        stringResource(text),
        Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DrawerRow(icon: @Composable () -> Unit, text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .pressScale(0.96f)
            .fillMaxWidth()
            .glass(RoundedCornerShape(50), if (selected) GlassKind.Pinned else GlassKind.Card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        icon()
        Text(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}
