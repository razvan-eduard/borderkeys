// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.personal.linkUses
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The personal dictionary's [OrderedWorker], on a hand-stepped thread ([SteppedExecutor]) and on
 * the production one ([SingleThread]): what is sent first lands first, and the teardown drains.
 */
class OrderedWorkerTest {

    private val thread = SteppedExecutor()
    private val memory = MemoryDictionary(thread)
    private val repository = memory.repository

    /** Starts [block] at once on the calling thread, as a caller on the main thread does. */
    private fun send(block: suspend () -> Unit) {
        CoroutineScope(Dispatchers.Unconfined).launch { block() }
    }

    /** A flush learning [word] once, with its pair after the sentence start, as the first commit records it. */
    private suspend fun flush(word: String) {
        val pair = LearnedBigram(LANGUAGE, UserBigram.SENTENCE_START, word, 1, AT)
        repository.applyLearning(
            listOf(LearnedWord(word, LOCALE, 1, AT)),
            linkUses(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), pairUses = listOf(pair)),
        )
    }

    private fun words(): List<String> = runBlocking { memory.words.allWords() }

    @Test
    fun `writes land in send order, each task to its end before the next starts`() {
        val order = CopyOnWriteArrayList<String>()
        val gate = CompletableDeferred<Unit>()
        memory.worker.post {
            order += "first starts"
            gate.await()
            order += "first ends"
        }
        send { memory.worker.run { order += "second" } }
        memory.worker.post { order += "third" }
        // Opens the gate while the first task waits on it.
        thread { Thread.sleep(GATE_MILLIS); gate.complete(Unit) }
        thread.drain()
        runBlocking { gate.await() }
        thread.drain()
        assertEquals(listOf("first starts", "first ends", "second", "third"), order.toList())
    }

    @Test
    fun `a forget after a queued batch stays forgotten`() {
        send { flush("cat") }
        send { repository.forgetEveryCase("cat") }
        thread.drain()
        assertEquals(emptyList<String>(), words())
        assertTrue("no pair through it", memory.pairs.rows.values.none { it.word == "cat" })
    }

    @Test
    fun `reads wait for earlier writes`() {
        send { flush("cat") }
        val rows = CoroutineScope(Dispatchers.Unconfined).async { repository.personalRows(LIMIT) }
        val touches = CoroutineScope(Dispatchers.Unconfined).async { repository.touchesIn(BUCKET) }
        assertFalse("the read waits on the worker", rows.isCompleted || touches.isCompleted)
        thread.drain()
        assertTrue(touches.isCompleted)
        val read = runBlocking { rows.await() }
        assertEquals(listOf("cat"), read.words.map { it.word })
        assertEquals(listOf("cat"), read.pairs.map { it.word })
    }

    @Test
    fun `teardown drains, then stops the thread, and a write after it starts the thread again`() {
        send { flush("cat") }
        send { flush("dog") }
        assertTrue(memory.worker.shutdown(TIMEOUT_MILLIS))
        assertEquals(listOf("cat", "dog"), words())
        assertTrue("the thread has stopped", thread.stopped)
        send { flush("eel") }
        assertFalse(thread.stopped)
        thread.drain()
        assertEquals(listOf("cat", "dog", "eel"), words())
    }

    @Test
    fun `a teardown past its wait lets the thread finish on its own, and the next task runs after it`() {
        val worker = OrderedWorker(SingleThread(THREAD_NAME)) { throw it }
        val release = CountDownLatch(1)
        val ran = CopyOnWriteArrayList<String>()
        val threads = CopyOnWriteArrayList<Thread>()
        worker.post {
            threads += Thread.currentThread()
            release.await(WAIT_SECONDS, TimeUnit.SECONDS)
            ran += "held"
        }
        worker.post { ran += "queued" }
        assertFalse("the wait is bounded", worker.shutdown(SHORT_MILLIS))
        val after = CountDownLatch(1)
        worker.post {
            threads += Thread.currentThread()
            ran += "after"
            after.countDown()
        }
        release.countDown()
        assertTrue(after.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(listOf("held", "queued", "after"), ran.toList())
        val (stopped, started) = threads
        assertNotSame("a thread of its own after the stop", stopped, started)
        stopped.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        assertFalse("the stopped thread has ended", stopped.isAlive)
        assertTrue(worker.shutdown(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)))
    }

    private companion object {
        const val LOCALE = "en-US"
        const val LANGUAGE = "en"
        const val AT = 1_000L
        const val LIMIT = 100
        const val BUCKET = "portrait"

        /** How long the first task waits on its gate. */
        const val GATE_MILLIS = 50L

        /** A teardown's wait, longer than the stepped thread ever takes. */
        const val TIMEOUT_MILLIS = 2_000L

        /** A teardown's wait shorter than the held task. */
        const val SHORT_MILLIS = 50L

        /** How long the test waits on a real thread at most. */
        const val WAIT_SECONDS = 10L

        const val THREAD_NAME = "personal-test"
    }
}
