// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** The thread an [OrderedWorker] runs on: each task handed to it runs after every one handed before. */
interface WorkerThread {
    /** Runs [task] after every task handed before it. */
    fun execute(task: Runnable)

    /**
     * Stops the thread once every task handed before has run, waiting at most [timeoutMillis] for
     * that; whether it stopped in that time. A task handed after a stop starts the thread again.
     */
    fun stop(timeoutMillis: Long): Boolean
}

/**
 * Runs the personal dictionary's reads and writes on [thread] one at a time, in the order they are
 * handed: each task runs to its end, its suspensions included, before the next one starts. What a
 * task posted without a caller throws goes to [onFailure].
 */
class OrderedWorker(private val thread: WorkerThread, private val onFailure: (Throwable) -> Unit) {

    /** Marks a task running on [worker], so the task's own calls to [run] run in place. */
    private class Running(val worker: OrderedWorker) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Running>
    }

    /** Runs [task] after every task handed before it, without waiting for it. */
    fun post(task: suspend () -> Unit) {
        thread.execute { runTask { runCatching { task() }.onFailure(onFailure) } }
    }

    /**
     * Runs [task] after every task handed before it, and returns what it returns or throws what it
     * throws; called from a task of this worker, runs it in place. The task runs to its end even
     * when the caller stops waiting.
     */
    suspend fun <T> run(task: suspend () -> T): T {
        if (currentCoroutineContext()[Running]?.worker === this) {
            return task()
        }
        val result = CompletableDeferred<T>()
        thread.execute { runTask { result.completeWith(runCatching { task() }) } }
        return result.await()
    }

    /**
     * The teardown: every task handed before runs, then the thread stops, waited for at most
     * [timeoutMillis]; whether it stopped in that time. Past it the thread finishes on its own.
     */
    fun shutdown(timeoutMillis: Long): Boolean = thread.stop(timeoutMillis)

    /** Runs [block] to its end on the calling thread, as a task of this worker. */
    private fun runTask(block: suspend () -> Unit) = runBlocking(Running(this)) { block() }
}

/**
 * A [WorkerThread] on one thread named [name], started at the first task and again at the first
 * after a [stop]; a thread started again runs its tasks only once the stopped one has finished.
 */
class SingleThread(private val name: String) : WorkerThread {
    private var executor = start()

    @Synchronized
    override fun execute(task: Runnable) {
        if (executor.isShutdown) {
            val stopping = executor
            executor = start()
            executor.execute { stopping.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS) }
        }
        executor.execute(task)
    }

    override fun stop(timeoutMillis: Long): Boolean {
        val stopping = synchronized(this) { executor.also { it.shutdown() } }
        return stopping.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    private fun start(): ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, name) }
}
