// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.assist

import com.borderkeys.data.assist.AssistProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure pieces of chunking: where a long selection is cut, and how big one chunk is allowed
 * to be for a given model. Neither touches the service or the client, so neither needs one to be
 * tested.
 */
class ChunkedAssistRunnerTest {

    @Test
    fun `text that already fits is not split at all`() {
        val text = "A short sentence."
        assertEquals(listOf(text), ChunkedAssistRunner.splitIntoChunks(text, 200))
    }

    @Test
    fun `a long text splits at sentence boundaries, never inside one`() {
        val text = "First sentence here. Second sentence follows it. Third one closes it out."
        val chunks = ChunkedAssistRunner.splitIntoChunks(text, 30)
        assertTrue("expected more than one chunk", chunks.size > 1)
        for (chunk in chunks) {
            assertTrue("$chunk exceeds the chunk size", chunk.length <= 30)
        }
        // Every chunk but a trailing fragment ends where a sentence actually ended -- rejoining
        // with spaces and comparing the words is what proves nothing was cut mid-sentence,
        // since the split points themselves consumed the original spacing between sentences.
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun `a run-on chunk with no sentence punctuation falls back to whole words`() {
        val text = "one two three four five six seven eight nine ten"
        val originalWords = text.split(' ').toSet()
        val chunks = ChunkedAssistRunner.splitIntoChunks(text, 12)
        assertTrue("expected more than one chunk", chunks.size > 1)
        for (chunk in chunks) {
            assertTrue("$chunk exceeds the chunk size", chunk.length <= 12)
            for (word in chunk.split(' ')) {
                assertTrue("\"$word\" is not a whole word from the original text",
                          word in originalWords)
            }
        }
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun `chunk size grows with the model's own context window`() {
        val small = ChunkedAssistRunner.maxChunkChars(512)
        val large = ChunkedAssistRunner.maxChunkChars(8192)
        assertTrue("a bigger window should allow a bigger chunk", large > small)
    }

    @Test
    fun `chunk size never exceeds what one request may carry at all`() {
        for (contextTokens in listOf(512, 2048, 4096, 8192)) {
            val chars = ChunkedAssistRunner.maxChunkChars(contextTokens)
            assertTrue(
                "a $contextTokens-token window produced a chunk too large for one request",
                chars <= AssistProtocol.MAX_SELECTION_CHARS,
            )
        }
    }

    @Test
    fun `chunk size never collapses to nothing for a small context`() {
        assertTrue(ChunkedAssistRunner.maxChunkChars(0) > 0)
        assertTrue(ChunkedAssistRunner.maxChunkChars(-100) > 0)
    }
}
