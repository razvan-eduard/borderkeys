// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningBufferTest {

    @Test
    fun `repeated words accumulate into one update`() {
        val buffer = LearningBuffer()
        buffer.record("mașina", "ro-RO", 1_000)
        buffer.record("mașina", "ro-RO", 1_500)
        buffer.record("mașina", "ro-RO", 2_000)

        val drained = buffer.drain()
        assertEquals(1, drained.size)
        assertEquals(3, drained[0].delta)
        // The timestamp is the most recent confirmation, not the first.
        assertEquals(2_000L, drained[0].lastUsedAt)
    }

    @Test
    fun `the same word in two locales stays two rows`() {
        val buffer = LearningBuffer()
        buffer.record("the", "en-US", 1_000)
        buffer.record("the", "ro-RO", 1_000)
        assertEquals(2, buffer.drain().size)
    }

    @Test
    fun `nothing is due before the debounce elapses`() {
        val buffer = LearningBuffer(debounceMillis = 4_000)
        buffer.record("word", "en-US", 1_000)
        assertFalse(buffer.isDue(2_000))
        assertFalse(buffer.isDue(4_999))
        assertTrue(buffer.isDue(5_000))
    }

    @Test
    fun `an empty buffer is never due`() {
        assertFalse(LearningBuffer().isDue(Long.MAX_VALUE))
    }

    @Test
    fun `the debounce is measured from the oldest pending entry, not the newest`() {
        val buffer = LearningBuffer(debounceMillis = 4_000)
        buffer.record("first", "en-US", 1_000)
        buffer.record("second", "en-US", 4_000)
        assertTrue(buffer.isDue(5_000))
    }

    @Test
    fun `a full buffer is due immediately and stays bounded`() {
        val buffer = LearningBuffer(debounceMillis = Long.MAX_VALUE, maxEntries = 4)
        repeat(10) { index -> buffer.record("word$index", "en-US", index.toLong()) }
        assertTrue(buffer.isDue(0))
        assertEquals(4, buffer.size)
        // The four most recent survived; the earliest were evicted.
        val words = buffer.drain().map { it.word }.toSet()
        assertEquals(setOf("word6", "word7", "word8", "word9"), words)
    }

    @Test
    fun `draining empties the buffer and resets the clock`() {
        val buffer = LearningBuffer(debounceMillis = 4_000)
        buffer.record("word", "en-US", 1_000)
        assertEquals(1, buffer.drain().size)
        assertTrue(buffer.isEmpty())
        assertEquals(emptyList<Any>(), buffer.drain())
        buffer.record("other", "en-US", 10_000)
        assertFalse("the clock restarts with the new entry", buffer.isDue(11_000))
    }

    @Test
    fun `disabling the buffer refuses everything`() {
        val buffer = LearningBuffer()
        buffer.enabled = false
        assertFalse(buffer.record("hunter2", "en-US", 1_000))
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun `blocked words are never learned`() {
        val buffer = LearningBuffer()
        buffer.setRefusedWords(RefusedWords.of(setOf("teh"), emptySet()))
        assertFalse(buffer.record("teh", "en-US", 1_000))
        assertTrue(buffer.record("the", "en-US", 1_000))
        assertEquals(1, buffer.size)
    }

    @Test
    fun `a blocked word is refused in any case`() {
        val buffer = LearningBuffer()
        buffer.setRefusedWords(RefusedWords.of(setOf("mașina"), emptySet()))
        assertFalse(buffer.record("Mașina", "ro-RO", 1_000))
        assertFalse(buffer.record("MAȘINA", "ro-RO", 1_000))
        assertFalse(buffer.recordPair("vreau", "Mașina", 1_000))
        assertFalse(buffer.recordTriple("Mașina", "e", "gata", 1_000))
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun `a blocked word leaves the word that differs from it by an accent`() {
        val buffer = LearningBuffer()
        buffer.setRefusedWords(RefusedWords.of(setOf("maine"), emptySet()))
        assertFalse(buffer.record("Maine", "ro-RO", 1_000))
        assertTrue(buffer.record("mâine", "ro-RO", 1_000))
        assertTrue(buffer.recordPair("pe", "mâine", 1_000))
    }

    @Test
    fun `an offensive word is refused in any case or spelling`() {
        val buffer = LearningBuffer()
        buffer.setRefusedWords(RefusedWords.of(emptySet(), setOf(WordFold.fold("căcat"))))
        assertFalse(buffer.record("căcat", "ro-RO", 1_000))
        assertFalse(buffer.record("Cacat", "ro-RO", 1_000))
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun `absurdly long input is refused rather than stored`() {
        val buffer = LearningBuffer()
        assertFalse(buffer.record("a".repeat(65), "en-US", 1_000))
        assertFalse(buffer.record("", "en-US", 1_000))
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun `discard drops pending work without returning it`() {
        val buffer = LearningBuffer()
        buffer.record("secret", "en-US", 1_000)
        buffer.discard()
        assertTrue(buffer.isEmpty())
        assertFalse(buffer.isDue(Long.MAX_VALUE))
    }

    @Test
    fun pairsAreBufferedAndDrainedSeparately() {
        val buffer = LearningBuffer()
        assertTrue(buffer.recordPair("vreau", "sa", 0L))
        assertTrue(buffer.recordPair("sa", "ma", 0L))
        assertTrue(buffer.recordPair("vreau", "sa", 10L))

        val pairs = buffer.drainPairs()
        assertEquals(2, pairs.size)
        val repeated = pairs.first { it.previousWord == "vreau" }
        assertEquals(2, repeated.delta)
        assertEquals(10L, repeated.lastUsedAt)
        assertTrue(buffer.drainPairs().isEmpty())
    }

    @Test
    fun aWordIsNotPairedWithItself() {
        val buffer = LearningBuffer()
        assertFalse(buffer.recordPair("da", "da", 0L))
        assertTrue(buffer.drainPairs().isEmpty())
    }

    @Test
    fun disabledLearningRecordsNoPairs() {
        val buffer = LearningBuffer()
        buffer.enabled = false
        assertFalse(buffer.recordPair("vreau", "sa", 0L))
        assertTrue(buffer.drainPairs().isEmpty())
    }

    @Test
    fun aBlockedWordIsNotPairedOnEitherSide() {
        val buffer = LearningBuffer()
        buffer.setRefusedWords(RefusedWords.of(setOf("naspa"), emptySet()))
        assertFalse(buffer.recordPair("vreau", "naspa", 0L))
        assertFalse(buffer.recordPair("naspa", "sa", 0L))
        assertTrue(buffer.drainPairs().isEmpty())
    }

    @Test
    fun discardingDropsThePairsToo() {
        val buffer = LearningBuffer()
        buffer.recordPair("vreau", "sa", 0L)
        buffer.discard()
        assertTrue(buffer.drainPairs().isEmpty())
    }

    @Test
    fun `a single letter is not worth a row of its own`() {
        val buffer = LearningBuffer()
        assertFalse(buffer.record("a", "ro-RO", 1_000))
        assertFalse(buffer.record("I", "en-US", 1_000))
        assertFalse(buffer.record("", "ro-RO", 1_000))
        assertTrue("two is enough", buffer.record("la", "ro-RO", 1_000))
    }

    @Test
    fun `a word longer than the ceiling is still refused`() {
        val buffer = LearningBuffer()
        val tooLong = "a".repeat(LearningBuffer.MAX_WORD_LENGTH + 1)
        assertFalse(buffer.record(tooLong, "ro-RO", 1_000))
    }

    /** Both facts about a commit reach the database with the count. */
    @Test
    fun `a deliberate capital and an assertion travel with the count`() {
        val buffer = LearningBuffer()
        buffer.record("emanuel", "ro-RO", 1_000)
        buffer.record("emanuel", "ro-RO", 1_500, deliberateCapital = true)
        buffer.record("emanuel", "ro-RO", 2_000, asserted = true)
        buffer.record("plain", "ro-RO", 2_000)

        val drained = buffer.drain().associateBy { it.word }
        assertEquals(3, drained.getValue("emanuel").delta)
        assertTrue(drained.getValue("emanuel").deliberateCapital)
        assertTrue(drained.getValue("emanuel").asserted)
        assertFalse(drained.getValue("plain").deliberateCapital)
        assertFalse(drained.getValue("plain").asserted)
    }
}
