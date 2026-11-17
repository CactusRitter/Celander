package com.celander.app.core.recurrence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RRuleExtrasTest {
    @Test
    fun unknownPartsSurviveParseAndFormat() {
        val rule = RRule.parse("FREQ=YEARLY;BYWEEKNO=20;BYDAY=MO;BYHOUR=9,17;RSCALE=GREGORIAN")!!
        assertEquals(listOf("BYWEEKNO" to "20", "BYHOUR" to "9,17", "RSCALE" to "GREGORIAN"), rule.extras)
        assertEquals("FREQ=YEARLY;BYDAY=MO;BYWEEKNO=20;BYHOUR=9,17;RSCALE=GREGORIAN", rule.format())
        assertEquals(rule, RRule.parse(rule.format()))
    }

    @Test
    fun rulesWithExtrasAreNotSimple() {
        assertTrue(RRule.parse("FREQ=DAILY")!!.isSimple)
        assertFalse(RRule.parse("FREQ=DAILY;BYHOUR=8")!!.isSimple)
    }

    @Test
    fun cappingASeriesKeepsItsExtras() {
        val capped = RRule.parse("FREQ=YEARLY;BYYEARDAY=1,100")!!.copy(until = "20261231T235959Z", count = null)
        assertEquals("FREQ=YEARLY;UNTIL=20261231T235959Z;BYYEARDAY=1,100", capped.format())
    }
}
