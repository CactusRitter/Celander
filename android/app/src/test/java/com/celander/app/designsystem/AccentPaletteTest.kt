package com.celander.app.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AccentPaletteTest {
    private fun assertClose(expected: Color, actual: Color, label: String) {
        val tolerance = 2.5f / 255f
        val close = abs(expected.red - actual.red) < tolerance && abs(expected.green - actual.green) < tolerance && abs(expected.blue - actual.blue) < tolerance
        assertTrue("$label: expected $expected, was $actual", close)
    }

    private fun assertReadable(foreground: Color, background: Color, label: String) {
        val contrast = AccentPalette.contrast(foreground, background)
        assertTrue("$label: contrast $contrast", contrast >= AccentPalette.MIN_CONTRAST)
    }

    @Test
    fun defaultHueReproducesTheHandTunedPalette() {
        val light = celanderColors(dark = false, AccentPalette.DEFAULT_HUE)
        assertClose(Color(0xFF4A52D6), light.accent, "light accent")
        assertClose(Color(0xFFE1E3FF), light.accentSoft, "light soft")
        listOf(Color(0xFFD9D3FF), Color(0xFFFFE2D0), Color(0xFFD3F0E3)).zip(light.washes).forEach { (expected, actual) -> assertClose(expected, actual, "light wash") }

        val dark = celanderColors(dark = true, AccentPalette.DEFAULT_HUE)
        assertClose(Color(0xFF9BA3FF), dark.accent, "dark accent")
        assertClose(Color(0xFF12152E), dark.onAccent, "dark on accent")
        assertClose(Color(0xFF2C3160), dark.accentSoft, "dark soft")
        listOf(Color(0xFF2B3170), Color(0xFF0F4A47), Color(0xFF4A2550)).zip(dark.washes).forEach { (expected, actual) -> assertClose(expected, actual, "dark wash") }
    }

    @Test
    fun everyHueKeepsTextReadableInBothThemes() {
        for (hue in 0 until 360) {
            val light = celanderColors(dark = false, hue.toFloat())
            assertReadable(light.accent, light.background, "light accent on background, hue $hue")
            assertReadable(light.accent, light.accentSoft, "light accent on soft, hue $hue")
            assertReadable(light.onAccent, light.accent, "light on accent, hue $hue")

            val dark = celanderColors(dark = true, hue.toFloat())
            assertReadable(dark.accent, dark.glassTint, "dark accent on glass, hue $hue")
            assertReadable(dark.accent, dark.accentSoft, "dark accent on soft, hue $hue")
            assertReadable(dark.onAccent, dark.accent, "dark on accent, hue $hue")
        }
    }

    @Test
    fun huesWrapAround() {
        assertEquals(celanderColors(dark = false, 10f).accent, celanderColors(dark = false, 370f).accent)
        assertEquals(celanderColors(dark = true, 350f).accent, celanderColors(dark = true, -10f).accent)
    }

    @Test
    fun presetsAreFoundByHue() {
        assertEquals(AccentPalette.presets.first(), AccentPalette.presetFor(AccentPalette.DEFAULT_HUE))
        assertEquals(211f, AccentPalette.presetFor(211.2f)?.hue)
        assertNull(AccentPalette.presetFor(200f))
    }
}
