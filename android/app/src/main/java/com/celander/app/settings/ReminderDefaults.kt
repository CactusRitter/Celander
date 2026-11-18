package com.celander.app.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.MaterialTheme
import com.celander.app.ui.LocalMotion
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.celander.app.R
import com.celander.app.core.model.CalendarInfo
import com.celander.app.core.model.EntryKind
import com.celander.app.core.model.ReminderDefaults
import com.celander.app.core.ui.Formats
import com.ce.kit.ui.theme.CeTheme
import com.celander.app.ui.Sizes
import com.celander.app.event.MAX_REMINDERS
import com.celander.app.event.RemindersSection

/** Default reminders of one kind, as in the editor: chips to remove, "Add" with suggestions or a custom time. */
@Composable
internal fun DefaultReminders(
    title: String,
    reminders: List<Int>,
    allDay: Boolean,
    task: Boolean,
    formats: Formats,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    maxCount: Int = MAX_REMINDERS,
    allowSameDay: Boolean = true,
    onChange: (List<Int>) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(contentPadding)) {
        RemindersSection(
            reminders = reminders,
            allDay = allDay,
            task = task,
            formats = formats,
            onChange = { list -> onChange(list.distinct().sorted()) },
            maxCount = maxCount,
            allowSameDay = allowSameDay,
            title = title,
        )
    }
}

/** Keeps a calendar's reminder lists across rotation and process death. */
internal val ReminderDefaultsSaver: Saver<ReminderDefaults, Any> = listSaver(
    save = { listOf(it.event.toIntArray(), it.allDay.toIntArray(), it.task.toIntArray()) },
    restore = { ReminderDefaults(it[0].toList(), it[1].toList(), it[2].toList()) },
)

/**
 * A calendar's default reminders in its edit sheet: the app-wide ones while [useDefaults] is on,
 * otherwise its own [own] lists, only for the kinds of entries [calendar] can hold (null: a new
 * calendar of Celander's own, which holds both).
 */
@Composable
internal fun CalendarReminderDefaults(
    calendar: CalendarInfo?,
    useDefaults: Boolean,
    own: ReminderDefaults,
    formats: Formats,
    onUseDefaultsChange: (Boolean) -> Unit,
    onOwnChange: (ReminderDefaults) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val holdsEvents = calendar?.canHold(EntryKind.EVENT) ?: true
    val holdsTasks = calendar?.canHold(EntryKind.TASK) ?: true
    if (!holdsEvents && !holdsTasks) return
    val padding = PaddingValues()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_calendar_reminders), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.touchTarget)
                .toggleable(value = useDefaults, role = Role.Switch, onValueChange = onUseDefaultsChange),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.settings_calendar_use_default_reminders), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                Text(
                    stringResource(if (useDefaults) R.string.settings_calendar_default_reminders_summary else R.string.settings_calendar_own_reminders_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Switch(checked = useDefaults, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = colors.primary))
        }
        AnimatedVisibility(!useDefaults, enter = LocalMotion.current.expandIn(), exit = LocalMotion.current.shrinkOut()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (holdsEvents) {
                    DefaultReminders(
                        stringResource(R.string.settings_calendar_reminders_events),
                        own.event,
                        allDay = false,
                        task = false,
                        formats = formats,
                        contentPadding = padding,
                    ) { onOwnChange(own.copy(event = it)) }
                    DefaultReminders(
                        stringResource(R.string.settings_calendar_reminders_all_day),
                        own.allDay,
                        allDay = true,
                        task = false,
                        formats = formats,
                        contentPadding = padding,
                    ) { onOwnChange(own.copy(allDay = it)) }
                }
                if (holdsTasks) {
                    DefaultReminders(
                        stringResource(R.string.settings_calendar_reminders_tasks),
                        own.task,
                        allDay = false,
                        task = true,
                        formats = formats,
                        contentPadding = padding,
                    ) { onOwnChange(own.copy(task = it)) }
                }
            }
        }
    }
}
