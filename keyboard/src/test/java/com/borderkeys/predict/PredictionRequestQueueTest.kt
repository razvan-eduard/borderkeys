// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionRequestQueueTest {

    @Test
    fun `ten rapid requests are collapsed into the last one`() {
        val queue = PredictionRequestQueue()
        for (index in 0 until 10) {
            queue.submit("word$index", null, null)
        }
        assertTrue(queue.take())
        assertEquals("word9", queue.currentComposing)
        // Nothing else is waiting: the nine intermediate answers were already obsolete.
        assertFalse(queue.take())
        assertEquals(9, queue.droppedRequests)
    }

    @Test
    fun `only the first submit asks for a worker`() {
        val queue = PredictionRequestQueue()
        assertTrue("the first request must schedule a worker", queue.submit("a", null, null))
        assertFalse(queue.submit("ab", null, null))
        assertFalse(queue.submit("abc", null, null))
        // Once the worker has drained the queue, the next request schedules again.
        assertTrue(queue.take())
        assertFalse(queue.take())
        assertTrue(queue.submit("abcd", null, null))
    }

    @Test
    fun `a stale result is not current`() {
        val queue = PredictionRequestQueue()
        queue.submit("mas", null, null)
        queue.take()
        val firstGeneration = queue.currentGeneration

        queue.submit("masi", null, null)
        queue.take()

        assertFalse("the older answer must be discarded", queue.isCurrent(firstGeneration))
        assertTrue(queue.isCurrent(queue.currentGeneration))
    }

    @Test
    fun `context words travel with the request`() {
        val queue = PredictionRequestQueue()
        queue.submit("tim", "the", "of")
        assertTrue(queue.take())
        assertEquals("tim", queue.currentComposing)
        assertEquals("the", queue.currentPrevious1)
        assertEquals("of", queue.currentPrevious2)
    }

    @Test
    fun `clearing drops everything pending`() {
        val queue = PredictionRequestQueue()
        queue.submit("secret", null, null)
        queue.clear()
        assertFalse(queue.take())
        // Nothing that was in flight can be shown over the next editor's field.
        assertFalse(queue.isCurrent(0))
    }

    @Test
    fun `generations keep increasing across drains`() {
        val queue = PredictionRequestQueue()
        val seen = ArrayList<Int>()
        repeat(5) { round ->
            queue.submit("round$round", null, null)
            queue.take()
            seen += queue.currentGeneration
            queue.take()
        }
        assertEquals(seen.sorted(), seen)
        assertEquals(seen.distinct().size, seen.size)
    }
}
