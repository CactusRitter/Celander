package com.celander.app.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RubberBandTest {
    private val dimension = 2000f

    @Test
    fun resistanceGrowsButNeverPassesTheContainer() {
        val pulls = listOf(0f, 50f, 200f, 500f, 2000f, 10_000f, 1_000_000f)
        val shown = pulls.map { rubberBand(it, dimension) }
        assertTrue(shown.zipWithNext().all { (a, b) -> b > a || b == a && a == 0f })
        assertTrue(shown.all { it < dimension })
        // A pull of 500 px moves the content by less than half of it (UIScrollView's curve).
        assertEquals(241.8f, rubberBand(500f, dimension), 0.5f)
    }

    @Test
    fun pullsInBothDirectionsAreSymmetric() {
        assertEquals(-rubberBand(300f, dimension), rubberBand(-300f, dimension), 0.001f)
    }

    @Test
    fun inverseRestoresTheFingerTravel() {
        for (pull in listOf(1f, 80f, 640f, 3000f, -450f)) {
            assertEquals(pull, rubberBandInverse(rubberBand(pull, dimension), dimension), 0.01f * kotlin.math.abs(pull) + 0.01f)
        }
    }

    @Test
    fun noContainerMeansNoStretch() {
        assertEquals(0f, rubberBand(300f, 0f))
        assertEquals(0f, rubberBandInverse(30f, 0f))
    }
}
