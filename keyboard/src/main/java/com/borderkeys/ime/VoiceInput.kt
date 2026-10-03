// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * Which voice keyboard the voice key goes to, from the enabled input methods that offer a voice
 * subtype, by id: the one remembered while the set it was chosen from is unchanged, the only
 * one when there is one, and the system's picker otherwise or on a hold.
 */
object VoiceInput {

    sealed interface Step {
        /** Switch to the keyboard [id]. */
        class Switch(val id: String) : Step

        /** Show the system's keyboard picker. */
        object Picker : Step

        /** No keyboard offers voice typing. */
        object None : Step
    }

    /** The enabled voice keyboards as one string, order aside, to tell a changed set. */
    fun signature(available: List<String>): String = available.sorted().joinToString("|")

    fun decide(available: List<String>, remembered: String, knownSet: String, hold: Boolean): Step {
        if (available.isEmpty()) {
            return Step.None
        }
        if (hold) {
            return Step.Picker
        }
        if (remembered in available && signature(available) == knownSet) {
            return Step.Switch(remembered)
        }
        if (available.size == 1) {
            return Step.Switch(available.single())
        }
        return Step.Picker
    }
}
