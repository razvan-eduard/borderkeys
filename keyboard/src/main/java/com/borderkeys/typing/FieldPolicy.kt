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
    /**
     * Where the taps land is learned, and the learned patterns count: [personalAllowed], and the
     * Heatmap switch on.
     */
    val heatmapAllowed: Boolean,
    /**
     * The user has unlocked since boot, so the personal dictionary, the clipboard history and
     * the settings can be read and written.
     */
    val userUnlocked: Boolean = true,
) {
    /** This policy with the Learning switch at [learning] and the Heatmap switch at [heatmap]. */
    fun withSwitches(learning: Boolean, heatmap: Boolean): FieldPolicy {
        val personal = learning && !privateField && userUnlocked
        return copy(personalAllowed = personal, heatmapAllowed = personal && heatmap)
    }

    /** This policy once the user has unlocked, the switches at [learning] and [heatmap]. */
    fun unlocked(learning: Boolean, heatmap: Boolean): FieldPolicy =
        copy(userUnlocked = true).withSwitches(learning, heatmap)

    companion object {
        /** Before any field has started. */
        val NONE = FieldPolicy(
            suggestionsAllowed = true,
            privateField = false,
            personalAllowed = true,
            verbatim = false,
            heatmapAllowed = true,
        )

        /**
         * The policy of a field: [passwordField] allows no dictionary words and takes the keys
         * verbatim, [privateField] allows nothing personal, the personal dictionary needs
         * [learningEnabled] and [userUnlocked] as well, and the heatmap needs [heatmapEnabled] on
         * top.
         */
        fun of(
            passwordField: Boolean,
            privateField: Boolean,
            learningEnabled: Boolean,
            heatmapEnabled: Boolean,
            userUnlocked: Boolean = true,
        ) = FieldPolicy(
            suggestionsAllowed = !passwordField,
            privateField = privateField,
            personalAllowed = learningEnabled && !privateField && userUnlocked,
            verbatim = passwordField,
            heatmapAllowed = learningEnabled && !privateField && userUnlocked && heatmapEnabled,
            userUnlocked = userUnlocked,
        )
    }
}
