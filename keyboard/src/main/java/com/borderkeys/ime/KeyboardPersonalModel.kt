// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.personal.LinkWrites
import com.borderkeys.data.personal.PersonalModelHost
import com.borderkeys.data.personal.WorkingCopy
import com.borderkeys.typing.EnginePort
import com.borderkeys.typing.TypingOrchestrator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The personal model of the running keyboard as the personal dictionary's edits reach it: each
 * edit runs on [scope], on the typing flow's thread, to its end whoever asked for it, under [lock]
 * with the loads; the lookup changes on the [engine]'s thread after every commit handed before it,
 * the learning waiting drops what it holds of the words the edit forgets, and the link rows the
 * edit changes are written through the [orchestrator]'s learning, after the rows waiting.
 * [reloadModel] reads the personal model again with an edit in between ([PersonalModelReload]),
 * and [refreshBlocked] hands the engine and the learning the blocked words. Once [scope] is
 * cancelled, an edit the keyboard has not run runs alone under [lock], once. What of a call fails
 * besides its edit goes to [failed].
 */
internal class KeyboardPersonalModel(
    private val scope: CoroutineScope,
    private val lock: Mutex,
    private val engine: EnginePort,
    private val orchestrator: TypingOrchestrator,
    private val reloadModel: suspend (between: suspend () -> Unit) -> Unit,
    private val refreshBlocked: suspend () -> Unit,
    private val failed: (Throwable) -> Unit,
) : PersonalModelHost {

    /** The words each update running on [scope] forgets. */
    private val forgetting = ArrayList<Collection<String>>()

    override suspend fun <T> update(
        write: suspend () -> T,
        forgotten: Collection<String>,
        change: (WorkingCopy, T) -> LinkWrites,
    ): T = runOnScope(write) { once ->
        forgetting.add(forgotten)
        try {
            val written = once.run().getOrElse { return@runOnScope }
            val writes = suspendCancellableCoroutine { answered ->
                engine.editPersonal({ copy -> change(copy, written) }) { answered.resume(it) }
            }
            forgotten.forEach { orchestrator.forgetUnsaved(it) }
            if (writes != null && !writes.isEmpty) {
                orchestrator.writeEdit(writes)
            }
        } finally {
            forgetting.remove(forgotten)
        }
    }

    override suspend fun <T> reload(edit: suspend () -> T): T = runOnScope(edit) { once ->
        reloadModel { once.run() }
        refreshBlocked()
    }

    override suspend fun blockedChanged() = runOnScope({}) { refreshBlocked() }

    override suspend fun forgetTouches(write: suspend () -> Unit) = runOnScope(write) { once ->
        orchestrator.forgetUnsavedTaps()
        once.run()
        orchestrator.reloadTouches()
    }

    /**
     * The keyboard is stopping, its last flush next: the learning waiting drops the words the
     * updates running forget.
     */
    fun stop() {
        forgetting.forEach { words -> words.forEach { orchestrator.forgetUnsaved(it) } }
    }

    /**
     * Runs [part] on [scope] under [lock] with [edit] for it to run, then [edit] under [lock] if
     * [part] did not run it, and waits for both past the caller's cancellation; returns what [edit]
     * returned, or throws what it threw.
     */
    private suspend fun <T> runOnScope(edit: suspend () -> T, part: suspend (Once<T>) -> Unit): T {
        val once = Once(edit)
        val running = scope.async {
            lock.withLock {
                try {
                    part(once)
                } finally {
                    once.run()
                }
            }
        }
        running.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException) {
                failed(cause)
            }
        }
        withContext(NonCancellable) {
            running.join()
            if (!once.ran) {
                lock.withLock { once.run() }
            }
        }
        return once.result()
    }

    /** [edit], run at most once and to its end, past any cancellation. */
    private class Once<T>(private val edit: suspend () -> T) {
        @Volatile
        private var outcome: Result<T>? = null

        /** Whether [edit] has run. */
        val ran: Boolean
            get() = outcome != null

        /** Runs [edit] unless it has run; what it returned or threw. */
        suspend fun run(): Result<T> =
            outcome ?: withContext(NonCancellable) { runCatching { edit() }.also { outcome = it } }

        /** What [edit] returned; throws what it threw. */
        fun result(): T = checkNotNull(outcome) { "the edit has not run" }.getOrThrow()
    }
}
