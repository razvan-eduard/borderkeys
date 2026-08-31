// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.assist

/**
 * What the text assistant can be asked to do, and exactly how it is asked.
 *
 * A closed list, on purpose. There is no free-form prompt box anywhere in this application: the
 * assistant is a set of transformations applied to text the user selected and an action they
 * tapped, not a chat. That keeps the feature explainable -- a user can be told precisely what is
 * sent to the model, because the instruction is a constant in this file.
 *
 * The instructions are written flat and imperative rather than as a persona. Small
 * instruction-tuned models follow a concrete request far more reliably than a role, and several
 * of the candidate models have no system turn at all.
 */
enum class AssistTask(
    val id: Int,
    val instruction: String,
    /**
     * How much longer than the input the answer is allowed to be, as a multiplier, and a floor.
     * A summary is shorter than its source; a translation is about the same length; a correction
     * is almost exactly the same length. Bounding the output per task is what stops a small
     * model from running to the end of the context window when it loses the thread.
     */
    val outputRatio: Float,
    val minOutputTokens: Int,
) {
    SUMMARISE(
        id = 1,
        instruction = "Summarise the following text in at most three sentences. " +
            "Reply with the summary and nothing else.",
        outputRatio = 0.5f,
        minOutputTokens = 48,
    ),
    REWRITE_FORMAL(
        id = 2,
        instruction = "Rewrite the following text in a formal register, keeping its meaning " +
            "and its language unchanged. Reply with the rewritten text and nothing else.",
        outputRatio = 1.4f,
        minOutputTokens = 64,
    ),
    CORRECT(
        id = 3,
        instruction = "Correct the spelling, grammar and punctuation of the following text. " +
            "Do not rephrase it and do not change its language. " +
            "Reply with the corrected text and nothing else.",
        outputRatio = 1.3f,
        minOutputTokens = 64,
    ),
    TRANSLATE_TO_ENGLISH(
        id = 4,
        instruction = "Translate the following text into English. " +
            "Reply with the translation and nothing else.",
        outputRatio = 1.5f,
        minOutputTokens = 64,
    ),
    TRANSLATE_TO_ROMANIAN(
        id = 5,
        instruction = "Translate the following text into Romanian. " +
            "Reply with the translation and nothing else.",
        outputRatio = 1.5f,
        minOutputTokens = 64,
    ),
    ;

    /** A rough token budget for the answer, from the input's length in characters. */
    fun outputTokenBudget(inputLength: Int): Int {
        // Four characters to a token is the usual rule of thumb across these tokenisers, and
        // being wrong in either direction here costs a slightly early stop or a slightly larger
        // ceiling -- neither of which is worth a tokeniser call to avoid.
        val inputTokens = inputLength / 4
        val budget = (inputTokens * outputRatio).toInt()
        return budget.coerceIn(minOutputTokens, MAX_OUTPUT_TOKENS)
    }

    companion object {
        const val MAX_OUTPUT_TOKENS = 512

        fun fromId(id: Int): AssistTask? = entries.firstOrNull { it.id == id }
    }
}
