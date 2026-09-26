// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.ClipEntry
import java.text.Normalizer

/**
 * Which remembered clips a piece of text appears in.
 *
 * Case and accents are folded on both sides, so "resume" finds "Résumé". Images never match:
 * there is nothing in them to read. A query can also be a pattern: wildcards, where `*` stands
 * for any run of characters and `?` for one, or a regular expression, matched anywhere in the
 * clip, against its folded text and without regard to case.
 */
object ClipSearch {

    /** How a query is read. */
    enum class Mode { PLAIN, WILDCARDS, REGEX }

    /** A query as it is matched: folded text, or a compiled pattern, or a pattern that cannot be read. */
    class Query private constructor(
        val text: String,
        val mode: Mode,
        private val folded: String,
        private val pattern: Regex?,
        /** True for a pattern that cannot be compiled; such a query matches nothing. */
        val invalid: Boolean,
    ) {
        /** Nothing to look for. */
        val empty: Boolean get() = folded.isEmpty() && pattern == null && !invalid

        /** Whether [entry] is text this query finds. */
        fun matches(entry: ClipEntry): Boolean {
            if (entry.isImage || invalid) {
                return false
            }
            val content = fold(entry.content)
            return if (pattern != null) pattern.containsMatchIn(content) else content.contains(folded)
        }

        companion object {
            fun of(text: String, mode: Mode = Mode.PLAIN): Query {
                val trimmed = text.trim()
                if (trimmed.isEmpty()) {
                    return Query(text, mode, "", null, invalid = false)
                }
                return when (mode) {
                    Mode.PLAIN -> Query(text, mode, fold(trimmed), null, invalid = false)
                    Mode.WILDCARDS -> Query(text, mode, "", wildcardsToRegex(fold(trimmed)), invalid = false)
                    Mode.REGEX -> {
                        val compiled = runCatching { Regex(trimmed, RegexOption.IGNORE_CASE) }.getOrNull()
                        Query(text, mode, "", compiled, invalid = compiled == null)
                    }
                }
            }
        }
    }

    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            .lowercase()

    /** Whether [entry] is text that contains [foldedQuery]. */
    fun matches(entry: ClipEntry, foldedQuery: String): Boolean =
        !entry.isImage && fold(entry.content).contains(foldedQuery)

    /** The entries [query] appears in, in their own order; every entry when [query] is blank. */
    fun filter(entries: List<ClipEntry>, query: String): List<ClipEntry> =
        filter(entries, Query.of(query))

    fun filter(entries: List<ClipEntry>, query: Query): List<ClipEntry> {
        if (query.empty) {
            return entries
        }
        return entries.filter { query.matches(it) }
    }

    /** Every entry, those [query] appears in first, the order inside each part unchanged. */
    fun rank(entries: List<ClipEntry>, query: String): List<ClipEntry> {
        val compiled = Query.of(query)
        if (compiled.empty) {
            return entries
        }
        val (found, rest) = entries.partition { compiled.matches(it) }
        return found + rest
    }

    /** How many of [entries] contain [query]. */
    fun count(entries: List<ClipEntry>, query: String): Int = count(entries, Query.of(query))

    fun count(entries: List<ClipEntry>, query: Query): Int {
        if (query.empty) {
            return 0
        }
        return entries.count { query.matches(it) }
    }

    /** `*` as any run of characters, `?` as one, everything else as itself. */
    private fun wildcardsToRegex(pattern: String): Regex {
        val out = StringBuilder()
        for (char in pattern) {
            when (char) {
                '*' -> out.append(".*")
                '?' -> out.append('.')
                else -> out.append(Regex.escape(char.toString()))
            }
        }
        return Regex(out.toString(), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }
}
