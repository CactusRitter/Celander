package com.celander.app.search

/** Turns what the user typed into an FTS4 prefix query. */
object FtsQuery {
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private const val MAX_TERMS = 8

    /**
     * Every word must match as a prefix, e.g. `Zahn arzt` → `zahn* arzt*`. Lowercase terms keep
     * FTS operators (AND, OR, NOT, NEAR) from being interpreted; null if nothing searchable remains.
     */
    fun build(input: String): String? {
        val terms = input.split(separators).filter { it.isNotBlank() }.take(MAX_TERMS)
        if (terms.isEmpty()) return null
        return terms.joinToString(" ") { it.lowercase() + "*" }
    }
}
