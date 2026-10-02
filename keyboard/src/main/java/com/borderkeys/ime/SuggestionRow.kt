// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.predict.Candidate

/**
 * Where each word sits on the suggestion strip: what was typed first, the correction in the
 * middle. Not thread safe; used on the UI thread.
 */
internal class SuggestionRow {

    /** The slot holding exactly what was typed, or -1 when the row does not carry it. */
    var typedIndex: Int = -1
        private set

    /** The slot holding the correction a delimiter would apply, or -1. */
    var appliedIndex: Int = -1
        private set

    /**
     * The row as drawn: the typed word first, the correction in the middle, the engine's order
     * behind them. [correction] is the text a delimiter would commit, already cased, or null; it
     * is inserted when the row does not carry it. [revertable] is the word a pending correction
     * replaced; with nothing typed it takes the first slot as the typed chip.
     */
    fun arrange(
        candidates: List<Candidate>,
        typed: String,
        limit: Int,
        correction: String?,
        revertable: String? = null,
    ): List<Candidate> {
        typedIndex = -1
        appliedIndex = -1
        val cap = limit
        if (cap <= 0) {
            return emptyList()
        }
        if (typed.isEmpty()) {
            if (!revertable.isNullOrEmpty()) {
                typedIndex = 0
                return listOf(Candidate(revertable)) + candidates.take(cap - 1)
            }
            // Nothing typed: next-word predictions in the engine's order, nothing marked.
            return withoutDoubles(candidates).take(cap)
        }

        val row = ArrayList(withoutDoubles(candidates).take(cap))
        val at = row.indexOfFirst { it.text == typed }
        if (at >= 0) {
            row.add(0, row.removeAt(at))
        } else {
            // The typed word, when not offered, is added first.
            row.add(0, Candidate(typed))
            while (row.size > cap) {
                row.removeAt(row.size - 1)
            }
        }
        typedIndex = 0

        if (correction == null) {
            // No correction, no outline.
            return row
        }
        // The correction goes in the middle; a one-slot row shows none.
        val middle = (cap / 2).coerceAtMost(row.size.coerceAtLeast(2) - 1)
        if (middle < 1) {
            return row
        }
        placeCorrection(row, correction, middle, cap)
        appliedIndex = middle
        return row
    }

    /**
     * The candidates with each text once, in order; the first copy takes a later copy's
     * correction mark.
     */
    fun withoutDoubles(candidates: List<Candidate>): List<Candidate> {
        val kept = ArrayList<Candidate>(candidates.size)
        for (candidate in candidates) {
            val at = kept.indexOfFirst { it.text == candidate.text }
            if (at < 0) {
                kept += candidate
            } else if (candidate.isCorrection && !kept[at].isCorrection) {
                kept[at] = kept[at].copy(isCorrection = true)
            }
        }
        return kept
    }

    /**
     * Puts the correction, carrying [text], in [slot]: moved there when the row carries it, else
     * inserted, dropping the last word when the row is full.
     */
    private fun placeCorrection(
        row: ArrayList<Candidate>,
        text: String,
        slot: Int,
        cap: Int,
    ) {
        // Found by its letters.
        val at = row.indexOfFirst { it.text == text }
        if (at > 0) {
            val marked = row.removeAt(at)
            row.add(slot, marked.copy(text = text, isCorrection = true))
            return
        }
        if (at == 0) {
            // The typed chip already carries the correction's text.
            return
        }
        row.add(slot, Candidate(text, isCorrection = true))
        while (row.size > cap) {
            row.removeAt(row.size - 1)
        }
    }

}
