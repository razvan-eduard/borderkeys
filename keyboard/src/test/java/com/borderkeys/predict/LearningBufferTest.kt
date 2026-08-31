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
        // Someone typing continuously must still get a flush; restarting the clock on every
        // word would mean the buffer is never written while the user keeps typing.
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
        buffer.setBlockedWords(setOf("teh"))
        assertFalse(buffer.record("teh", "en-US", 1_000))
        assertTrue(buffer.record("the", "en-US", 1_000))
        assertEquals(1, buffer.size)
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
}
