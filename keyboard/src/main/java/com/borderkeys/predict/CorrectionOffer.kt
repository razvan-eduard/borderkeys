// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.ime.AutoCorrection.Situation
import com.borderkeys.ime.WordStems

/** One entry of autocorrect's list for a typed word, in the engine's own case. */
sealed class CorrectionOffer {
    abstract val text: String

    /** Whether [text] is a name. */
    abstract val isName: Boolean

    /** [WordStems.shields]'s answer for the typed word against [text]. */
    abstract val inflection: Boolean

    /** The [Situation] this offer is applied as. */
    internal abstract val appliedAs: Situation

    /**
     * Whether [text] stands against the taps: no other reading of them is likelier by more than
     * the engine's evidence ratio. One that does not is left on the strip rather than applied.
     */
    abstract val confident: Boolean

    /**
     * The most edits [text] may be from the typed word, given the distance setting's ceiling
     * [maxEdits] and its ceiling for neighbouring keys alone [maxSlipEdits].
     */
    abstract fun editCeiling(maxEdits: Int, maxSlipEdits: Int): Int
}

/** An entry of the engine's correction list. */
data class ListedCorrection(
    override val text: String,
    override val isName: Boolean,
    override val inflection: Boolean,
    /**
     * Whether the engine reached [text] by neighbouring keys in place of typed ones alone: no
     * letter added, dropped, swapped or run on.
     */
    val slipsOnly: Boolean = false,
    override val confident: Boolean = true,
) : CorrectionOffer() {
    override val appliedAs: Situation get() = Situation.Correctable

    override fun editCeiling(maxEdits: Int, maxSlipEdits: Int): Int =
        if (slipsOnly) maxOf(maxEdits, maxSlipEdits) else maxEdits
}

/**
 * The tap decoder's word: the word the taps fit best, which the engine accepted over the typed
 * letters read as an unknown word. Never a name, and held to no edit ceiling.
 */
data class DecodedCorrection(
    override val text: String,
    override val inflection: Boolean,
) : CorrectionOffer() {
    override val isName: Boolean get() = false

    override val appliedAs: Situation get() = Situation.Decoded

    override val confident: Boolean get() = true

    override fun editCeiling(maxEdits: Int, maxSlipEdits: Int): Int = Int.MAX_VALUE
}
