package com.celander.app.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.celander.app.R
import com.celander.app.calendar.components.DateGridPicker
import com.celander.app.core.time.CalendarMath
import com.celander.app.core.ui.Formats
import com.celander.app.ui.theme.CelanderTheme
import com.celander.app.ui.GlassSheet
import com.celander.app.ui.PlainIconButton
import com.celander.app.ui.SoftButton
import com.celander.app.ui.rememberGlassSheetController
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

private const val YEARS_PER_PAGE = 12

/**
 * Compact picker opened from the pill's context label: years for the year view, months for month
 * and agenda, a calendar for the day view. "Today" jumps directly.
 */
@Composable
fun JumpDateSheet(
    destination: HomeDestination,
    selectedDate: LocalDate,
    today: LocalDate,
    firstDay: DayOfWeek,
    formats: Formats,
    onPick: (LocalDate) -> Unit,
    onDismissed: () -> Unit,
) {
    val controller = rememberGlassSheetController()
    val pick: (LocalDate) -> Unit = { date ->
        onPick(date)
        controller.close()
    }
    GlassSheet(onDismissed = onDismissed, controller = controller, paneTitle = stringResource(R.string.jump_title)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.jump_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            SoftButton(stringResource(R.string.action_today), onClick = { pick(today) })
        }
        when (destination) {
            HomeDestination.YEAR -> YearGrid(selectedDate, today, onPick = { year -> pick(CalendarMath.sameDayIn(YearMonth.of(year, selectedDate.monthValue), selectedDate.dayOfMonth)) })
            HomeDestination.DAY -> DateGridPicker(
                selected = selectedDate,
                today = today,
                firstDay = firstDay,
                formats = formats,
                onPick = pick,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            else -> MonthGrid(selectedDate, today, formats, onPick = { month -> pick(CalendarMath.sameDayIn(month, selectedDate.dayOfMonth)) })
        }
    }
}

@Composable
private fun YearGrid(selectedDate: LocalDate, today: LocalDate, onPick: (Int) -> Unit) {
    var firstYear by rememberSaveable { mutableIntStateOf(selectedDate.year - YEARS_PER_PAGE / 2 + 1) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Stepper(
            label = "$firstYear – ${firstYear + YEARS_PER_PAGE - 1}",
            onPrevious = { firstYear -= YEARS_PER_PAGE },
            onNext = { firstYear += YEARS_PER_PAGE },
        )
        ChoiceGrid(
            labels = List(YEARS_PER_PAGE) { (firstYear + it).toString() },
            selectedIndex = selectedDate.year - firstYear,
            highlightIndex = today.year - firstYear,
            onPick = { onPick(firstYear + it) },
        )
    }
}

@Composable
private fun MonthGrid(selectedDate: LocalDate, today: LocalDate, formats: Formats, onPick: (YearMonth) -> Unit) {
    var year by rememberSaveable { mutableIntStateOf(selectedDate.year) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Stepper(label = year.toString(), onPrevious = { year-- }, onNext = { year++ })
        ChoiceGrid(
            labels = List(12) { formats.capitalize(YearMonth.of(year, it + 1).format(formats.monthShort)) },
            selectedIndex = if (selectedDate.year == year) selectedDate.monthValue - 1 else -1,
            highlightIndex = if (today.year == year) today.monthValue - 1 else -1,
            onPick = { onPick(YearMonth.of(year, it + 1)) },
        )
    }
}

@Composable
private fun Stepper(label: String, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PlainIconButton(Icons.Rounded.ChevronLeft, stringResource(R.string.action_previous), onPrevious)
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        PlainIconButton(Icons.Rounded.ChevronRight, stringResource(R.string.action_next), onNext)
    }
}

@Composable
private fun ChoiceGrid(labels: List<String>, selectedIndex: Int, highlightIndex: Int, onPick: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.chunked(3).forEachIndexed { row, chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chunk.forEachIndexed { col, label ->
                    val index = row * 3 + col
                    val selected = index == selectedIndex
                    Box(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(CircleShape)
                            .background(if (selected) colors.primary else colors.surface.copy(alpha = 0.5f))
                            .selectable(selected = selected, role = Role.Button) { onPick(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = when {
                                selected -> colors.onPrimary
                                index == highlightIndex -> colors.primary
                                else -> colors.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}
