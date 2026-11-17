package com.celander.app.ui

import com.celander.app.core.ui.Formats
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Texts fall back to English, but the system's regional settings stay in effect. */
class UiLocaleTest {
    @Test
    fun anUntranslatedLanguageKeepsTheRegionAndTheChosenWeekStart() {
        // French with Sunday picked as first day of the week in the regional settings.
        val ui = Formats.uiLocale(Locale.forLanguageTag("fr-FR-u-fw-sun"), "en")

        assertEquals("en", ui.language)
        assertEquals("FR", ui.country)
        assertEquals("sun", ui.getUnicodeLocaleType("fw"))
        assertEquals(DayOfWeek.SUNDAY, WeekFields.of(ui).firstDayOfWeek)
    }

    @Test
    fun theRegionStillDecidesTheWeekStartWithoutAChoice() {
        assertEquals(DayOfWeek.MONDAY, WeekFields.of(Formats.uiLocale(Locale.forLanguageTag("fr-FR"), "en")).firstDayOfWeek)
    }

    @Test
    fun theScriptOfTheSystemLanguageIsDropped() {
        assertEquals(Locale.forLanguageTag("en-TW"), Formats.uiLocale(Locale.forLanguageTag("zh-Hant-TW"), "en"))
    }

    @Test
    fun aTranslatedLanguageKeepsTheSystemLocale() {
        val system = Locale.forLanguageTag("de-AT-u-fw-sun")
        assertSame(system, Formats.uiLocale(system, "de"))
    }
}
