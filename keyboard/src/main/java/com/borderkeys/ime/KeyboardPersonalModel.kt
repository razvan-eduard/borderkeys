// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.personal.LinkWrites
import com.borderkeys.data.personal.PersonalModelHost
import com.borderkeys.data.personal.WorkingCopy
import com.borderkeys.typing.EnginePort
import com.borderkeys.typing.TypingOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * The personal model of the running keyboard as the personal dictionary's edits reach it: each
 * edit runs on [scope], on the typing flow's thread, to its end whoever asked for it, under [lock]
 * with the loads; the lookup changes on the [engine]'s thread after every commit handed before it,
 * the learning waiting drops what it holds of the words the edit forgets, and the link rows the
 * edit changes are written through the [orchestrator]'s learning, after the rows waiting.
 * [reloadModel] reads the personal model again with an edit in between ([PersonalModelReload]),
 * and [refreshBlocked] hands the engine and the learning the blocked words.
 */
internal class KeyboardPersonalModel(
    private val scope: CoroutineScope,
    private val lock: Mutex,
    private val engine: EnginePort,
    private val orchestrator: TypingOrchestrator,
    private val reloadModel: suspend (between: suspend () -> Unit) -> Unit,
    private val refreshBlocked: suspend () -> Unit,
) : PersonalModelHost {

    override suspend fun <T> update(
        write: suspend () -> T,
        forgotten: Collection<String>,
        change: (WorkingCopy, T) -> LinkWrites,
    ): T = locked {
        val written = write()
        val writes = suspendCancellableCoroutine { edited ->
            engine.editPersonal({ copy -> change(copy, written) }) { edited.resume(it) }
        }
        forgotten.forEach { orchestrator.forgetUnsaved(it) }
        if (writes != null && !writes.isEmpty) {
            orchestrator.writeEdit(writes)
        }
        written
    }

    override suspend fun <T> reload(edit: suspend () -> T): T {
        val edited = locked {
            var result: Result<T>? = null
            reloadModel { result = runCatching { edit() } }
            refreshBlocked()
            result
        }
        return checkNotNull(edited) { "the reload ran no edit" }.getOrThrow()
    }

    override suspend fun blockedChanged() = locked { refreshBlocked() }

    override suspend fun forgetTouches(write: suspend () -> Unit) = locked {
        orchestrator.forgetUnsavedTaps()
        write()
        orchestrator.reloadTouches()
    }

    /** Runs [block] on [scope] under [lock], to its end even when the caller stops waiting. */
    private suspend fun <T> locked(block: suspend () -> T): T =
        scope.async { lock.withLock { block() } }.await()
}
