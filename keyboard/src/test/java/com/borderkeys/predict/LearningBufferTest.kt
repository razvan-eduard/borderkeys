// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedTrigram
import com.borderkeys.data.dao.PairStep
import com.borderkeys.data.dao.TripleStep
import com.borderkeys.data.entity.UserBigram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningBufferTest {

    /** A pair use the engine recorded at [at], with nothing dropped after it. */
    private fun pair(previous: String, word: String, at: Long = 0L) =
        PairStep(LearnedBigram("en", previous, word, 1, at))

    /** A triple use the engine recorded at [at], with nothing dropped after it. */
    private fun triple(previous2: String, previous1: String, word: String, at: Long = 0L) =
        TripleStep(LearnedTrigram("en", previous2, previous1, word, 1, at))

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
    fun `a full table of pairs, or of triples, makes the buffer due with no word waiting`() {
        val most = 4
        val pairs = LearningBuffer(debounceMillis = Long.MAX_VALUE, maxEntries = most)
        repeat(most) { index -> pairs.recordSteps(listOf(pair("w$index", "next")), emptyList(), 0L) }
        assertEquals("no word waits", 0, pairs.size)
        assertTrue(pairs.isDue(0L))
        val triples = LearningBuffer(debounceMillis = Long.MAX_VALUE, maxEntries = most)
        repeat(most) { index -> triples.recordSteps(emptyList(), listOf(triple("v$index", "word", "next")), 0L) }
        assertEquals("no word waits", 0, triples.size)
        assertTrue(triples.isDue(0L))
    }

    @Test
    fun `past its bound a table lets its oldest use go and keeps the engine's drops in front`() {
        val most = 2
        val firstDrop = UserBigram("en", "the", "dog", 1, 0L, 0L, 0L)
        val secondDrop = UserBigram("en", "the", "fox", 1, 0L, 0L, 0L)
        val first = pair("the", "cat").copy(dropped = listOf(firstDrop))
        val second = pair("the", "cow").copy(dropped = listOf(secondDrop))
        val three = LearningBuffer(debounceMillis = Long.MAX_VALUE, maxEntries = most)
        three.recordSteps(listOf(first, second, pair("the", "owl")), emptyList(), 0L)
        assertEquals(listOf(PairStep(null, listOf(firstDrop)), second, pair("the", "owl")), three.drainPairs())
        val four = LearningBuffer(debounceMillis = Long.MAX_VALUE, maxEntries = most)
        four.recordSteps(listOf(first, second, pair("the", "owl")), emptyList(), 0L)
        four.recordSteps(listOf(pair("the", "bat")), emptyList(), 0L)
        assertEquals(
            listOf(PairStep(null, listOf(firstDrop, secondDrop)), pair("the", "owl"), pair("the", "bat")),
            four.drainPairs(),
        )
    }

    @Test
    fun `a pair alone starts the debounce clock`() {
        val debounce = 4_000L
        val at = 1_000L
        val buffer = LearningBuffer(debounceMillis = debounce)
        buffer.recordSteps(listOf(pair("the", "cat", at)), emptyList(), at)
        assertFalse(buffer.isDue(at + debounce - 1))
        assertTrue(buffer.isDue(at + debounce))
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
        assertFalse(buffer.acceptsPair("vreau", "Mașina"))
        assertFalse(buffer.acceptsTriple("Mașina", "este", "gata"))
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun `a blocked word leaves the word that differs from it by an accent`() {
        val buffer = LearningBuffer()
        buffer.setRefusedWords(RefusedWords.of(setOf("maine"), emptySet()))
        assertFalse(buffer.record("Maine", "ro-RO", 1_000))
        assertTrue(buffer.record("mâine", "ro-RO", 1_000))
        assertTrue(buffer.acceptsPair("pe", "mâine"))
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
    fun `each pair use is buffered on its own and drained in order`() {
        val buffer = LearningBuffer()
        val uses = listOf(pair("vreau", "sa", 0L), pair("sa", "ma", 0L), pair("vreau", "sa", 10L))
        buffer.recordSteps(uses, emptyList(), 0L)

        assertEquals(uses, buffer.drainPairs())
        assertTrue(buffer.drain().isEmpty())
        assertTrue(buffer.drainPairs().isEmpty())
    }

    @Test
    fun `a pair or triple after a one-letter word is taken`() {
        val buffer = LearningBuffer()
        assertTrue(buffer.acceptsPair("a", "book"))
        assertTrue(buffer.acceptsTriple("I", "read", "books"))
    }

    @Test
    fun aWordIsNotPairedWithItself() {
        val buffer = LearningBuffer()
        assertFalse(buffer.acceptsPair("da", "da"))
        assertFalse("nor with its own capitalised form", buffer.acceptsPair("Da", "da"))
        assertFalse("nor in a triple", buffer.acceptsTriple("eu", "Da", "da"))
    }

    @Test
    fun disabledLearningRecordsNoPairs() {
        val buffer = LearningBuffer()
        buffer.enabled = false
        assertFalse(buffer.acceptsPair("vreau", "sa"))
        buffer.recordSteps(listOf(pair("vreau", "sa")), emptyList(), 0L)
        assertTrue(buffer.drainPairs().isEmpty())
    }

    @Test
    fun aBlockedWordIsNotPairedOnEitherSide() {
        val buffer = LearningBuffer()
        buffer.setRefusedWords(RefusedWords.of(setOf("naspa"), emptySet()))
        assertFalse(buffer.acceptsPair("vreau", "naspa"))
        assertFalse(buffer.acceptsPair("naspa", "sa"))
    }

    @Test
    fun discardingDropsThePairsToo() {
        val buffer = LearningBuffer()
        buffer.recordSteps(listOf(pair("vreau", "sa")), emptyList(), 0L)
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

    @Test
    fun `forgetting a word drops it in every case, with its pairs and triples, and says whether it was waiting`() {
        val buffer = LearningBuffer()
        buffer.record("This", "en-US", 1_000)
        buffer.record("this", "en-US", 2_000)
        buffer.record("make", "en-US", 3_000)
        val dropped = UserBigram("en", "make", "that", 1, 0L, 0L, 0L)
        buffer.recordSteps(
            listOf(pair("make", "this").copy(dropped = listOf(dropped)), pair("go", "home")),
            listOf(triple("we", "make", "this")),
            3_000,
        )

        assertTrue(buffer.forget("THIS"))
        assertEquals(listOf("make"), buffer.drain().map { it.word })
        assertEquals(
            "the engine's drop stays",
            listOf(PairStep(null, listOf(dropped)), pair("go", "home")),
            buffer.drainPairs(),
        )
        assertTrue(buffer.drainTriples().isEmpty())
        assertFalse("nothing left of it to drop", buffer.forget("this"))
    }
}
