// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import java.text.Normalizer

/**
 * Which emoji a word names.
 *
 * The index is Unicode's own name per emoji, compiled by tools/build_emoji.py, and beside it
 * the keywords Unicode's CLDR annotations give each emoji in the languages switched on. A query
 * matches a name it equals, then a name one of whose words begins with it, then a keyword it
 * equals or begins a word of, then a name that merely contains it, then a keyword that does;
 * within an order the index's own order stands, which is the palette order. Case and accents
 * are folded on both sides.
 *
 * Pure, so the ranking is testable without a view.
 */
internal object EmojiSearch {

    /** The shortest query worth answering. */
    const val MIN_QUERY_LETTERS = 2

    /** The index: an emoji and its lower-case name. */
    class Entry(val emoji: String, val name: String)

    /** Reads `emoji<TAB>name` lines. Lines without a tab are skipped. */
    fun parse(lines: Sequence<String>): List<Entry> {
        val entries = ArrayList<Entry>()
        for (line in lines) {
            val tab = line.indexOf('\t')
            if (tab <= 0 || tab == line.length - 1) {
                continue
            }
            entries += Entry(line.substring(0, tab), fold(line.substring(tab + 1)))
        }
        return entries
    }

    /**
     * Reads `emoji<TAB>keyword|keyword|...` lines into the folded keywords of each emoji.
     * Lines without a tab, and empty keywords, are skipped.
     */
    fun parseKeywords(lines: Sequence<String>): Map<String, List<String>> {
        val keywords = HashMap<String, List<String>>()
        for (line in lines) {
            val tab = line.indexOf('\t')
            if (tab <= 0 || tab == line.length - 1) {
                continue
            }
            val words = line.substring(tab + 1).split('|')
                .map { fold(it).trim() }
                .filter { it.isNotEmpty() }
            if (words.isNotEmpty()) {
                keywords[line.substring(0, tab)] = words
            }
        }
        return keywords
    }

    /**
     * The emoji [query] names, best first, at most [limit]. Empty for a query too short.
     * [keywords] are the folded keywords per emoji, consulted after the names.
     */
    fun matches(
        query: String,
        index: List<Entry>,
        limit: Int,
        keywords: Map<String, List<String>> = emptyMap(),
    ): List<String> {
        val folded = fold(query).trim()
        if (folded.length < MIN_QUERY_LETTERS || limit <= 0) {
            return emptyList()
        }
        val exact = ArrayList<String>()
        val wordStart = ArrayList<String>()
        val keywordStart = ArrayList<String>()
        val inside = ArrayList<String>()
        val keywordInside = ArrayList<String>()
        for (entry in index) {
            val name = entry.name
            when {
                name == folded -> exact += entry.emoji
                name.startsWith(folded) || name.contains(" $folded") -> wordStart += entry.emoji
                else -> {
                    val words = keywords[entry.emoji]
                    when {
                        words != null && words.any { startsWord(it, folded) } ->
                            keywordStart += entry.emoji
                        name.contains(folded) -> inside += entry.emoji
                        words != null && words.any { it.contains(folded) } ->
                            keywordInside += entry.emoji
                    }
                }
            }
            if (exact.size >= limit) {
                break
            }
        }
        return (exact + wordStart + keywordStart + inside + keywordInside).take(limit)
    }

    /** Whether [query] is [keyword] or begins one of its words. */
    private fun startsWord(keyword: String, query: String): Boolean =
        keyword.startsWith(query) || keyword.contains(" $query") || keyword.contains("-$query")

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            .lowercase()
}
