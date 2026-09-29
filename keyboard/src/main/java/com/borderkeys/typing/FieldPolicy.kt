// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

/** What the field being typed into allows, from its type and the settings. */
data class FieldPolicy(
    /** Dictionary words may be suggested, corrected, swiped or offered on the ring. */
    val suggestionsAllowed: Boolean,
    /**
     * Nothing about the user may be read or written: no learning, no clipboard history, no
     * personal dictionary, no assistant.
     */
    val privateField: Boolean,
    /** The personal dictionary is recorded and consulted. */
    val personalAllowed: Boolean,
    /** The keys go in exactly as typed: no correction or rewrite, no space added or removed. */
    val verbatim: Boolean,
) {
    /** This policy with the Learning switch at [enabled]. */
    fun withLearning(enabled: Boolean): FieldPolicy =
        copy(personalAllowed = enabled && !privateField)

    companion object {
        /** Before any field has started. */
        val NONE = FieldPolicy(
            suggestionsAllowed = true,
            privateField = false,
            personalAllowed = true,
            verbatim = false,
        )

        /**
         * The policy of a field: [passwordField] allows no dictionary words and takes the keys
         * verbatim, [privateField] allows nothing personal, and the personal dictionary needs
         * [learningEnabled] as well.
         */
        fun of(passwordField: Boolean, privateField: Boolean, learningEnabled: Boolean) = FieldPolicy(
            suggestionsAllowed = !passwordField,
            privateField = privateField,
            personalAllowed = learningEnabled && !privateField,
            verbatim = passwordField,
        )
    }
}
