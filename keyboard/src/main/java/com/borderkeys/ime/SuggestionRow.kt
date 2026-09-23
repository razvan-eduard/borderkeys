// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.predict.Candidate

/**
 * Where each word sits on the suggestion strip, and which two of them are marked.
 *
 * Two chips on that row are not suggestions in the ordinary sense, and the arrangement exists to
 * keep them findable without reading:
 *
 *  - **What was typed**, first and always first. It is the one chip whose text the user already
 *    knows, so it is the one they should never have to search for -- and a chip that moves as
 *    the number of candidates changes is a chip nobody can aim at.
 *  - **The correction**, in the middle, outlined -- the word a delimiter would put in place of
 *    what you typed. Present only when there is a correction to apply; the typed word itself is
 *    never outlined. The ends are the worst place for it, one being the typed word and the
 *    other being the slot nobody reads.
 *
 * Its own object, and pure, for the same reason as [AutoCorrection]: this is a decision, not a
 * drawing detail, and a decision is worth testing without a view, an editor or a dictionary.
 *
 * Not thread safe and does not need to be: written and read on the UI thread, once per round of
 * suggestions. Reused rather than returned fresh, so a keystroke allocates nothing here.
 */
internal class SuggestionRow {

    /** The slot holding exactly what was typed, or -1 when the row does not carry it. */
    var typedIndex: Int = -1
        private set

    /**
     * The slot holding the correction a delimiter would apply, or -1 when there is none.
     *
     * Always a correction, never the typed word: what was typed is [typedIndex], italic and
     * unmarked. -1 whenever nothing is being corrected -- the word is known, auto-correction is
     * off, the word is too short, or the row is too narrow to hold the correction behind the
     * typed word.
     */
    var appliedIndex: Int = -1
        private set

    /**
     * The row as it should be drawn: the typed word first, the correction outlined in the
     * middle, and the engine's own order behind them.
     *
     * [correction] is the exact text a delimiter would commit -- [AutoCorrection]'s own answer,
     * already cased -- or null when nothing would be replaced. The word itself and not a flag,
     * because the two cannot be derived from each other: autocorrect reads the corrections heap
     * and this row is the ranked one, and a word can top either without appearing in the other
     * at all. This took "putem" outlined on the row while the space bar committed "out", a word
     * that was never on it -- the row promising one thing and the delimiter doing another.
     *
     * So the outlined chip is placed from this value rather than found by position, and is
     * inserted when the row does not already carry it. Passing the word makes the two agree by
     * construction; passing a boolean made them agree only by coincidence.
     */
    fun arrange(
        candidates: List<Candidate>,
        typed: String,
        limit: Int,
        correction: String?,
    ): List<Candidate> {
        typedIndex = -1
        appliedIndex = -1
        // A list has no capacity to bound against the way the reused array did; the caller's
        // limit is already the smaller of the setting and the slots the strip can draw.
        val cap = limit
        if (cap <= 0) {
            return emptyList()
        }
        if (typed.isEmpty()) {
            // Predictions for what comes next rather than candidates for a word in progress:
            // nothing was typed, so nothing is marked and the engine's order stands.
            return candidates.take(cap)
        }

        val row = ArrayList(candidates.take(cap))
        val at = row.indexOfFirst { it.text == typed }
        if (at >= 0) {
            row.add(0, row.removeAt(at))
        } else {
            // Not offered, so it is added, pushing the rest along and dropping whatever falls
            // off the end. The engine's best is never what falls off: it moves to slot one.
            row.add(0, Candidate(typed))
            while (row.size > cap) {
                row.removeAt(row.size - 1)
            }
        }
        typedIndex = 0

        if (correction == null) {
            // No correction, no outline. A delimiter commits what was typed letter for letter,
            // and the typed chip -- italic, first -- is the whole of what the row has to say.
            return row
        }
        // The middle, because the ends are the worst place for it: one is the typed word and the
        // other is the slot nobody reads. A one-slot row has no middle, so the correction is
        // simply not shown -- and nothing is outlined, rather than the wrong thing being.
        val middle = (cap / 2).coerceAtMost(row.size.coerceAtLeast(2) - 1)
        if (middle < 1) {
            return row
        }
        placeCorrection(row, correction, middle, cap)
        appliedIndex = middle
        return row
    }

    /**
     * Puts the correction in [slot], carrying [text] -- what a delimiter will actually commit.
     *
     * Found by [Candidate.isCorrection], which the engine set, rather than by looking for [text]
     * among the words: the row is cased for display and the two rankings behind it do not share
     * an identity, so a word could match by meaning and not by letters. Moved when the row
     * already carries it and inserted when it does not -- and it often does not, because the
     * corrections heap is not this list. Inserting grows the row by one where there is room and
     * otherwise drops whatever was last, which is the slot nobody reads.
     */
    private fun placeCorrection(
        row: ArrayList<Candidate>,
        text: String,
        slot: Int,
        cap: Int,
    ) {
        // By the engine's mark first, and by the letters second. The two rankings do not share
        // an identity, so the engine can leave nothing marked -- its choice need not be in the
        // strip's own sixteen -- while the row already carries that very word from the other
        // heap. Inserting then drew the same word twice, one chip outlined and one not.
        var at = row.indexOfFirst { it.isCorrection }
        if (at < 0) {
            at = row.indexOfFirst { it.text == text }
        }
        if (at > 0) {
            val marked = row.removeAt(at)
            row.add(slot, marked.copy(text = text, isCorrection = true))
            return
        }
        if (at == 0) {
            // The typed chip is itself what a delimiter commits, so there is nothing to outline
            // elsewhere and nothing to add: a second chip here would repeat slot zero.
            return
        }
        row.add(slot, Candidate(text, isCorrection = true))
        while (row.size > cap) {
            row.removeAt(row.size - 1)
        }
    }

}
