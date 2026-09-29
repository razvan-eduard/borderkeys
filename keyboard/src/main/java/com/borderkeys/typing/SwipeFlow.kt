// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.predict.Candidate

/**
 * A swipe between its decode and its word: whether a pause already composed a guess, the word a
 * ring resolved without a pick applies, and which words the ring offers.
 */
class SwipeFlow : TypingFlow() {

    /** Whether this swipe's pause composed a word, which the next decode replaces. */
    private var previewComposed = false

    /** The decode's rank one, which a ring resolved without a pick applies, or null. */
    var topWord: String? = null
        private set

    /** The word's reset at each field start forgets the pause's guess. */
    override fun onFieldStarted(field: FieldSession) = Unit

    /** A pause's decode composed its word. */
    fun previewComposed() {
        previewComposed = true
    }

    /** Whether a pause composed a word, which is then forgotten. */
    fun takePreviewComposed(): Boolean {
        val composed = previewComposed
        previewComposed = false
        return composed
    }

    /** The pause's guess is no longer the word being written. */
    fun forgetPreview() {
        previewComposed = false
    }

    fun rememberTopWord(word: String) {
        topWord = word
    }

    fun forgetTopWord() {
        topWord = null
    }

    /**
     * The ring's words: the decode's first
     * [com.borderkeys.data.theme.KeyboardPreferences.radialSuggestionCount], or none for a
     * decode of fewer than two.
     */
    fun wedges(cased: List<Candidate>): List<String> =
        if (cased.size < 2) {
            emptyList()
        } else {
            cased.take(settings.radialSuggestionCount).map { it.text }
        }

    /** Whether rank one holds at least [DECISIVE_SHARE_PER_MILLE] of the decode. */
    fun decisive(cased: List<Candidate>): Boolean =
        cased.size < 2 || cased.first().share >= DECISIVE_SHARE_PER_MILLE

    companion object {
        /** The wedge that carries the word already in the field. */
        const val TRUSTED_WEDGE = 0

        /** The share of a decode, per mille, that makes its rank one decisive. */
        const val DECISIVE_SHARE_PER_MILLE = 500f
    }
}
