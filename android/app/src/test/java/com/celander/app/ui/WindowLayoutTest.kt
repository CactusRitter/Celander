package com.celander.app.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowLayoutTest {
    @Test
    fun phonesAreCompactInBothOrientations() {
        val portrait = WindowLayout(411.dp, 891.dp)
        assertEquals(WidthClass.COMPACT, portrait.widthClass)
        assertFalse(portrait.isWide)
        assertFalse(portrait.isLandscape)
        assertEquals(0.dp, portrait.extraGutter)

        // A phone turned sideways is wider than 600 dp but still a phone: landscape, not wide.
        val landscape = WindowLayout(891.dp, 411.dp)
        assertTrue(landscape.isLandscape)
        assertEquals(WidthClass.EXPANDED, landscape.widthClass)
    }

    @Test
    fun tabletsAreWideWithAPageMargin() {
        val portrait = WindowLayout(800.dp, 1280.dp)
        assertEquals(WidthClass.MEDIUM, portrait.widthClass)
        assertTrue(portrait.isWide)
        assertFalse(portrait.isLandscape)
        assertEquals(16.dp, portrait.extraGutter)

        val landscape = WindowLayout(1280.dp, 800.dp)
        assertEquals(WidthClass.EXPANDED, landscape.widthClass)
        assertTrue(landscape.isLandscape)
    }

    @Test
    fun breakpointsFollowMaterial() {
        assertEquals(WidthClass.COMPACT, WindowLayout(599.dp, 900.dp).widthClass)
        assertEquals(WidthClass.MEDIUM, WindowLayout(600.dp, 900.dp).widthClass)
        assertEquals(WidthClass.MEDIUM, WindowLayout(839.dp, 900.dp).widthClass)
        assertEquals(WidthClass.EXPANDED, WindowLayout(840.dp, 900.dp).widthClass)
    }

    @Test
    fun centeringPaddingKeepsContentReadable() {
        // Narrow: the minimum padding.
        assertEquals(12.dp, centeringPadding(411.dp, 720.dp, 12.dp))
        // Wide: whatever centres a 720 dp column.
        assertEquals(280.dp, centeringPadding(1280.dp, 720.dp, 12.dp))
        assertEquals(12.dp, centeringPadding(744.dp, 720.dp, 12.dp))
    }
}
