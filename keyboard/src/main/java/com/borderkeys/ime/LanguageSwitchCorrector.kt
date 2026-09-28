// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * Which already-corrected words to revisit once the conversation's dominant language changes.
 * Holds no InputConnection and makes no native calls: [BorderKeysService] reads the text and asks
 * the engine.
 */
class LanguageSwitchCorrector {

    /** A correction that turned [typedText] into [appliedText], at [startOffset] until
     *  [endOffset] in the field. */
    data class Flag(
        val typedText: String,
        val appliedText: String,
        val startOffset: Int,
        val endOffset: Int,
    )

    /** An edit: replace `[startOffset, endOffset)`, which reads [previousText], with [text]. */
    data class Replacement(
        val startOffset: Int,
        val endOffset: Int,
        val previousText: String,
        val text: String,
    )

    private val flags = ArrayDeque<Flag>()
    private var lastDominantPack = NO_DOMINANT_PACK

    /** Tracks a correction just applied, dropping the oldest past [MAX_TRACKED]. */
    fun recordCorrection(flag: Flag) {
        if (flags.size >= MAX_TRACKED) {
            flags.removeFirst()
        }
        flags.addLast(flag)
    }

    /** Forgets everything tracked. */
    fun reset() {
        flags.clear()
        lastDominantPack = NO_DOMINANT_PACK
    }

    /** Whether [dominantPack] names a pack and differs from the one last observed. */
    fun observeDominantPack(dominantPack: Int): Boolean {
        val previous = lastDominantPack
        lastDominantPack = dominantPack
        return dominantPack >= 0 && dominantPack != previous
    }

    /** Everything tracked, cleared as it is returned. */
    fun snapshot(): List<Flag> {
        val tracked = flags.toList()
        flags.clear()
        return tracked
    }

    /**
     * The edits worth making, right to left by [Replacement.startOffset]. [verifiedFlags] are the
     * flags whose live text still reads [Flag.appliedText]; [suggestions] is the new dominant
     * pack's answer for each flag's [Flag.typedText], in the same order, null where it has none.
     */
    fun resolve(verifiedFlags: List<Flag>, suggestions: List<String?>): List<Replacement> =
        verifiedFlags.zip(suggestions).mapNotNull { (flag, suggestion) ->
            // Cased like the word in the field; a difference of case alone is no edit.
            val cased = suggestion?.let { AutoCorrection.matchCase(flag.appliedText, it) }
            if (cased == null || cased == flag.appliedText) {
                null
            } else {
                Replacement(flag.startOffset, flag.endOffset, flag.appliedText, cased)
            }
        }.sortedByDescending { it.startOffset }

    /**
     * Where a caret at [caret] belongs once [applied] has landed, [applied] being in [resolve]'s
     * order and the text's original offsets: shifted by each edit behind it, and at the end of
     * the new spelling when it sat inside a replaced word.
     */
    fun caretAfter(caret: Int, applied: List<Replacement>): Int {
        var moved = caret
        for (replacement in applied) {
            moved = when {
                replacement.endOffset <= caret ->
                    moved + replacement.text.length - replacement.previousText.length
                replacement.startOffset >= caret -> moved
                else -> replacement.startOffset + replacement.text.length
            }
        }
        return if (moved < 0) 0 else moved
    }

    private companion object {
        /** The most corrections tracked. */
        const val MAX_TRACKED = 20

        /** Nothing observed yet; distinct from -1, no pack dominant. */
        const val NO_DOMINANT_PACK = -2
    }
}
