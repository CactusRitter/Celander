package com.celander.app.event

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.celander.app.R
import com.celander.app.core.model.EventTiming
import com.celander.app.core.ui.Formats
import com.celander.app.testing.BERLIN
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Rules are only spelled out when the description says everything they do. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RecurrenceTextTest {
    private val resources = ApplicationProvider.getApplicationContext<Application>().resources
    private val custom = resources.getString(R.string.repeat_custom)
    private val formats = Formats(Locale.ENGLISH, use24h = true)

    private fun describe(rrule: String, start: LocalDate) =
        RecurrenceText.describe(resources, rrule, EventTiming.AllDay(start, start), formats, BERLIN)

    @Test
    fun aYearlyRuleWithItsOwnMonthAndDayIsYearly() {
        assertNotEquals(custom, describe("FREQ=YEARLY;BYMONTHDAY=26;BYMONTH=10", LocalDate.of(2026, 10, 26)))
    }

    @Test
    fun aMonthDayWithoutAMonthRepeatsMonthlySoItIsNotCalledYearly() {
        assertEquals(custom, describe("FREQ=YEARLY;BYMONTHDAY=26", LocalDate.of(2026, 10, 26)))
    }

    @Test
    fun weekdaysOfADailyRuleAreNotEveryDay() {
        assertEquals(custom, describe("FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR", LocalDate.of(2026, 10, 26)))
    }

    @Test
    fun aWeekdayAndAMonthDayTogetherAreCustom() {
        assertEquals(custom, describe("FREQ=MONTHLY;BYDAY=FR;BYMONTHDAY=13", LocalDate.of(2026, 11, 13)))
    }
}
