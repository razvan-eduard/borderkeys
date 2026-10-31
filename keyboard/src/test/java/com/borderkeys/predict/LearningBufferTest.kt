// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.WordShape
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.personal.LinkWrites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningBufferTest {

    /** The row of a pair a commit at [at] left at [count] uses. */
    private fun pair(previous: String, word: String, at: Long = 0L, count: Int = 1) =
        UserBigram("en", previous, word, count, at, 0L, 0L)

    /** The row of a triple a commit at [at] left at one use. */
    private fun triple(previous2: String, previous1: String, word: String, at: Long = 0L) =
        UserTrigram("en", previous2, previous1, word, 1, at, 0L, 0L)

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
        repeat(most) { index -> pairs.recordLinks(LinkWrites(pairs = listOf(pair("w$index", "next"))), 0L) }
        assertEquals("no word waits", 0, pairs.size)
        assertTrue(pairs.isDue(0L))
        val triples = LearningBuffer(debounceMillis = Long.MAX_VALUE, maxEntries = most)
        repeat(most) { index -> triples.recordLinks(LinkWrites(triples = listOf(triple("v$index", "word", "next"))), 0L) }
        assertEquals("no word waits", 0, triples.size)
        assertTrue(triples.isDue(0L))
    }

    @Test
    fun `the rows of a link commits changed gather at the latest, a link given up and written again written`() {
        val buffer = LearningBuffer(debounceMillis = Long.MAX_VALUE, maxEntries = 2)
        buffer.recordLinks(LinkWrites(pairs = listOf(pair("the", "cat"), pair("the", "dog"))), 0L)
        buffer.recordLinks(LinkWrites(pairs = listOf(pair("the", "cat", count = 2)), gonePairs = listOf(pair("the", "dog"))), 0L)
        assertTrue("a link to write and one to give up fill the buffer", buffer.isDue(0L))
        buffer.recordLinks(LinkWrites(pairs = listOf(pair("the", "dog", count = 3))), 0L)
        assertEquals(LinkWrites(pairs = listOf(pair("the", "cat", count = 2), pair("the", "dog", count = 3))), buffer.drainLinks())
    }

    @Test
    fun `a pair alone starts the debounce clock`() {
        val debounce = 4_000L
        val at = 1_000L
        val buffer = LearningBuffer(debounceMillis = debounce)
        buffer.recordLinks(LinkWrites(pairs = listOf(pair("the", "cat", at))), at)
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
    fun `the link rows drain apart from the words, and draining empties them`() {
        val buffer = LearningBuffer()
        val rows = LinkWrites(pairs = listOf(pair("vreau", "sa", 0L), pair("sa", "ma", 0L)))
        buffer.recordLinks(rows, 0L)

        assertEquals(rows, buffer.drainLinks())
        assertTrue(buffer.drain().isEmpty())
        assertTrue(buffer.drainLinks().isEmpty)
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
    }

    @Test
    fun `the link rows of a commit made before the buffer was disabled are kept`() {
        val buffer = LearningBuffer()
        buffer.enabled = false
        buffer.recordLinks(LinkWrites(pairs = listOf(pair("vreau", "sa"))), 0L)
        assertEquals(listOf("sa"), buffer.drainLinks().pairs.map { it.word })
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
        buffer.recordLinks(LinkWrites(pairs = listOf(pair("vreau", "sa"))), 0L)
        buffer.discard()
        assertTrue(buffer.drainLinks().isEmpty)
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
        val tooLong = "a".repeat(WordShape.MAX_LENGTH + 1)
        assertFalse(buffer.record(tooLong, "ro-RO", 1_000))
    }

    /** Both facts about a commit reach the database with the count; each choice is counted. */
    @Test
    fun `a deliberate capital and the choices travel with the count`() {
        val buffer = LearningBuffer()
        buffer.record("emanuel", "ro-RO", 1_000)
        buffer.record("emanuel", "ro-RO", 1_500, deliberateCapital = true)
        buffer.record("emanuel", "ro-RO", 2_000, asserted = true)
        buffer.record("emanuel", "ro-RO", 2_500, asserted = true)
        buffer.record("plain", "ro-RO", 2_000)

        val drained = buffer.drain().associateBy { it.word }
        assertEquals(4, drained.getValue("emanuel").delta)
        assertTrue(drained.getValue("emanuel").deliberateCapital)
        assertEquals(2, drained.getValue("emanuel").asserted)
        assertFalse(drained.getValue("plain").deliberateCapital)
        assertEquals(0, drained.getValue("plain").asserted)
    }

    @Test
    fun `forgetting a word drops it in every case, with its pairs and triples, and says whether it was waiting`() {
        val buffer = LearningBuffer()
        buffer.record("This", "en-US", 1_000)
        buffer.record("this", "en-US", 2_000)
        buffer.record("make", "en-US", 3_000)
        val dropped = pair("make", "that")
        buffer.recordLinks(
            LinkWrites(
                pairs = listOf(pair("make", "this"), pair("go", "home")),
                triples = listOf(triple("we", "make", "this")),
                gonePairs = listOf(dropped),
            ),
            3_000,
        )

        assertTrue(buffer.forget("THIS"))
        assertEquals(listOf("make"), buffer.drain().map { it.word })
        val left = buffer.drainLinks()
        assertEquals("the link given up stays", listOf(dropped), left.gonePairs)
        assertEquals(listOf(pair("go", "home")), left.pairs)
        assertTrue(left.triples.isEmpty())
        assertFalse("nothing left of it to drop", buffer.forget("this"))
    }
}
