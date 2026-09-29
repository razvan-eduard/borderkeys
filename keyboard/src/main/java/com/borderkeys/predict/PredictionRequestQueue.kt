// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

/**
 * A request queue one deep: a new request replaces the pending one, and the one taken gets a
 * generation number from [NewestWins], so an answer cleared away meanwhile can be discarded.
 * Written from the UI thread and read from the prediction thread.
 */
internal class PredictionRequestQueue {

    private var pendingValid = false
    private var pendingComposing: String = ""
    private var pendingPrevious1: String? = null
    private var pendingPrevious2: String? = null

    private var workerScheduled = false
    private val generations = NewestWins()

    var currentComposing: String = ""
        private set
    var currentPrevious1: String? = null
        private set
    var currentPrevious2: String? = null
        private set
    var currentGeneration: Int = -1
        private set

    /** How many requests were superseded before being served. */
    var droppedRequests: Int = 0
        private set

    /** Records a request. Returns true when no worker is scheduled or running. */
    @Synchronized
    fun submit(composing: String, previous1: String?, previous2: String?): Boolean {
        if (pendingValid) {
            droppedRequests++
        }
        pendingValid = true
        pendingComposing = composing
        pendingPrevious1 = previous1
        pendingPrevious2 = previous2

        val needsWorker = !workerScheduled
        workerScheduled = true
        return needsWorker
    }

    /**
     * Moves the pending request into the current slot. Returns false when there is none, and
     * then records that no worker is running.
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
        currentGeneration = generations.issue()
        pendingValid = false
        return true
    }

    /** Whether a result for [generation] is still worth showing. */
    @Synchronized
    fun isCurrent(generation: Int): Boolean = generations.isNewest(generation)

    /** Drops the pending and the current request. */
    @Synchronized
    fun clear() {
        pendingValid = false
        workerScheduled = false
        generations.cancel()
        currentGeneration = -1
        currentComposing = ""
        currentPrevious1 = null
        currentPrevious2 = null
    }
}
