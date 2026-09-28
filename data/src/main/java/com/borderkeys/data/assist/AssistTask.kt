// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.assist

/** What is put in front of an instruction the user wrote. See [AssistTask.customInstruction]. */
private const val CUSTOM_PREFIX =
    "Apply the following instruction to the text below. Change only the text. " +
        "Do not answer questions about it, do not comment on it, do not add a preamble. " +
        "Use Markdown formatting (like bullet points or bold text) if it helps clarity. " +
        "Reply with the resulting text and nothing else."

/**
 * What the text assistant can be asked to do, and the instruction it is given: a closed list of
 * transformations, plus [CUSTOM], which carries an instruction the user wrote behind
 * [CUSTOM_PREFIX].
 */
enum class AssistTask(
    val id: Int,
    val instruction: String,
    /**
     * How long the answer may be, as a multiple of the request's tokenised size, with
     * [minOutputTokens] as the floor. The ceiling for a task without [usesRemainingContext]; the
     * starting budget for the others.
     */
    val outputRatio: Float,
    val minOutputTokens: Int,
    /** Whether the input may be split into chunks, each run on its own, and the answers joined. */
    val isChunkable: Boolean = false,
    /**
     * Whether generation may run up to the space left in the model's context window rather than
     * stopping at [outputRatio]'s budget.
     */
    val usesRemainingContext: Boolean = false,
    /**
     * Which kind of model does this task best; with more than one model imported, each category
     * can run on its own. See [AssistCategory].
     */
    val category: AssistCategory = AssistCategory.WRITE,
    /**
     * The fewest words of input this task runs on; the draft box greys the button out below it.
     */
    val minWords: Int = 1,
) {
    SUMMARISE(
        id = 1,
        instruction = "Summarise the following text into a clear, bulleted list of the most " +
            "important points. Use Markdown bullet points (-). Reply with the summary and " +
            "nothing else.",
        outputRatio = 0.6f,
        minOutputTokens = 64,
        minWords = 15,
    ),
    REWRITE_FORMAL(
        id = 2,
        instruction = "Rewrite the following text in a formal register, keeping its meaning " +
            "and its language unchanged. Reply with the rewritten text and nothing else.",
        outputRatio = 1.4f,
        minOutputTokens = 64,
        isChunkable = true,
        usesRemainingContext = true,
        minWords = 3,
    ),
    CORRECT(
        id = 3,
        instruction = "Correct the spelling, grammar and punctuation of the following text. " +
            "Do not rephrase it and do not change its language. " +
            "Reply with the corrected text and nothing else.",
        outputRatio = 1.3f,
        minOutputTokens = 64,
        isChunkable = true,
        usesRemainingContext = true,
    ),
    TRANSLATE_TO_ENGLISH(
        id = 4,
        instruction = "Translate the following text into English. " +
            "Reply with the translation and nothing else.",
        outputRatio = 1.5f,
        minOutputTokens = 64,
        isChunkable = true,
        usesRemainingContext = true,
        category = AssistCategory.TRANSLATE,
    ),
    TRANSLATE_TO_ROMANIAN(
        id = 5,
        instruction = "Translate the following text into Romanian. " +
            "Reply with the translation and nothing else.",
        outputRatio = 1.5f,
        minOutputTokens = 64,
        isChunkable = true,
        usesRemainingContext = true,
        category = AssistCategory.TRANSLATE,
    ),

    // The other four languages the application speaks, one entry per target.
    TRANSLATE_TO_GERMAN(
        id = 6,
        instruction = "Translate the following text into German. " +
            "Reply with the translation and nothing else.",
        outputRatio = 1.5f,
        minOutputTokens = 64,
        isChunkable = true,
        usesRemainingContext = true,
        category = AssistCategory.TRANSLATE,
    ),
    TRANSLATE_TO_SPANISH(
        id = 7,
        instruction = "Translate the following text into Spanish. " +
            "Reply with the translation and nothing else.",
        outputRatio = 1.5f,
        minOutputTokens = 64,
        isChunkable = true,
        usesRemainingContext = true,
        category = AssistCategory.TRANSLATE,
    ),
    TRANSLATE_TO_FRENCH(
        id = 8,
        instruction = "Translate the following text into French. " +
            "Reply with the translation and nothing else.",
        outputRatio = 1.5f,
        minOutputTokens = 64,
        isChunkable = true,
        usesRemainingContext = true,
        category = AssistCategory.TRANSLATE,
    ),
    TRANSLATE_TO_ITALIAN(
        id = 9,
        instruction = "Translate the following text into Italian. " +
            "Reply with the translation and nothing else.",
        outputRatio = 1.5f,
        minOutputTokens = 64,
        isChunkable = true,
        usesRemainingContext = true,
        category = AssistCategory.TRANSLATE,
    ),

    REWRITE_CASUAL(
        id = 10,
        instruction = "Rewrite the following text in a casual, friendly register, keeping its " +
            "meaning and its language unchanged. Reply with the rewritten text and nothing else.",
        outputRatio = 1.4f,
        minOutputTokens = 64,
        isChunkable = true,
        usesRemainingContext = true,
        minWords = 3,
    ),
    REWRITE_DIRECT(
        id = 11,
        instruction = "Rewrite the following text to be direct and plain, keeping its meaning " +
            "and its language unchanged. Reply with the rewritten text and nothing else.",
        outputRatio = 1.4f,
        minOutputTokens = 64,
        isChunkable = true,
        usesRemainingContext = true,
        minWords = 3,
    ),

    /** Fewer words for the same content, every point kept; not a summary. */
    SHORTEN(
        id = 12,
        instruction = "Rewrite the following text using fewer words, keeping its meaning and " +
            "its language unchanged. Do not summarise it and do not leave any of its points " +
            "out. Reply with the shortened text and nothing else.",
        outputRatio = 0.8f,
        minOutputTokens = 48,
        isChunkable = true,
        minWords = 5,
    ),

    /**
     * The user's own instruction, carried in the request. [instruction] is the prefix alone; the
     * service appends what the user wrote and refuses the task without it.
     */
    CUSTOM(
        id = 13,
        instruction = CUSTOM_PREFIX,
        outputRatio = 1.5f,
        minOutputTokens = 64,
        usesRemainingContext = true,
    ),
    ;

    companion object {
        /** The ceiling every task's ratio-derived budget is clamped under in `TextAssist::run`. */
        const val MAX_OUTPUT_TOKENS = 512

        /** The longest instruction a user may write. */
        const val MAX_INSTRUCTION_CHARS = 400

        fun fromId(id: Int): AssistTask? = entries.firstOrNull { it.id == id }

        /** The whole instruction for a prompt the user wrote: [CUSTOM_PREFIX], then [written]. */
        fun customInstruction(written: String): String =
            CUSTOM_PREFIX + " Instruction: " + written.trim()
    }
}
