// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.assist

/**
 * What the text assistant can be asked to do, and exactly how it is asked.
 *
 * A closed list of transformations, plus one entry -- [CUSTOM] -- that carries an instruction the
 * user wrote. That is a deliberate reversal of what this file used to promise, and it is worth
 * being clear about what was traded. The old rule was that a user could be told precisely what is
 * sent to the model, because every instruction was a constant here. With a custom prompt, half of
 * the instruction is theirs. What has not changed is that the model runs on this device and the
 * text goes nowhere, so the cost is predictability rather than privacy: a prompt someone writes
 * badly gets a bad answer, and that is the whole of it.
 *
 * The user's words never become the whole instruction. [CUSTOM_PREFIX] is glued in front of them,
 * so the model is always told what kind of job this is and what shape the answer must take. That
 * matters most when the text came from a clipboard -- somebody else's writing, which can contain
 * something that reads like an instruction.
 *
 * The instructions are written flat and imperative rather than as a persona. Small
 * instruction-tuned models follow a concrete request far more reliably than a role, and several
 * of the candidate models have no system turn at all -- the native side builds one user turn
 * (see text_assist.cpp), so a "system prompt" would have nowhere to go.
 */
/**
 * What is glued in front of an instruction the user wrote. See [AssistTask.customInstruction].
 */
private const val CUSTOM_PREFIX =
    "Apply the following instruction to the text below. Change only the text. " +
        "Do not answer questions about it, do not comment on it, do not add a preamble. " +
        "Reply with the resulting text and nothing else."

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
    /**
     * Whether splitting the input into chunks and running each one separately still produces a
     * correct answer, joined back together.
     *
     * True for anything that transforms each sentence roughly on its own terms -- a translation,
     * a correction, a register change -- where chunk two never needed to know what chunk one
     * said. False for [SUMMARISE], where that is the entire point: three summaries of three
     * chunks are three summaries, not one summary of the whole, and stitching them together
     * would read as a summary that repeats itself once per chunk. False for [CUSTOM] too, for
     * the same reason -- an instruction someone wrote by hand could easily be a summarising one,
     * and nothing here can tell the two apart to know it should refuse chunking anyway.
     */
    val isChunkable: Boolean = false,
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
        isChunkable = true,
    ),
    CORRECT(
        id = 3,
        instruction = "Correct the spelling, grammar and punctuation of the following text. " +
            "Do not rephrase it and do not change its language. " +
            "Reply with the corrected text and nothing else.",
        outputRatio = 1.3f,
        minOutputTokens = 64,
        isChunkable = true,
    ),
    TRANSLATE_TO_ENGLISH(
        id = 4,
        instruction = "Translate the following text into English. " +
            "Reply with the translation and nothing else.",
        outputRatio = 1.5f,
        minOutputTokens = 64,
        isChunkable = true,
    ),
    TRANSLATE_TO_ROMANIAN(
        id = 5,
        instruction = "Translate the following text into Romanian. " +
            "Reply with the translation and nothing else.",
        outputRatio = 1.5f,
        minOutputTokens = 64,
        isChunkable = true,
    ),

    // The remaining four languages the application itself speaks. One entry per target rather
    // than one entry with a language argument, because the id is what crosses the process
    // boundary and a task that means different things depending on a second field is a task
    // whose log line cannot be read.
    TRANSLATE_TO_GERMAN(
        id = 6,
        instruction = "Translate the following text into German. " +
            "Reply with the translation and nothing else.",
        outputRatio = 1.5f,
        minOutputTokens = 64,
        isChunkable = true,
    ),
    TRANSLATE_TO_SPANISH(
        id = 7,
        instruction = "Translate the following text into Spanish. " +
            "Reply with the translation and nothing else.",
        outputRatio = 1.5f,
        minOutputTokens = 64,
        isChunkable = true,
    ),
    TRANSLATE_TO_FRENCH(
        id = 8,
        instruction = "Translate the following text into French. " +
            "Reply with the translation and nothing else.",
        outputRatio = 1.5f,
        minOutputTokens = 64,
        isChunkable = true,
    ),
    TRANSLATE_TO_ITALIAN(
        id = 9,
        instruction = "Translate the following text into Italian. " +
            "Reply with the translation and nothing else.",
        outputRatio = 1.5f,
        minOutputTokens = 64,
        isChunkable = true,
    ),

    REWRITE_CASUAL(
        id = 10,
        instruction = "Rewrite the following text in a casual, friendly register, keeping its " +
            "meaning and its language unchanged. Reply with the rewritten text and nothing else.",
        outputRatio = 1.4f,
        minOutputTokens = 64,
        isChunkable = true,
    ),
    REWRITE_DIRECT(
        id = 11,
        instruction = "Rewrite the following text to be direct and plain, keeping its meaning " +
            "and its language unchanged. Reply with the rewritten text and nothing else.",
        outputRatio = 1.4f,
        minOutputTokens = 64,
        isChunkable = true,
    ),

    /**
     * Fewer words for the same content, which is not what [SUMMARISE] does.
     *
     * A summary is a different text about the original; this is the original with the padding
     * taken out. The instruction says so twice because a small model asked to shorten something
     * will summarise it given the slightest excuse -- and the ratio below is the only one under
     * 1.0, which is the other half of saying it.
     */
    SHORTEN(
        id = 12,
        instruction = "Rewrite the following text using fewer words, keeping its meaning and " +
            "its language unchanged. Do not summarise it and do not leave any of its points " +
            "out. Reply with the shortened text and nothing else.",
        outputRatio = 0.8f,
        minOutputTokens = 48,
        isChunkable = true,
    ),

    /**
     * The user's own instruction, carried in the request rather than stored here.
     *
     * [instruction] is the prefix alone; the service appends what the user wrote. Every other
     * entry's instruction is complete on its own, and this one is deliberately not -- sending
     * this task without a written instruction is a bug, and the service refuses it.
     */
    CUSTOM(
        id = 13,
        instruction = CUSTOM_PREFIX,
        // No way to know what was asked for, so the same allowance a translation gets: enough
        // for a longer answer, still bounded by the ceiling below.
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

        /** As many characters of instruction as a person will type on a phone, and no more. */
        const val MAX_INSTRUCTION_CHARS = 400

        fun fromId(id: Int): AssistTask? = entries.firstOrNull { it.id == id }

        /**
         * The whole instruction for a prompt the user wrote.
         *
         * The prefix does two jobs. It tells the model this is a text transformation and not a
         * conversation, which is what stops "Sure! Here is your text rewritten:" from ending up
         * in somebody's message. And it puts the real instruction ahead of the text, so that
         * text arriving from a clipboard -- somebody else's writing -- reads as material rather
         * than as orders. That is a mitigation and not a fence: a small model can still be
         * talked out of its instruction, and the answer is that nothing leaves the device and
         * every version is one tap from being undone.
         */
        fun customInstruction(written: String): String =
            CUSTOM_PREFIX + " Instruction: " + written.trim()
    }
}
