package com.celander.app.calendar.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.material3.MaterialTheme
import com.celander.app.ui.LocalMotion
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.celander.app.R
import com.celander.app.core.time.CalendarMath
import com.celander.app.core.ui.Formats
import com.celander.app.ui.theme.CelanderTheme
import com.celander.app.ui.PlainIconButton
import com.celander.app.ui.WheelPicker
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Compact month grid for picking a date inside sheets; fits narrow screens. */
@Composable
fun DateGridPicker(
    selected: LocalDate,
    today: LocalDate,
    firstDay: DayOfWeek,
    formats: Formats,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotion.current
    var month by rememberSaveable { mutableStateOf(YearMonth.from(selected)) }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlainIconButton(Icons.Rounded.ChevronLeft, stringResource(R.string.action_previous), { month = month.minusMonths(1) })
            Text(
                formats.capitalize(month.format(formats.monthYear)),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            PlainIconButton(Icons.Rounded.ChevronRight, stringResource(R.string.action_next), { month = month.plusMonths(1) })
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            CalendarMath.weekDays(month.atDay(1), firstDay).forEach { day ->
                Text(
                    formats.weekdayNarrow(day.dayOfWeek),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        AnimatedContent(
            targetState = month,
            transitionSpec = {
                val forward = targetState > initialState
                if (motion.reduced) {
                    fadeIn(motion.fade()) togetherWith fadeOut(motion.fade())
                } else {
                    (slideInHorizontally(motion.enter()) { if (forward) it / 4 else -it / 4 } + fadeIn(motion.enter())) togetherWith
                        (slideOutHorizontally(motion.exit()) { if (forward) -it / 4 else it / 4 } + fadeOut(motion.exit()))
                }
            },
            label = "dateGridMonth",
        ) { shown ->
            Column {
                CalendarMath.monthGrid(shown, firstDay).chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { date ->
                            DayCell(
                                date = date,
                                inMonth = date.month == shown.month,
                                selected = date == selected,
                                isToday = date == today,
                                label = formats.capitalize(date.format(formats.dayLong)),
                                onClick = { onPick(date) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    selected: Boolean,
    isToday: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .height(44.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (selected) colors.primary else Color.Transparent)
                .then(if (isToday && !selected) Modifier.border(1.5.dp, colors.primary, CircleShape) else Modifier),
        )
        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                selected -> colors.onPrimary
                isToday -> colors.primary
                inMonth -> colors.onSurface
                else -> colors.onSurfaceVariant
            },
        )
    }
}

/**
 * Compact time picker: wheels for hour and minute (every minute, plus AM/PM in the 12-hour format)
 * and a field for typing the time ("14:33").
 */
@Composable
fun TimeWheelPicker(
    time: LocalTime,
    formats: Formats,
    onPick: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    val next = stringResource(R.string.wheel_next)
    val previous = stringResource(R.string.wheel_previous)
    // A wheel coming to rest on the value it already shows changes nothing.
    val pick: (LocalTime) -> Unit = { value -> if (value != time) onPick(value) }
    fun twoDigits(value: Int) = String.format(formats.locale, "%02d", value)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GridLabel(stringResource(R.string.picker_exact_time), Modifier.weight(1f))
            ExactTimeField(time, formats, pick)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val afternoon = time.hour >= 12
            if (formats.use24h) {
                WheelPicker(
                    values = HOURS_24,
                    selected = time.hour,
                    onSelect = { hour -> pick(time.withHour(hour)) },
                    label = ::twoDigits,
                    contentDescription = stringResource(R.string.picker_hour),
                    increaseLabel = next,
                    decreaseLabel = previous,
                    modifier = Modifier.weight(1f),
                    visibleItems = TIME_WHEEL_ITEMS,
                )
            } else {
                WheelPicker(
                    values = HOURS_12,
                    selected = (time.hour % 12).let { if (it == 0) 12 else it },
                    onSelect = { hour -> pick(time.withHour(hour % 12 + if (afternoon) 12 else 0)) },
                    label = { it.toString() },
                    contentDescription = stringResource(R.string.picker_hour),
                    increaseLabel = next,
                    decreaseLabel = previous,
                    modifier = Modifier.weight(1f),
                    visibleItems = TIME_WHEEL_ITEMS,
                )
            }
            Text(":", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            WheelPicker(
                values = MINUTES,
                selected = time.minute,
                onSelect = { minute -> pick(time.withMinute(minute)) },
                label = ::twoDigits,
                contentDescription = stringResource(R.string.picker_minute),
                increaseLabel = next,
                decreaseLabel = previous,
                modifier = Modifier.weight(1f),
                visibleItems = TIME_WHEEL_ITEMS,
            )
            if (!formats.use24h) {
                val halfDay = DateTimeFormatter.ofPattern("a", formats.locale)
                WheelPicker(
                    values = listOf(false, true),
                    selected = afternoon,
                    onSelect = { pm -> pick(time.withHour(time.hour % 12 + if (pm) 12 else 0)) },
                    label = { pm -> LocalTime.of(if (pm) 12 else 0, 0).format(halfDay) },
                    contentDescription = stringResource(R.string.picker_half_day),
                    increaseLabel = next,
                    decreaseLabel = previous,
                    modifier = Modifier.weight(1f),
                    visibleItems = TIME_WHEEL_ITEMS,
                )
            }
        }
    }
}

private val HOURS_24 = (0..23).toList()
private val HOURS_12 = listOf(12) + (1..11)
private val MINUTES = (0..59).toList()

/** Three values per wheel keep the picker short inside the editor. */
private const val TIME_WHEEL_ITEMS = 3

/**
 * The time as typed ("1433", "14:33", "9:07"); in 12-hour mode the hour stays in the half of the day
 * the picker shows. Null until the entry makes a whole time. Three digits without a colon wait while
 * a two-digit hour could still follow ("213" on the way to "2130"), unless the entry is [finished].
 */
internal fun parseTypedTime(text: String, use24h: Boolean, current: LocalTime, finished: Boolean = false): LocalTime? {
    val colon = text.indexOf(':')
    val (hourDigits, minuteDigits) = if (colon >= 0) {
        text.take(colon).filter(Char::isDigit) to text.drop(colon + 1).filter(Char::isDigit)
    } else {
        val digits = text.filter(Char::isDigit)
        if (digits.length == 3 && !finished && digits[0] <= if (use24h) '2' else '1') return null
        digits.dropLast(2) to digits.takeLast(2)
    }
    if (hourDigits.length !in 1..2 || minuteDigits.length != 2) return null
    val hour = hourDigits.toInt()
    val minute = minuteDigits.toInt()
    if (minute > 59) return null
    return when {
        use24h -> if (hour <= 23) LocalTime.of(hour, minute) else null
        hour in 1..12 -> LocalTime.of(hour % 12 + if (current.hour >= 12) 12 else 0, minute)
        else -> null
    }
}

/** A small field for typing the exact time; every complete entry is picked right away. */
@Composable
private fun ExactTimeField(time: LocalTime, formats: Formats, onPick: (LocalTime) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val shown = time.format(DateTimeFormatter.ofPattern(if (formats.use24h) "HH:mm" else "h:mm", formats.locale))
    // Starts over from the picked time whenever the grids or a complete entry change it.
    var text by rememberSaveable(time) { mutableStateOf(shown) }
    val description = stringResource(R.string.picker_exact_time)
    Row(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { input ->
                text = input.filter { it.isDigit() || it == ':' }.take(5)
                parseTypedTime(text, formats.use24h, time)?.takeIf { it != time }?.let(onPick)
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.labelLarge.copy(color = colors.onSurface, textAlign = TextAlign.End),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                parseTypedTime(text, formats.use24h, time, finished = true)?.takeIf { it != time }?.let(onPick)
                focus.clearFocus()
            }),
            modifier = Modifier.width(56.dp).semantics { contentDescription = description },
        )
        if (!formats.use24h) {
            Text(
                time.format(DateTimeFormatter.ofPattern("a", formats.locale)),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun GridLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}
