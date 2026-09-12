// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * Which already-corrected words look wrong once the conversation's language has moved on.
 *
 * [Engine.observeContextLanguage] already decides, natively, which pack the conversation is
 * being written in -- but only ever forward: a run of Romanian evidence at the start of a
 * message does not get revisited once the next several words turn out to be exclusively
 * English. This is that revisit, and only that: it holds no InputConnection and makes no native
 * calls of its own, so it can be tested the same way [AutoCorrection]/[AutoShift] are, on plain
 * data.
 *
 * The split with [BorderKeysService] is deliberate: reading live text and asking the engine for
 * a pack-specific candidate both need to happen off this class (a live connection, and a native
 * call that has to run on the prediction thread) -- what belongs here is deciding, from data
 * already in hand, whether there is anything to check and what to do with the answer once it
 * arrives.
 */
class LanguageSwitchCorrector {

    /** One correction applied while [typedText] was believed to be in a language that may not
     *  have been the right one -- [appliedText] is what actually landed in the field, at
     *  [startOffset] until [endOffset] in the field's own text. */
    data class Flag(
        val typedText: String,
        val appliedText: String,
        val startOffset: Int,
        val endOffset: Int,
    )

    /** One edit worth making: replace `[startOffset, endOffset)`, which currently reads
     *  [previousText], with [text] -- both spellings kept so `Ask` mode has something to show,
     *  not just something to do. */
    data class Replacement(
        val startOffset: Int,
        val endOffset: Int,
        val previousText: String,
        val text: String,
    )

    private val flags = ArrayDeque<Flag>()
    private var lastDominantPack = NO_DOMINANT_PACK

    /** Tracks a correction just applied, dropping the oldest once past [MAX_TRACKED] -- the same
     *  bound [Composer] holds its own version history to, not picked separately. */
    fun recordCorrection(flag: Flag) {
        if (flags.size >= MAX_TRACKED) {
            flags.removeFirst()
        }
        flags.addLast(flag)
    }

    /** Forgets everything tracked -- a new field is a new conversation, and an offset from the
     *  last one means nothing in this one. */
    fun reset() {
        flags.clear()
        lastDominantPack = NO_DOMINANT_PACK
    }

    /**
     * The one check that runs after every completed word: whether [dominantPack] names a real
     * pack and differs from what was last observed here. True is the only case worth spending
     * anything further on -- reading live text and asking the engine for a candidate both cost
     * more than an int comparison, which is why this exists as its own step rather than folded
     * into [snapshot].
     */
    fun observeDominantPack(dominantPack: Int): Boolean {
        val previous = lastDominantPack
        lastDominantPack = dominantPack
        return dominantPack >= 0 && dominantPack != previous
    }

    /** Everything tracked right now, and clears it -- a flip is checked against once. Called
     *  only after [observeDominantPack] says there is something to check. */
    fun snapshot(): List<Flag> {
        val tracked = flags.toList()
        flags.clear()
        return tracked
    }

    /**
     * The edits worth making, right-to-left by [Replacement.startOffset] so applying them in
     * this order never invalidates an offset still to come -- an earlier edit only ever changes
     * text at or after its own position, never before it.
     *
     * [verifiedFlags] is already filtered to the ones whose live text still matches
     * [Flag.appliedText] -- that check needs a live connection this class does not hold, so it
     * is the caller's job, done once before this runs and, for `AUTO_APPLY`, worth doing again
     * immediately before the edit itself: more time passes between [snapshot] and an edit landing
     * than between two calls in the same function. [suggestions] is the new dominant pack's
     * answer for each flag's [Flag.typedText], in the same order, null where it had nothing
     * different to say.
     */
    fun resolve(verifiedFlags: List<Flag>, suggestions: List<String?>): List<Replacement> =
        verifiedFlags.zip(suggestions).mapNotNull { (flag, suggestion) ->
            if (suggestion == null || suggestion == flag.appliedText) {
                null
            } else {
                Replacement(flag.startOffset, flag.endOffset, flag.appliedText, suggestion)
            }
        }.sortedByDescending { it.startOffset }

    private companion object {
        /** Matches [Composer.MAX_VERSIONS] -- both are "how many recent steps are worth
         *  remembering", not two separately-tuned numbers. */
        const val MAX_TRACKED = 20

        /** Distinct from -1 ("no language is dominant right now"), which is itself a real,
         *  observable value [observeDominantPack] must be able to transition into and out of. */
        const val NO_DOMINANT_PACK = -2
    }
}
