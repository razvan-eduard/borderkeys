// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * Which already-corrected words to revisit once the conversation's dominant language changes.
 * Holds no InputConnection and makes no native calls: [BorderKeysService] reads the text and asks
 * the engine.
 */
class LanguageSwitchCorrector {

    /**
     * A correction that turned [typedText] into [appliedText], at [startOffset] until
     * [endOffset] in the field, made while [madeUnder] was the detected pack: -1 undecided,
     * [UNKNOWN] before any was observed.
     */
    data class Flag(
        val typedText: String,
        val appliedText: String,
        val startOffset: Int,
        val endOffset: Int,
        val madeUnder: Int,
    )

    /** An edit: replace `[startOffset, endOffset)`, which reads [previousText], with [text]. */
    data class Replacement(
        val startOffset: Int,
        val endOffset: Int,
        val previousText: String,
        val text: String,
    )

    private val flags = ArrayDeque<Flag>()

    /** The detected pack last observed: -1 undecided, [UNKNOWN] before any. */
    var verdict = UNKNOWN
        private set

    /** The last pack observed that was decided, [UNKNOWN] before any. */
    private var lastDecided = UNKNOWN

    /**
     * Tracks a correction just applied, made under [verdict], dropping the oldest past
     * [MAX_TRACKED].
     */
    fun recordCorrection(typedText: String, appliedText: String, startOffset: Int, endOffset: Int) {
        if (flags.size >= MAX_TRACKED) {
            flags.removeFirst()
        }
        flags.addLast(Flag(typedText, appliedText, startOffset, endOffset, verdict))
    }

    /** Forgets everything tracked and observed. */
    fun reset() {
        flags.clear()
        verdict = UNKNOWN
        lastDecided = UNKNOWN
    }

    /** Takes [pack] as the verdict the field opens under, which is no change. */
    fun startUnder(pack: Int) {
        verdict = pack
        if (pack >= 0) {
            lastDecided = pack
        }
    }

    /**
     * Takes [dominantPack] as the verdict; whether it names a pack other than the last one
     * decided. Undecided is no change, so a verdict that passes through it and back is none.
     */
    fun observeDominantPack(dominantPack: Int): Boolean {
        verdict = dominantPack
        if (dominantPack < 0) {
            return false
        }
        val changed = dominantPack != lastDecided
        lastDecided = dominantPack
        return changed
    }

    /**
     * The corrections made under a decided pack other than [pack]; everything tracked is
     * cleared.
     */
    fun snapshot(pack: Int): List<Flag> {
        val tracked = flags.filter { it.madeUnder >= 0 && it.madeUnder != pack }
        flags.clear()
        return tracked
    }

    /**
     * The edits worth making, right to left by [Replacement.startOffset]. [verifiedFlags] are the
     * flags whose live text still reads [Flag.appliedText]; [suggestions] is what autocorrect
     * writes for each flag's [Flag.typedText] as the new pack, in the same order, null where it
     * would leave the word as typed.
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

    companion object {
        /** The most corrections tracked. */
        private const val MAX_TRACKED = 20

        /** Nothing observed yet; distinct from -1, no pack dominant. */
        const val UNKNOWN = -2
    }
}
