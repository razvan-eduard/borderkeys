// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.assist

/**
 * The models this application will load, identified by the SHA-256 of the exact published file.
 *
 * Nothing is downloaded, ever. A model arrives because the user picked a file, and that file is
 * accepted only if its hash matches an entry here. An unknown hash is refused -- not warned
 * about, refused.
 *
 * That is a strong rule and it is worth being clear about what it buys and what it does not. A
 * GGUF file is not a document; it is weights plus metadata that a runtime maps and executes
 * against. This process is handed the user's selected text. Loading an arbitrary file that
 * someone sent them, into that process, on the strength of it having the right extension, is
 * the same class of decision as running an attachment. The hash makes "I got this from the
 * official repository" a checkable claim rather than a hopeful one.
 *
 * It does not make the model trustworthy, only identified. What a given set of weights emits is
 * a separate question, and the answer never leaves the device either way.
 *
 * Every hash below was read from the publishing repository's own file metadata. Adding an entry
 * is a deliberate act with a name attached, which is why this is source rather than a
 * configuration file.
 */
object KnownAssistModels {

    data class Entry(
        val displayName: String,
        val fileName: String,
        val sha256: String,
        val sizeBytes: Long,
        /** SPDX identifier. Only free licences are listed; see docs/licensing.md. */
        val license: String,
        val source: String,
        /** Context window to request. Clamped natively to what the model was trained for. */
        val contextTokens: Int,
        /** Roughly how much RAM the loaded model needs, for the warning in Settings. */
        val approximateRamMb: Int,
    )

    /**
     * All Apache-2.0, and that is not an accident: it means the `plus` flavor can offer a text
     * assistant without carrying a single non-free asset, which the swipe weights could not.
     * A user who imports one of these is running free software end to end.
     */
    val entries: List<Entry> = listOf(
        Entry(
            displayName = "Qwen3 0.6B (Q8_0)",
            fileName = "Qwen3-0.6B-Q8_0.gguf",
            sha256 = "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031",
            sizeBytes = 639_446_688L,
            license = "Apache-2.0",
            source = "huggingface.co/Qwen/Qwen3-0.6B-GGUF",
            contextTokens = 4096,
            approximateRamMb = 900,
        ),
        Entry(
            displayName = "Qwen3 1.7B (Q8_0)",
            fileName = "Qwen3-1.7B-Q8_0.gguf",
            sha256 = "061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a",
            sizeBytes = 1_834_426_016L,
            license = "Apache-2.0",
            source = "huggingface.co/Qwen/Qwen3-1.7B-GGUF",
            contextTokens = 4096,
            approximateRamMb = 2300,
        ),
        Entry(
            displayName = "SmolLM3 3B (Q4_K_M)",
            fileName = "SmolLM3-Q4_K_M.gguf",
            sha256 = "8334b850b7bd46238c16b0c550df2138f0889bf433809008cc17a8b05761863e",
            sizeBytes = 1_915_305_312L,
            license = "Apache-2.0",
            source = "huggingface.co/ggml-org/SmolLM3-3B-GGUF",
            contextTokens = 4096,
            approximateRamMb = 2400,
        ),
        // The three above are general-purpose and trained English-first; a task that asks one of
        // them to write fluently in a less-common language, rather than just read one, is where
        // that shows. EuroLLM is trained for balanced coverage across the EU's own languages
        // instead -- the other reasonable trade for a model this size, at the cost of being
        // weaker outside them. Same publisher, same licence, two sizes for the same reason
        // Qwen3 is offered at two.
        Entry(
            displayName = "EuroLLM 1.7B Instruct (Q8_0)",
            fileName = "EuroLLM-1.7B-Instruct.Q8_0.gguf",
            sha256 = "c3672eb97ff1eeac40f8cb227802467f7ba99264b7df0cf0a86995a4f3faf0ac",
            sizeBytes = 1_763_775_616L,
            license = "Apache-2.0",
            source = "huggingface.co/QuantFactory/EuroLLM-1.7B-Instruct-GGUF",
            contextTokens = 4096,
            approximateRamMb = 2200,
        ),
        Entry(
            displayName = "EuroLLM 9B Instruct (Q4_K_M)",
            fileName = "EuroLLM-9B-Instruct-Q4_K_M.gguf",
            sha256 = "785a3b2883532381704ef74f866f822f179a931801d1ed1cf12e6deeb838806b",
            sizeBytes = 5_582_838_496L,
            license = "Apache-2.0",
            source = "huggingface.co/bartowski/EuroLLM-9B-Instruct-GGUF",
            contextTokens = 4096,
            approximateRamMb = 6600,
        ),
    )

    fun bySha256(hash: String): Entry? = entries.firstOrNull { it.sha256.equals(hash, true) }

    fun bySizeCandidates(sizeBytes: Long): List<Entry> = entries.filter { it.sizeBytes == sizeBytes }
}
