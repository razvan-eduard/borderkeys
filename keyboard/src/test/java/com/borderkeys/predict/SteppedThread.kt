// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

/** An [EngineThread] the test steps by hand: each task posted waits until [drain] runs it. */
internal class SteppedThread : EngineThread {
    private val tasks = ArrayDeque<Runnable>()
    private var quitting = false

    /** Whether the thread has stopped: [quit] ran, and the tasks posted before it with it. */
    var stopped = false
        private set

    override fun post(task: Runnable): Boolean {
        if (quitting) {
            return false
        }
        tasks.addLast(task)
        return true
    }

    override fun remove(task: Runnable) {
        tasks.remove(task)
    }

    override fun clear() = tasks.clear()

    override fun quit() {
        quitting = true
    }

    /** Runs the tasks waiting, and those they post, until none is left or the thread has quit. */
    fun drain() {
        while (!stopped) {
            val task = tasks.removeFirstOrNull()
            if (task == null) {
                stopped = quitting
                return
            }
            task.run()
        }
    }
}
