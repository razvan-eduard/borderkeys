// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.ClipEntry
import java.text.Normalizer

/**
 * Which remembered clips a piece of text appears in.
 *
 * Case and accents are folded on both sides, so "resume" finds "Résumé". Images never match:
 * there is nothing in them to read.
 */
object ClipSearch {

    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            .lowercase()

    /** Whether [entry] is text that contains [foldedQuery]. */
    fun matches(entry: ClipEntry, foldedQuery: String): Boolean =
        !entry.isImage && fold(entry.content).contains(foldedQuery)

    /** The entries [query] appears in, in their own order; every entry when [query] is blank. */
    fun filter(entries: List<ClipEntry>, query: String): List<ClipEntry> {
        val folded = fold(query.trim())
        if (folded.isEmpty()) {
            return entries
        }
        return entries.filter { matches(it, folded) }
    }

    /** Every entry, those [query] appears in first, the order inside each part unchanged. */
    fun rank(entries: List<ClipEntry>, query: String): List<ClipEntry> {
        val folded = fold(query.trim())
        if (folded.isEmpty()) {
            return entries
        }
        val (found, rest) = entries.partition { matches(it, folded) }
        return found + rest
    }

    /** How many of [entries] contain [query]. */
    fun count(entries: List<ClipEntry>, query: String): Int {
        val folded = fold(query.trim())
        if (folded.isEmpty()) {
            return 0
        }
        return entries.count { matches(it, folded) }
    }
}
