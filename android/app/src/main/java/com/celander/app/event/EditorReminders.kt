package com.celander.app.event

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.MaterialTheme
import com.celander.app.ui.LocalMotion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.celander.app.R
import com.celander.app.core.ui.Formats
import com.ce.kit.ui.theme.CeTheme
import com.celander.app.ui.RemovableChip
import com.celander.app.ui.SelectableChip
import com.celander.app.ui.Sizes
import com.celander.app.ui.SoftButton
import com.celander.app.ui.Stepper

/** At most this many reminders per entry, and as defaults per kind. */
internal const val MAX_REMINDERS = 4

/**
 * Reminders as removable chips plus "Add" with common choices and a custom amount. [warning] shows
 * why reminders would not appear (missing permission, disabled channel) right where they are set.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemindersSection(
    reminders: List<Int>,
    allDay: Boolean,
    task: Boolean,
    formats: Formats,
    onChange: (List<Int>) -> Unit,
    warning: (@Composable () -> Unit)? = null,
    /** The phone's calendars cap reminders and cannot store times on the day of all-day events. */
    maxCount: Int = MAX_REMINDERS,
    allowSameDay: Boolean = true,
    note: String? = null,
    /** In the settings: a plain title instead of the editor's icon row, rows without the icon indent. */
    title: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotion.current
    val resources = LocalResources.current
    var adding by rememberSaveable { mutableStateOf(false) }
    val label = { minutes: Int -> ReminderText.label(resources, minutes, allDay, task, formats) }

    if (title == null) {
        EditorRow(icon = Icons.Outlined.Notifications, label = stringResource(R.string.editor_reminders)) {}
    } else {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.padding(bottom = 8.dp))
    }
    Column(Modifier.padding(start = if (title == null) 44.dp else 0.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            reminders.forEach { minutes ->
                val text = label(minutes)
                RemovableChip(text, stringResource(R.string.reminder_remove, text), onRemove = { onChange(reminders - minutes) })
            }
            if (reminders.size < maxCount) {
                SelectableChip(
                    stringResource(R.string.reminder_add),
                    selected = adding,
                    onClick = { adding = !adding },
                    leading = { Icon(Icons.Rounded.Add, null, tint = colors.primary, modifier = Modifier.size(Sizes.iconSmall)) },
                )
            }
        }
        if (reminders.isEmpty() && !adding) {
            Text(stringResource(R.string.reminder_none), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        warning?.invoke()
        AnimatedVisibility(
            adding && reminders.size < maxCount,
            enter = motion.expandIn(),
            exit = motion.shrinkOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReminderText.presets(allDay).filterNot { it in reminders || (!allowSameDay && it < 0) }.forEach { minutes ->
                        SelectableChip(label(minutes), selected = false, onClick = {
                            onChange(reminders + minutes)
                            adding = false
                        })
                    }
                }
                CustomReminder(allDay, reminders, label) {
                    if (allowSameDay || it >= 0) {
                        onChange(reminders + it)
                        adding = false
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomReminder(allDay: Boolean, existing: List<Int>, label: (Int) -> String, onAdd: (Int) -> Unit) {
    var amount by rememberSaveable { mutableIntStateOf(if (allDay) 3 else 20) }
    var unit by rememberSaveable { mutableStateOf(if (allDay) ReminderUnit.DAYS else ReminderUnit.MINUTES) }
    val minutes = if (allDay) ReminderText.allDayDaysBefore(amount) else ReminderText.minutes(amount, unit)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.reminder_custom), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Stepper(value = amount, onValueChange = { amount = it }, range = 1..99, label = amount.toString())
            if (!allDay) {
                ReminderUnit.entries.forEach { option ->
                    SelectableChip(pluralStringResource(option.label, amount), selected = unit == option, onClick = { unit = option })
                }
            }
        }
        SoftButton(
            stringResource(R.string.reminder_custom_add, label(minutes)),
            onClick = { if (minutes !in existing) onAdd(minutes) },
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private val ReminderUnit.label: Int
    get() = when (this) {
        ReminderUnit.MINUTES -> R.plurals.reminder_unit_minutes
        ReminderUnit.HOURS -> R.plurals.reminder_unit_hours
        ReminderUnit.DAYS -> R.plurals.reminder_unit_days
        ReminderUnit.WEEKS -> R.plurals.reminder_unit_weeks
    }
