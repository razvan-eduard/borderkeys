// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

/**
 * The engine's own account of one candidate's score, term by term: what `Engine::explainScore`
 * fills, carried across JNI as [SLOTS] floats in the order [fromSlots] reads them.
 *
 * The terms are natural log-probabilities and penalties, so a higher total ranks higher and
 * every term is at most zero apart from the personal boost.
 */
class ScoreExplanation(
    val total: Float,
    /** The weight of the language pack the word came from. */
    val packWeight: Float,
    /** How common the word is, and how well it follows the words before it. */
    val languageModel: Float,
    /** What the user's own typing of the word adds. */
    val personal: Float,
    /** The cost of the edits and the letters added between what was typed and the word. */
    val editsAndCompletion: Float,
    /** Which of the active packs the word came from, in the order they were enabled. */
    val packIndex: Int,
    /** The word's position in the engine's list, from zero. */
    val rank: Int,
    val editDistance: Int,
    val addedCharacters: Int,
) {
    companion object {
        const val SLOTS = 9

        fun fromSlots(values: FloatArray): ScoreExplanation = ScoreExplanation(
            total = values[0],
            packWeight = values[1],
            languageModel = values[2],
            personal = values[3],
            editsAndCompletion = values[4],
            packIndex = values[5].toInt(),
            rank = values[6].toInt(),
            editDistance = values[7].toInt(),
            addedCharacters = values[8].toInt(),
        )
    }
}
