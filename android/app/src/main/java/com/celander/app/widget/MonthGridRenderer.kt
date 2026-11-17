package com.celander.app.widget

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import com.celander.app.calendar.CalendarWindow
import com.celander.app.calendar.month.MonthEntry
import com.celander.app.calendar.month.MonthLayout
import com.celander.app.calendar.month.WeekLayout
import com.celander.app.core.time.CalendarMath
import com.celander.app.designsystem.CalendarColor
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor

/** What the month widget's grid shows. */
internal data class MonthGridSpec(
    val month: YearMonth,
    val firstDay: DayOfWeek,
    val showWeekNumbers: Boolean,
    val today: LocalDate,
    val window: CalendarWindow?,
    val zone: ZoneId,
    val weekdayLabels: List<String>,
    val untitled: String,
)

/**
 * Draws the grid of the month widget, modeled on the Samsung month widget: weekday row, week
 * numbers, hairlines between weeks, the day number on top, then all-day entries as tinted bars that
 * run across days and timed entries with a color bar (tasks with a ring), "+n" when a day overflows.
 * Today has an inverted badge and an outlined cell; days of other months are faded.
 */
internal object MonthGridRenderer {
    const val HEADER_DP = 22f
    const val WEEK_COLUMN_DP = 18f

    /** Week numbers only when the day columns stay wide enough; [widthDp] is the grid's width. */
    fun showsWeekNumbers(enabled: Boolean, widthDp: Float): Boolean = enabled && (widthDp - WEEK_COLUMN_DP) / 7f >= MIN_COLUMN_WITH_WEEK_NUMBERS_DP

    fun weeks(month: YearMonth, firstDay: DayOfWeek): Int {
        val start = CalendarMath.monthGridStart(month, firstDay)
        val days = month.atEndOfMonth().toEpochDay() - start.toEpochDay() + 1
        return ceil(days / 7.0).toInt()
    }

    fun render(spec: MonthGridSpec, width: Int, height: Int, paints: WidgetPaints): WidgetLayers {
        val layers = WidgetLayers(width, height, withAccent = false)
        val headerH = paints.dp(HEADER_DP)
        val showWeekNumbers = showsWeekNumbers(spec.showWeekNumbers, width / paints.dp(1f))
        val weekColW = if (showWeekNumbers) paints.dp(WEEK_COLUMN_DP) else 0f
        val rows = weeks(spec.month, spec.firstDay)
        val rowH = (height - headerH) / rows
        val colW = (width - weekColW) / 7f

        // Weekday row.
        val weekdayPaint = paints.text(10.5f, WidgetPaints.Weight.MEDIUM, alpha = 0.7f, align = Paint.Align.CENTER)
        val sundayPaint = paints.text(10.5f, WidgetPaints.Weight.MEDIUM, align = Paint.Align.CENTER)
        for (i in 0 until 7) {
            val x = weekColW + colW * (i + 0.5f)
            val baseline = headerH * 0.68f
            val isSunday = spec.firstDay.plus(i.toLong()) == DayOfWeek.SUNDAY
            if (isSunday) layers.sundayCanvas.drawText(spec.weekdayLabels[i], x, baseline, sundayPaint)
            else layers.inkCanvas.drawText(spec.weekdayLabels[i], x, baseline, weekdayPaint)
        }

        val gridStart = CalendarMath.monthGridStart(spec.month, spec.firstDay)
        val hairline = paints.fill(alpha = 0.12f)
        val weekNumberPaint = paints.text(9.5f, alpha = 0.55f, align = Paint.Align.CENTER)
        val numberLine = paints.sp(12.5f) * 1.3f
        val lanes = Lanes(paints, rowH - numberLine - paints.dp(5f), colW)
        // Too short for a "+n" line: numbers move to the left and "+n" goes beside them.
        val numberPaint = paints.text(12.5f, WidgetPaints.Weight.BOLD, align = if (lanes.compact) Paint.Align.LEFT else Paint.Align.CENTER)
        val lanesTopOffset = if (lanes.lines) numberLine + paints.dp(2f) else numberLine + paints.dp(5f)
        for (row in 0 until rows) {
            val top = headerH + row * rowH
            val weekStart = gridStart.plusWeeks(row.toLong())
            layers.inkCanvas.drawRect(weekColW, top, width.toFloat(), top + maxOf(1f, paints.dp(0.75f)), hairline)
            val numberBaseline = top + paints.dp(3f) + numberPaint.textSize
            if (showWeekNumbers) {
                layers.inkCanvas.drawText(CalendarMath.weekNumber(weekStart, spec.firstDay).toString(), weekColW / 2f, numberBaseline, weekNumberPaint)
            }
            for (col in 0 until 7) {
                val date = weekStart.plusDays(col.toLong())
                val left = weekColW + col * colW
                val centerX = if (lanes.compact) left + paints.dp(4f) + numberPaint.measureText(date.dayOfMonth.toString()) / 2f else left + colW / 2f
                val numberX = if (lanes.compact) left + paints.dp(4f) else centerX
                if (date == spec.today) {
                    val half = maxOf(paints.dp(11f), numberPaint.measureText(date.dayOfMonth.toString()) / 2f + paints.dp(4f))
                    val badge = RectF(centerX - half, numberBaseline - numberPaint.textSize - paints.dp(1.5f), centerX + half, numberBaseline + paints.dp(4f))
                    layers.inkCanvas.drawRoundRect(badge, paints.dp(5f), paints.dp(5f), paints.fill())
                    layers.inkCanvas.drawText(date.dayOfMonth.toString(), numberX, numberBaseline, paints.cutOut(numberPaint))
                    val cell = RectF(left + paints.dp(1.5f), top + paints.dp(1.5f), left + colW - paints.dp(1.5f), top + rowH - paints.dp(1.5f))
                    layers.inkCanvas.drawRoundRect(cell, paints.dp(6f), paints.dp(6f), paints.stroke(paints.dp(1f), alpha = 0.35f))
                } else {
                    val canvas = if (date.dayOfWeek == DayOfWeek.SUNDAY) layers.sundayCanvas else layers.inkCanvas
                    canvas.drawText(date.dayOfMonth.toString(), numberX, numberBaseline, numberPaint)
                }
            }
            val layout = spec.window?.let { MonthLayout.layoutWeek(weekStart, it.occurrences, it.tasks, spec.zone) }
            if (layout != null) drawEntries(layers, layout, spec, paints, lanes, weekColW, colW, row, top, top + lanesTopOffset, top + rowH, numberBaseline)
            for (col in 0 until 7) {
                if (YearMonth.from(weekStart.plusDays(col.toLong())) != spec.month) {
                    layers.fade(RectF(weekColW + col * colW, top + paints.dp(1f), weekColW + (col + 1) * colW, top + rowH), OUTSIDE_MONTH_ALPHA)
                }
            }
        }
        return layers
    }

    private fun drawEntries(
        layers: WidgetLayers,
        layout: WeekLayout,
        spec: MonthGridSpec,
        paints: WidgetPaints,
        lanes: Lanes,
        weekColW: Float,
        colW: Float,
        row: Int,
        rowTop: Float,
        lanesTop: Float,
        rowBottom: Float,
        numberBaseline: Float,
    ) {
        if (lanes.lines) {
            drawLines(layers, layout, paints, lanes, weekColW, colW, lanesTop, rowBottom)
            return
        }
        val textPaint = lanes.textPaint
        val laneH = lanes.height
        val visible = when {
            layout.laneCount <= lanes.max -> layout.laneCount
            lanes.compact -> lanes.max
            else -> lanes.max - 1
        }
        val moreBesideNumber = lanes.compact
        val inset = paints.dp(2f)
        for (segment in layout.segments) {
            if (segment.lane >= visible) continue
            val top = lanesTop + segment.lane * laneH
            val chipBottom = top + laneH - paints.dp(2f)
            val baseline = top + (laneH - paints.dp(2f)) / 2f + textPaint.textSize * 0.36f
            val base = CalendarColor.fromKey(segment.entry.colorKey).base(dark = false).toArgb()
            val title = segment.entry.title.ifBlank { spec.untitled }
            val left = weekColW + segment.startCol * colW + if (segment.continuesBefore) 0f else inset
            val right = weekColW + (segment.endCol + 1) * colW - if (segment.continuesAfter) 0f else inset
            if (segment.bar) {
                val chip = RectF(left, top, right, chipBottom)
                val radius = paints.dp(4f)
                val tint = paints.fill(base).apply { alpha = (CHIP_TINT * 255).toInt() }
                layers.colorCanvas.drawRoundRect(chip, radius, radius, tint)
                // Square ends where the entry continues into the previous or next week.
                if (segment.continuesBefore) layers.colorCanvas.drawRect(chip.left, chip.top, chip.left + radius, chip.bottom, tint)
                if (segment.continuesAfter) layers.colorCanvas.drawRect(chip.right - radius, chip.top, chip.right, chip.bottom, tint)
                layers.inkCanvas.drawClipped(title, chip.left + paints.dp(3f), baseline, chip.right - paints.dp(2f), textPaint)
            } else {
                val markerX = left + paints.dp(1f)
                val entry = segment.entry
                val done = entry is MonthEntry.Task && entry.item.task.done
                if (entry is MonthEntry.Task) {
                    layers.tasks += TaskTarget(row, segment.startCol, top - rowTop, laneH, entry.item.task.id, title, done)
                    val r = paints.dp(3f)
                    layers.colorCanvas.drawCircle(markerX + r, (top + chipBottom) / 2f, r, paints.stroke(paints.dp(1.2f)).apply { color = base })
                } else {
                    layers.colorCanvas.drawRoundRect(RectF(markerX, top + paints.dp(1f), markerX + paints.dp(2.5f), chipBottom - paints.dp(1f)), paints.dp(1.25f), paints.dp(1.25f), paints.fill(base))
                }
                val paint = if (done) paints.text(10.5f, alpha = 0.45f).apply { isStrikeThruText = true } else textPaint
                layers.inkCanvas.drawClipped(title, markerX + paints.dp(8f), baseline, weekColW + (segment.endCol + 1) * colW - paints.dp(1f), paint)
            }
        }
        val hidden = layout.hiddenPerColumn(visible)
        val morePaint = paints.text(10f, alpha = 0.7f, align = if (moreBesideNumber) Paint.Align.RIGHT else Paint.Align.LEFT)
        val moreBaseline = if (moreBesideNumber) numberBaseline else lanesTop + visible * laneH + morePaint.textSize
        for (col in 0 until 7) {
            if (hidden[col] == 0) continue
            val x = if (moreBesideNumber) weekColW + (col + 1) * colW - paints.dp(3f) else weekColW + col * colW + paints.dp(3f)
            layers.inkCanvas.drawText("+${hidden[col]}", x, moreBaseline, morePaint)
        }
    }

    /**
     * How entries fit under the day number, the same for every week of a widget: titles with "+n" on
     * a line of its own, one title with "+n" beside the number ([compact]), or only thin color lines
     * when not even one title fits ([lines]), like Samsung's compact month.
     */
    private class Lanes(paints: WidgetPaints, available: Float, columnWidth: Float) {
        val textPaint = paints.text(10.5f, alpha = 0.92f)
        val height = textPaint.textSize * 1.25f + paints.dp(2f)
        val max = floor((available - paints.dp(1f)) / height).toInt().coerceAtLeast(0)
        val lines = max < 1 || columnWidth < paints.dp(MIN_TITLE_COLUMN_DP)
        val compact = !lines && max < 2
        val lineHeight = paints.dp(3f)
        val lineStep = lineHeight + paints.dp(2f)
        /** Color lines that fit completely; none when the cell has no room below its number. */
        val maxLines = if (available < lineHeight) 0 else (floor((available - lineHeight) / lineStep).toInt() + 1).coerceAtMost(MAX_LINES)
    }

    private fun drawLines(layers: WidgetLayers, layout: WeekLayout, paints: WidgetPaints, lanes: Lanes, weekColW: Float, colW: Float, top: Float, rowBottom: Float) {
        val inset = paints.dp(3f)
        for (segment in layout.segments) {
            if (segment.lane >= lanes.maxLines) continue
            val y = top + segment.lane * lanes.lineStep
            if (y + lanes.lineHeight > rowBottom) continue
            val left = weekColW + segment.startCol * colW + if (segment.continuesBefore) 0f else inset
            val right = weekColW + (segment.endCol + 1) * colW - if (segment.continuesAfter) 0f else inset
            val color = CalendarColor.fromKey(segment.entry.colorKey).base(dark = false).toArgb()
            layers.colorCanvas.drawRoundRect(RectF(left, y, right, y + lanes.lineHeight), lanes.lineHeight / 2f, lanes.lineHeight / 2f, paints.fill(color))
        }
    }

    private const val MIN_COLUMN_WITH_WEEK_NUMBERS_DP = 26f
    private const val MIN_TITLE_COLUMN_DP = 30f
    private const val MAX_LINES = 4
    private const val OUTSIDE_MONTH_ALPHA = 0.35f

    /** All-day bars are a translucent tint of the calendar color: pastel on a light card, muted on a dark one. */
    private const val CHIP_TINT = 0.32f
}
