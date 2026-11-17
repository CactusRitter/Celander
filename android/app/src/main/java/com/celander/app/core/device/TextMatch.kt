package com.celander.app.core.device

import java.text.Normalizer
import java.util.Locale

/**
 * In-memory counterpart of the `unicode61` FTS4 tables queried through `FtsQuery`, for rows that
 * live outside Room (device calendar events).
 *
 * Text and query are normalized alike: lowercase in [Locale.ROOT], NFD, non-spacing marks removed,
 * then split on everything that is neither letter, digit nor mark. A query matches when every term
 * is a prefix of some token in any of the fields. Terms are not split further, so `zahn arzt` does
 * not find `Zahnarzt` (one token), exactly like `zahn* arzt*` in FTS4.
 */
object TextMatch {
    private val separators = Regex("[^\\p{L}\\p{N}\\p{M}]+")
    private val nonSpacingMarks = Regex("\\p{Mn}+")
    private const val MAX_TERMS = 8

    /** A parsed query that can be tested against many rows. */
    class Query internal constructor(val terms: List<String>) {
        fun matches(vararg fields: String): Boolean {
            val tokens = fields.flatMap(::tokens)
            return terms.all { term -> tokens.any { it.startsWith(term) } }
        }
    }

    /** The query for [input], or null if nothing searchable remains (as `FtsQuery.build`). */
    fun prepare(input: String): Query? {
        val terms = tokens(input).take(MAX_TERMS)
        return if (terms.isEmpty()) null else Query(terms)
    }

    /** One-off match; an empty query matches nothing. */
    fun matches(query: String, vararg fields: String): Boolean =
        prepare(query)?.matches(*fields) ?: false

    /** Tokens of [text] as the `unicode61` tokenizer with diacritic removal produces them. */
    fun tokens(text: String): List<String> {
        val folded = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(nonSpacingMarks, "")
        return folded.split(separators).filter { it.isNotEmpty() }
    }
}
