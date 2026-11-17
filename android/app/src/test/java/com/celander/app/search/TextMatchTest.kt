package com.celander.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatchTest {
    @Test
    fun everyWordBecomesALowercasePrefix() {
        assertEquals(listOf("zahn", "arzt"), TextMatch.prepare("  Zahn   ARZT ")?.terms)
        assertTrue(TextMatch.matches("  Zahn   ARZT ", "Zahnbehandlung beim Arzttermin"))
        assertFalse(TextMatch.matches("  Zahn   ARZT ", "Zahnbehandlung"))
    }

    @Test
    fun operatorsAndPunctuationAreNeutralized() {
        assertEquals(listOf("lunch", "or", "anna"), TextMatch.prepare("lunch OR \"anna\"*")?.terms)
        assertEquals(listOf("cafe", "2"), TextMatch.prepare("Café-2")?.terms)
        assertFalse(TextMatch.matches("lunch OR anna", "Lunch"))
        assertTrue(TextMatch.matches("lunch OR anna", "Lunch order with Anna"))
    }

    @Test
    fun nothingSearchableMatchesNothing() {
        assertNull(TextMatch.prepare(" *\"- "))
        assertNull(TextMatch.prepare(""))
        assertFalse(TextMatch.matches(" *\"- ", "anything"))
        assertFalse(TextMatch.matches("", ""))
    }

    @Test
    fun termsArePrefixesOfTokensNotSubstrings() {
        assertTrue(TextMatch.matches("arzt", "Arzt"))
        assertTrue(TextMatch.matches("ar", "Arzt"))
        assertFalse(TextMatch.matches("rzt", "Arzt"))
        assertFalse(TextMatch.matches("arzt", "Zahnarzt Termin"))
    }

    @Test
    fun compoundWordIsOneToken() {
        assertTrue(TextMatch.matches("zahn", "Zahnarzt Termin"))
        assertTrue(TextMatch.matches("zahnarzt term", "Zahnarzt Termin"))
        assertFalse(TextMatch.matches("zahn arzt", "Zahnarzt Termin"))
    }

    @Test
    fun umlautsAndAccentsFoldBothWays() {
        assertTrue(TextMatch.matches("arzt", "Ärzte"))
        assertTrue(TextMatch.matches("ärzte", "Arzte"))
        assertTrue(TextMatch.matches("Muller", "Herr Müller"))
        assertTrue(TextMatch.matches("cafe", "Café de Flore"))
        assertTrue(TextMatch.matches("CAFÉ", "cafe"))
        assertTrue(TextMatch.matches("senor", "Señora García"))
        assertTrue(TextMatch.matches("garcia", "Señora García"))
    }

    @Test
    fun decomposedAndComposedInputAgree() {
        val decomposed = "Café Müller"
        assertEquals(listOf("cafe", "muller"), TextMatch.tokens(decomposed))
        assertTrue(TextMatch.matches("café", decomposed))
        assertTrue(TextMatch.matches("Café", "café"))
    }

    @Test
    fun lowercasingIsLocaleIndependent() {
        assertEquals(listOf("istanbul"), TextMatch.tokens("İSTANBUL"))
        assertTrue(TextMatch.matches("istanbul", "İstanbul"))
        assertEquals(listOf("straße"), TextMatch.tokens("STRAßE"))
    }

    @Test
    fun numbersAreTokens() {
        assertTrue(TextMatch.matches("2", "Raum 204"))
        assertTrue(TextMatch.matches("204", "Raum 204"))
        assertFalse(TextMatch.matches("04", "Raum 204"))
        assertTrue(TextMatch.matches("10 30", "Treffen 10:30"))
        assertTrue(TextMatch.matches("b2", "Gate B2"))
        assertEquals(listOf("abc123", "x"), TextMatch.tokens("abc123_x"))
    }

    @Test
    fun termsMayMatchDifferentFieldsAndTheSameToken() {
        assertTrue(TextMatch.matches("team büro", "Team-Meeting", "Büro 3", ""))
        assertTrue(TextMatch.matches("zahn zahnarzt", "Zahnarzt"))
        assertFalse(TextMatch.matches("team büro", "Team-Meeting"))
        assertFalse(TextMatch.matches("team"))
    }

    @Test
    fun atMostEightTermsAreUsed() {
        val query = TextMatch.prepare("a b c d e f g h nine")!!
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g", "h"), query.terms)
        assertTrue(query.matches("a b c d e f g h"))
    }

    @Test
    fun preparedQueryIsReusable() {
        val query = TextMatch.prepare("arzt")!!
        assertTrue(query.matches("Ärztehaus"))
        assertFalse(query.matches("Zahnarzt"))
        assertTrue(query.matches("Notiz", "beim Arzt"))
    }

    @Test
    fun nonLatinScriptsStayWhole() {
        assertTrue(TextMatch.matches("москв", "Поездка в Москву"))
        assertTrue(TextMatch.matches("東京", "東京出張"))
        assertEquals(1, TextMatch.tokens("नमस्ते!").size)
        assertTrue(TextMatch.matches("नमस्ते", "नमस्ते दुनिया"))
    }
}
