// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

/**
 * One candidate's score, term by term, as `Engine::explainScore` fills it: [SLOTS] floats in the
 * order [fromSlots] reads them. Terms are natural log-probabilities and penalties; a higher total
 * ranks higher.
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
    /** The walk's edit cost to the word, in key widths. */
    val editCost: Float,
    /** How many edits the walk took. */
    val edits: Int,
    /** How many characters the word runs on past the last one typed. */
    val runOn: Int,
    /** What the edits cost on the strip, what needing one cost on top, what the run-on cost. */
    val editPenalty: Float,
    val surcharge: Float,
    val completion: Float,
) {
    companion object {
        const val SLOTS = 15

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
            editCost = values[9],
            edits = values[10].toInt(),
            runOn = values[11].toInt(),
            editPenalty = values[12],
            surcharge = values[13],
            completion = values[14],
        )
    }
}
