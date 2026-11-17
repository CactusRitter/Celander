package com.celander.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsQueryTest {
    @Test
    fun everyWordBecomesALowercasePrefix() {
        assertEquals("zahn* arzt*", FtsQuery.build("  Zahn   ARZT "))
    }

    @Test
    fun operatorsAndPunctuationAreNeutralized() {
        assertEquals("lunch* or* anna*", FtsQuery.build("lunch OR \"anna\"*"))
        assertEquals("café* 2*", FtsQuery.build("Café-2"))
    }

    @Test
    fun nothingSearchableGivesNull() {
        assertNull(FtsQuery.build(" *\"- "))
    }
}
