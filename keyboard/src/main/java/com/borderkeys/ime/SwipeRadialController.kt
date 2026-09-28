// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * Whether a paused swipe's radial ring is open, and whether a swipe has gone far enough to open
 * it. Holds no connection or view and makes no native calls.
 */
class SwipeRadialController {

    enum class State { IDLE, OPEN }

    var state: State = State.IDLE
        private set

    /** The pause fired with [candidateWords] to show; takes effect only from [State.IDLE]. */
    fun onRingOpened(candidateWords: List<String>): Boolean {
        if (state != State.IDLE || candidateWords.isEmpty()) {
            return false
        }
        state = State.OPEN
        return true
    }

    /** The finger lifted, timed out or was cancelled: back to [State.IDLE]. */
    fun onResolved() {
        state = State.IDLE
    }

    companion object {
        /** Whether a swipe in progress has gone far enough to open the ring. */
        fun isEligibleForPreview(
            gestureCount: Int,
            pathLengthPx: Float,
            minPoints: Int,
            minPathPx: Float,
        ): Boolean = gestureCount >= minPoints && pathLengthPx >= minPathPx
    }
}
