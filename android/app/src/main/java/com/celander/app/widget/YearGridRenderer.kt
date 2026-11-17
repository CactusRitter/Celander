package com.celander.app.widget

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import com.celander.app.calendar.CalendarWindow
import com.celander.app.core.time.CalendarMath
import com.celander.app.designsystem.CalendarColor
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.min

/** What the year widget shows. */
internal data class YearGridSpec(
    val year: Int,
    val today: LocalDate,
    val firstDay: DayOfWeek,
    val window: CalendarWindow?,
    val monthNames: List<String>,
)

/**
 * Draws the twelve months of the year widget, in the style of the month widget: the current month
 * in the app color, today as an inverted badge, a dot in the calendar color under every day with
 * entries, past months faded. Too small for digits, the months become a dot pattern.
 */
internal object YearGridRenderer {
    /** Months per row: four on a wide widget, three on a tall one. */
    fun columns(width: Int, height: Int): Int = if (width > height * WIDE_RATIO) 4 else 3

    fun render(spec: YearGridSpec, width: Int, height: Int, paints: WidgetPaints): WidgetLayers {
        val layers = WidgetLayers(width, height, withAccent = true)
        val columns = columns(width, height)
        val rows = 12 / columns
        val cellW = width / columns.toFloat()
        val cellH = height / rows.toFloat()
        val pad = paints.dp(4f)
        val titlePaint = paints.text(11f, WidgetPaints.Weight.BOLD)
        val currentMonth = YearMonth.from(spec.today)
        for (index in 0 until 12) {
            val month = YearMonth.of(spec.year, index + 1)
            val left = (index % columns) * cellW
            val top = (index / columns) * cellH
            val titleBaseline = top + pad + titlePaint.textSize
            val titleCanvas = if (month == currentMonth) layers.accentCanvas!! else layers.inkCanvas
            titleCanvas.drawText(spec.monthNames[index], left + pad + paints.dp(2f), titleBaseline, titlePaint)

            val gridTop = titleBaseline + paints.dp(4f)
            val rowH = (top + cellH - pad - gridTop) / GRID_ROWS
            val colW = (cellW - 2 * pad) / 7f
            val showDigits = rowH >= paints.dp(MIN_DIGIT_ROW_DP)
            val digitPaint = paints.text(8f, WidgetPaints.Weight.MEDIUM, alpha = 0.85f, align = Paint.Align.CENTER).apply {
                textSize = min(textSize, rowH * 0.62f)
            }
            val start = CalendarMath.monthGridStart(month, spec.firstDay)
            for (cell in 0 until GRID_ROWS * 7) {
                val date = start.plusDays(cell.toLong())
                if (YearMonth.from(date) != month) continue
                val cx = left + pad + colW * (cell % 7 + 0.5f)
                val cy = gridTop + rowH * (cell / 7 + 0.5f)
                val baseline = cy + digitPaint.textSize * 0.35f
                if (date == spec.today) {
                    val r = min(colW, rowH) * 0.5f
                    layers.inkCanvas.drawCircle(cx, cy, r, paints.fill())
                    if (showDigits) layers.inkCanvas.drawText(date.dayOfMonth.toString(), cx, baseline, paints.cutOut(digitPaint))
                } else if (showDigits) {
                    val canvas = if (date.dayOfWeek == DayOfWeek.SUNDAY) layers.sundayCanvas else layers.inkCanvas
                    canvas.drawText(date.dayOfMonth.toString(), cx, baseline, digitPaint)
                }
                val colorKey = spec.window?.day(date)?.let { day -> (day.allDay + day.timed).firstOrNull()?.colorKey ?: day.tasks.firstOrNull()?.colorKey }
                val dotRadius = min(colW, rowH) * 0.3f
                if (!showDigits && date != spec.today) {
                    // Without digits every day is a faint dot, so the month keeps its shape.
                    layers.inkCanvas.drawCircle(cx, cy, dotRadius * 0.7f, paints.fill(alpha = 0.3f))
                }
                if (colorKey != null && date != spec.today) {
                    val dot = paints.fill(CalendarColor.fromKey(colorKey).base(dark = false).toArgb())
                    if (showDigits) layers.colorCanvas.drawCircle(cx, cy + rowH * 0.42f, paints.dp(1.3f), dot)
                    else layers.colorCanvas.drawCircle(cx, cy, dotRadius, dot)
                }
            }
            if (spec.year < currentMonth.year || spec.year == currentMonth.year && month < currentMonth) {
                layers.fade(RectF(left, top, left + cellW, top + cellH), PAST_ALPHA)
            }
        }
        return layers
    }

    private const val GRID_ROWS = 6
    private const val WIDE_RATIO = 1.15f
    private const val MIN_DIGIT_ROW_DP = 8.5f
    private const val PAST_ALPHA = 0.55f
}
