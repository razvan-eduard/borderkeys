// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

/** A [WorkerThread] the test steps by hand: each task handed waits until [runNext] or [drain] runs it. */
class SteppedExecutor : WorkerThread {
    private val tasks = ArrayDeque<Runnable>()

    /** Whether the thread is stopped: a [stop] ran and no task has been handed since. */
    var stopped = false
        private set

    /** How many tasks wait to run. */
    val waiting: Int
        @Synchronized get() = tasks.size

    @Synchronized
    override fun execute(task: Runnable) {
        stopped = false
        tasks.addLast(task)
    }

    /** Runs the oldest task waiting; false when none waits. */
    fun runNext(): Boolean {
        val task = synchronized(this) { tasks.removeFirstOrNull() } ?: return false
        task.run()
        return true
    }

    /** Runs the tasks waiting, and those they hand on, until none is left. */
    fun drain() {
        while (runNext()) continue
    }

    /** Runs every task handed, as the thread runs them before it stops, then stops; never past its wait. */
    override fun stop(timeoutMillis: Long): Boolean {
        drain()
        stopped = true
        return true
    }
}

/** A [WorkerThread] that runs each task where it is handed, at once. */
object InlineThread : WorkerThread {
    override fun execute(task: Runnable) = task.run()

    override fun stop(timeoutMillis: Long) = true
}

/** An [OrderedWorker] on [thread] that rethrows what a posted task throws, so a test sees it. */
fun testWorker(thread: WorkerThread = InlineThread) = OrderedWorker(thread) { throw it }
