// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import java.text.Normalizer

/**
 * Which emoji a word names.
 *
 * The index is Unicode's own name per emoji, compiled by tools/build_emoji.py. A query matches
 * a name it equals, then a name one of whose words begins with it, then a name that merely
 * contains it, in that order; within an order the index's own order stands, which is the
 * palette order. Case and accents are folded on both sides.
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

    /** The emoji [query] names, best first, at most [limit]. Empty for a query too short. */
    fun matches(query: String, index: List<Entry>, limit: Int): List<String> {
        val folded = fold(query).trim()
        if (folded.length < MIN_QUERY_LETTERS || limit <= 0) {
            return emptyList()
        }
        val exact = ArrayList<String>()
        val wordStart = ArrayList<String>()
        val inside = ArrayList<String>()
        for (entry in index) {
            val name = entry.name
            when {
                name == folded -> exact += entry.emoji
                name.startsWith(folded) || name.contains(" $folded") -> wordStart += entry.emoji
                name.contains(folded) -> inside += entry.emoji
            }
            if (exact.size >= limit) {
                break
            }
        }
        return (exact + wordStart + inside).take(limit)
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            .lowercase()
}
