package com.celander.app.calendar.year

import androidx.compose.foundation.Canvas
import com.ce.kit.ui.glass.fadingTopEdge
import com.ce.kit.ui.glass.glassTile
import androidx.compose.material3.MaterialTheme
import com.ce.kit.ui.isDark
import com.celander.app.ui.today
import com.celander.app.ui.onToday
import com.celander.app.ui.sunday
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celander.app.R
import com.celander.app.calendar.CalendarWindow
import com.celander.app.calendar.PeriodPager
import com.celander.app.core.time.CalendarMath
import com.celander.app.core.ui.Formats
import com.celander.app.ui.CalendarColor
import com.ce.kit.ui.theme.CeTheme
import com.celander.app.ui.LimitedFontScale
import com.ce.kit.ui.elastic
import com.ce.kit.ui.glass.fadingTopEdge
import com.ce.kit.ui.rememberHaptics
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.ln

private val MinMonthHeight = 156.dp
private val MonthTitleHeight = 26.dp
private val WeekdayRowHeight = 14.dp

/**
 * How the twelve mini months share the space: 3, 4 or 6 columns, whichever gives tiles closest to
 * their natural, slightly upright shape. A phone in portrait gets 3 × 4, a phone in landscape or a
 * landscape tablet 6 × 2. Larger tiles scale their text up from the phone size.
 */
internal object YearGrid {
    private val ColumnChoices = listOf(3, 4, 6)
    private const val TARGET_ASPECT = 0.9f
    private val BaseTileWidth = 124.dp
    private val BaseTileHeight = 168.dp
    private const val MAX_TEXT_SCALE = 1.6f
    const val MAX_TITLE_SCALE = 1.3f

    fun columns(width: Dp, height: Dp, gap: Dp, minTileHeight: Dp): Int = ColumnChoices.minBy { columns ->
        val rows = 12 / columns
        val tileWidth = (width - gap * (columns - 1)) / columns
        val tileHeight = maxOf((height - gap * (rows - 1)) / rows, minTileHeight)
        abs(ln(tileWidth / tileHeight / TARGET_ASPECT))
    }

    /** Text grows with the tile, limited by the tighter of its two sides. */
    fun textScale(tileWidth: Dp, tileHeight: Dp): Float =
        minOf(tileWidth / BaseTileWidth, tileHeight / BaseTileHeight).coerceIn(1f, MAX_TEXT_SCALE)
}

@Composable
fun YearView(
    selectedDate: LocalDate,
    today: LocalDate,
    firstDay: DayOfWeek,
    formats: Formats,
    contentPadding: PaddingValues,
    windowFor: @Composable (start: LocalDate, endExclusive: LocalDate) -> CalendarWindow?,
    onYearChange: (Int) -> Unit,
    onOpenMonth: (YearMonth) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onLongPressDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    LimitedFontScale {
        PeriodPager(
            index = selectedDate.year.toLong(),
            onIndexChange = { onYearChange(it.toInt()) },
            modifier = modifier.fillMaxSize(),
        ) { year ->
            val start = LocalDate.of(year.toInt(), 1, 1)
            val window = windowFor(start, start.plusYears(1))
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val gridWidth = maxWidth - 12.dp * 2
                val gridHeight = maxHeight - contentPadding.calculateTopPadding() - contentPadding.calculateBottomPadding()
                val columns = YearGrid.columns(gridWidth, gridHeight, 8.dp, MinMonthHeight)
                val rows = 12 / columns
                val monthHeight = maxOf((gridHeight - 8.dp * (rows - 1)) / rows, MinMonthHeight)
                val monthWidth = (gridWidth - 8.dp * (columns - 1)) / columns
                val scale = YearGrid.textScale(monthWidth, monthHeight)
                Column(
                    Modifier
                        .fillMaxSize()
                        // Only scrolls where the months do not fit (landscape phones); then they fade out under the status bar.
                        .fadingTopEdge(contentPadding.calculateTopPadding() * 0.6f)
                        .elastic()
                        .verticalScroll(rememberScrollState())
                        .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding(), start = 12.dp, end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (row in 0 until rows) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (col in 0 until columns) {
                                val month = YearMonth.of(year.toInt(), row * columns + col + 1)
                                MiniMonth(
                                    month = month,
                                    today = today,
                                    firstDay = firstDay,
                                    formats = formats,
                                    window = window,
                                    onOpenMonth = { onOpenMonth(month) },
                                    onOpenDay = onOpenDay,
                                    onLongPressDay = onLongPressDay,
                                    scale = scale,
                                    modifier = Modifier.weight(1f).height(monthHeight),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniMonth(
    month: YearMonth,
    today: LocalDate,
    firstDay: DayOfWeek,
    formats: Formats,
    window: CalendarWindow?,
    onOpenMonth: () -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onLongPressDay: (LocalDate) -> Unit,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val isCurrentMonth = YearMonth.from(today) == month
    val days = remember(month, firstDay) { CalendarMath.monthGrid(month, firstDay) }
    val markers = remember(window, month) {
        days.associateWith { date ->
            if (YearMonth.from(date) != month) emptyList()
            else window?.day(date)?.let { content -> (content.allDay + content.timed).map { it.colorKey } + content.tasks.map { it.colorKey } }
                .orEmpty().distinct().take(2)
        }
    }
    val daysWithEntries = markers.count { it.value.isNotEmpty() }
    val monthName = formats.capitalize(month.format(formats.monthName))
    val description = monthName + ", " + pluralStringResource(R.plurals.days_with_entries, daysWithEntries, daysWithEntries)
    val openMonthLabel = stringResource(R.string.action_open_month)
    val numberStyle = TextStyle(fontSize = 10.sp * scale, fontWeight = FontWeight.Medium)
    val weekdayStyle = TextStyle(fontSize = 8.sp * scale, fontWeight = FontWeight.Medium)
    val titleScale = minOf(scale, YearGrid.MAX_TITLE_SCALE)
    val titleStyle = MaterialTheme.typography.labelLarge.let { it.copy(fontSize = it.fontSize * titleScale, lineHeight = it.lineHeight * titleScale) }
    val weekdayRowHeight = WeekdayRowHeight * scale

    Column(
        modifier
            .glassTile(RoundedCornerShape(18.dp))
            .semantics(mergeDescendants = false) {
                contentDescription = description
                onClick(openMonthLabel) { onOpenMonth(); true }
                customActions = listOf(CustomAccessibilityAction(openMonthLabel) { onOpenMonth(); true })
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            monthName,
            style = titleStyle,
            color = if (isCurrentMonth) colors.primary else colors.onSurface,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .height(MonthTitleHeight * titleScale)
                .clickable(onClick = onOpenMonth)
                .padding(top = 4.dp),
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(month, firstDay, weekdayRowHeight) {
                    fun dateAt(offset: Offset): LocalDate? {
                        val headerPx = weekdayRowHeight.toPx()
                        if (offset.y < headerPx) return null
                        val cellW = size.width / 7f
                        val cellH = (size.height - headerPx) / CalendarMath.GRID_WEEKS
                        val col = (offset.x / cellW).toInt().coerceIn(0, 6)
                        val row = ((offset.y - headerPx) / cellH).toInt().coerceIn(0, CalendarMath.GRID_WEEKS - 1)
                        return days[row * 7 + col].takeIf { YearMonth.from(it) == month }
                    }
                    detectTapGestures(
                        onTap = { offset -> dateAt(offset)?.let(onOpenDay) ?: onOpenMonth() },
                        onLongPress = { offset ->
                            dateAt(offset)?.let {
                                haptics.longPress()
                                onLongPressDay(it)
                            }
                        },
                    )
                },
        ) {
            val headerPx = weekdayRowHeight.toPx()
            val cellW = size.width / 7f
            val cellH = (size.height - headerPx) / CalendarMath.GRID_WEEKS
            for (i in 0 until 7) {
                val day = firstDay.plus(i.toLong())
                val layout = measurer.measure(formats.weekdayNarrow(day), weekdayStyle.copy(color = if (day == DayOfWeek.SUNDAY) colors.sunday else colors.onSurfaceVariant))
                drawText(layout, topLeft = Offset(i * cellW + (cellW - layout.size.width) / 2f, 0f))
            }
            days.forEachIndexed { index, date ->
                if (YearMonth.from(date) != month) return@forEachIndexed
                val col = index % 7
                val row = index / 7
                val cx = col * cellW + cellW / 2f
                val top = headerPx + row * cellH
                val isToday = date == today
                if (isToday) {
                    val side = minOf(cellW, cellH) * 0.92f
                    drawRoundRect(
                        colors.today,
                        topLeft = Offset(cx - side / 2f, top + (cellH - side) / 2f - 1.dp.toPx()),
                        size = Size(side, side),
                        cornerRadius = CornerRadius(side * 0.3f),
                    )
                }
                val color = when {
                    isToday -> colors.onToday
                    date.dayOfWeek == DayOfWeek.SUNDAY -> colors.sunday
                    else -> colors.onSurface
                }
                val text = measurer.measure(date.dayOfMonth.toString(), numberStyle.copy(color = color))
                drawText(text, topLeft = Offset(cx - text.size.width / 2f, top + (cellH - text.size.height) / 2f - 1.5.dp.toPx() * scale))
                val dots = markers[date].orEmpty()
                if (dots.isNotEmpty() && !isToday) {
                    val radius = 1.6.dp.toPx() * scale
                    val spacing = 4.dp.toPx() * scale
                    val y = top + cellH - radius - 0.5.dp.toPx()
                    val startX = cx - (dots.size - 1) * spacing / 2f
                    dots.forEachIndexed { i, key ->
                        drawCircle(CalendarColor.fromKey(key).base(colors.isDark), radius, Offset(startX + i * spacing, y))
                    }
                }
            }
        }
    }
}
