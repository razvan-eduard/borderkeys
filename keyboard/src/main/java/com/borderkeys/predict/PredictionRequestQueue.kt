// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

/**
 * A request queue exactly one deep.
 *
 * Someone typing quickly produces requests faster than the engine answers them, and every
 * intermediate one is already obsolete by the time it would be served: the answer to "mas" is
 * worthless once "masi" has been typed. So a new request overwrites the pending one rather than
 * queueing behind it, and the engine always works on the newest state of the world.
 *
 * A generation number goes with each request. A result carrying an old generation is discarded
 * rather than displayed, which is what stops a slow answer from overwriting a fast one that came
 * after it -- the failure mode where the suggestion strip flickers back to a previous word.
 *
 * Deliberately free of Android and of JNI, so the policy can be tested for what it is: pure
 * state. Synchronised because it is written from the UI thread and read from the prediction
 * thread; the critical sections are field assignments.
 */
internal class PredictionRequestQueue {

    private var pendingValid = false
    private var pendingComposing: String = ""
    private var pendingPrevious1: String? = null
    private var pendingPrevious2: String? = null
    private var pendingGeneration = 0

    private var workerScheduled = false
    private var nextGeneration = 0

    var currentComposing: String = ""
        private set
    var currentPrevious1: String? = null
        private set
    var currentPrevious2: String? = null
        private set
    var currentGeneration: Int = -1
        private set

    /** How many requests were superseded before being served. Useful in a trace, and in tests. */
    var droppedRequests: Int = 0
        private set

    /**
     * Records a request. Returns true when the caller must schedule a worker, which is only the
     * case if one is not already scheduled or running.
     */
    @Synchronized
    fun submit(composing: String, previous1: String?, previous2: String?): Boolean {
        if (pendingValid) {
            droppedRequests++
        }
        pendingValid = true
        pendingComposing = composing
        pendingPrevious1 = previous1
        pendingPrevious2 = previous2
        pendingGeneration = nextGeneration++

        val needsWorker = !workerScheduled
        workerScheduled = true
        return needsWorker
    }

    /**
     * Moves the pending request into the current slot, for the worker to serve.
     *
     * Returns false when there is nothing left, and in that case also records that no worker is
     * running -- so the next [submit] schedules one. The worker loop is therefore
     * `while (take()) { ... }`, and coalescing falls out of it rather than being arranged.
     */
    @Synchronized
    fun take(): Boolean {
        if (!pendingValid) {
            workerScheduled = false
            return false
        }
        currentComposing = pendingComposing
        currentPrevious1 = pendingPrevious1
        currentPrevious2 = pendingPrevious2
        currentGeneration = pendingGeneration
        pendingValid = false
        return true
    }

    /** Whether a result for [generation] is still worth showing. */
    @Synchronized
    fun isCurrent(generation: Int): Boolean = generation == currentGeneration

    /**
     * Drops anything pending. Used when the editor changes or the input view goes away, where
     * the answer to the previous field's last word must not appear over the new one.
     */
    @Synchronized
    fun clear() {
        pendingValid = false
        workerScheduled = false
        currentGeneration = -1
        currentComposing = ""
        currentPrevious1 = null
        currentPrevious2 = null
    }
}
