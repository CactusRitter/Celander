package com.celander.app.designsystem

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarColorNearestTest {
    @Test
    fun googleCalendarDefaultsMapToMatchingPaletteColors() {
        val expected = mapOf(
            0xFF7986CB.toInt() to CalendarColor.LAVENDER, // Lavender
            0xFF33B679.toInt() to CalendarColor.GREEN, // Sage
            0xFF8E24AA.toInt() to CalendarColor.LAVENDER, // Grape
            0xFFE67C73.toInt() to CalendarColor.RED, // Flamingo
            0xFFF6BF26.toInt() to CalendarColor.MUSTARD, // Banana
            0xFFF4511E.toInt() to CalendarColor.ORANGE, // Tangerine
            0xFF039BE5.toInt() to CalendarColor.SKY, // Peacock
            0xFF616161.toInt() to CalendarColor.GRAPHITE, // Graphite
            0xFF3F51B5.toInt() to CalendarColor.BLUE, // Blueberry
            0xFF0B8043.toInt() to CalendarColor.GREEN, // Basil
            0xFFD50000.toInt() to CalendarColor.RED, // Tomato
        )
        expected.forEach { (argb, color) ->
            assertEquals("#%08X".format(argb), color, CalendarColor.nearest(argb))
        }
    }

    @Test
    fun everyPaletteToneMapsToItself() {
        CalendarColor.entries.forEach { color ->
            assertEquals("${color.key} light", color, CalendarColor.nearest(color.base(dark = false).toArgb()))
            assertEquals("${color.key} dark", color, CalendarColor.nearest(color.base(dark = true).toArgb()))
        }
    }

    @Test
    fun greysMapToGraphite() {
        listOf(0xFFFFFFFF, 0xFF000000, 0xFF808080, 0xFFB3A79D, 0xFF9E9E9E, 0xFF616161, 0xFF2B2D31).forEach { argb ->
            assertEquals("#%08X".format(argb), CalendarColor.GRAPHITE, CalendarColor.nearest(argb.toInt()))
        }
    }

    @Test
    fun alphaIsIgnored() {
        assertEquals(CalendarColor.nearest(0xFF039BE5.toInt()), CalendarColor.nearest(0x00039BE5))
        assertEquals(CalendarColor.nearest(0xFFD50000.toInt()), CalendarColor.nearest(0x80D50000.toInt()))
    }

    @Test
    fun commonColorsLandOnTheirHueFamily() {
        assertEquals(CalendarColor.RED, CalendarColor.nearest(0xFFC62828.toInt()))
        assertEquals(CalendarColor.GREEN, CalendarColor.nearest(0xFF00C000.toInt()))
        assertEquals(CalendarColor.BLUE, CalendarColor.nearest(0xFF2050FF.toInt()))
        assertEquals(CalendarColor.MUSTARD, CalendarColor.nearest(0xFFFFD000.toInt()))
        assertEquals(CalendarColor.TEAL, CalendarColor.nearest(0xFF009688.toInt()))
    }
}
