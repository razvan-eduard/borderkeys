// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

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
     * Rearranges [words] in place and returns how many slots are now filled.
     *
     * [correcting] is the caller's answer to "would a delimiter replace this word", which is
     * [AutoCorrection]'s decision and not one to be guessed at from the candidates: it depends
     * on a setting, on the word's length, and on whether the dictionaries spell it.
     */
    fun arrange(
        words: Array<String?>,
        count: Int,
        typed: String,
        limit: Int,
        correcting: Boolean,
    ): Int {
        typedIndex = -1
        appliedIndex = -1
        val cap = limit.coerceAtMost(words.size)
        if (cap <= 0) {
            return 0
        }
        if (typed.isEmpty()) {
            // Predictions for what comes next rather than candidates for a word in progress:
            // nothing was typed, so nothing is marked and the engine's order stands.
            return count.coerceAtMost(cap)
        }

        var shown = count.coerceAtMost(cap)
        var at = -1
        for (index in 0 until shown) {
            if (words[index] == typed) {
                at = index
                break
            }
        }
        if (at >= 0) {
            moveToFront(words, at)
        } else {
            // Not offered, so it is added, pushing the rest along and dropping whatever falls
            // off the end. The engine's best is never what falls off: it moves to slot one.
            shown = (count + 1).coerceAtMost(cap)
            var index = shown - 1
            while (index > 0) {
                words[index] = words[index - 1]
                index--
            }
            words[0] = typed
        }
        typedIndex = 0

        if (!correcting) {
            // No correction, no outline. A delimiter commits what was typed letter for letter,
            // and the typed chip -- italic, first -- is the whole of what the row has to say.
            return shown
        }
        // Correcting means the engine's best is not the typed word, so the move above put it in
        // slot one. From there it goes to the middle -- unless the row is one slot wide, in
        // which case the correction is simply not on the row and nothing is outlined.
        val middle = (cap / 2).coerceAtMost(shown - 1)
        if (middle < 1) {
            return shown
        }
        moveToBack(words, middle)
        appliedIndex = middle
        return shown
    }

    /** Moves the word at [from] to slot zero, shifting the ones before it along. */
    private fun moveToFront(words: Array<String?>, from: Int) {
        val moved = words[from]
        var index = from
        while (index > 0) {
            words[index] = words[index - 1]
            index--
        }
        words[0] = moved
    }

    /** Moves the word in slot one to [to], shifting the ones between it and there along. */
    private fun moveToBack(words: Array<String?>, to: Int) {
        val moved = words[1]
        var index = 1
        while (index < to) {
            words[index] = words[index + 1]
            index++
        }
        words[to] = moved
    }
}
