// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process

/** A thread [PredictionEngine] hands work to, in order: the prediction thread, or the main thread. */
interface EngineThread {
    /** Runs [task] after every task posted before it; false when the thread takes no more. */
    fun post(task: Runnable): Boolean

    /** Drops [task] where it still waits. */
    fun remove(task: Runnable)

    /** Drops every task still waiting. */
    fun clear()

    /** Stops the thread once the tasks posted before have run. */
    fun quit()
}

/** An [EngineThread] on [handler]'s looper; [quit] stops [thread], when the looper is one of its own. */
class LooperThread(private val handler: Handler, private val thread: HandlerThread? = null) : EngineThread {
    override fun post(task: Runnable): Boolean = handler.post(task)

    override fun remove(task: Runnable) = handler.removeCallbacks(task)

    override fun clear() = handler.removeCallbacksAndMessages(null)

    override fun quit() {
        thread?.quitSafely()
    }

    companion object {
        /** The main thread, which never quits. */
        fun main(): LooperThread = LooperThread(Handler(Looper.getMainLooper()))

        /** A thread of its own named [name], started. */
        fun started(name: String): LooperThread {
            val thread = HandlerThread(name, Process.THREAD_PRIORITY_DEFAULT)
            thread.start()
            return LooperThread(Handler(thread.looper), thread)
        }
    }
}
