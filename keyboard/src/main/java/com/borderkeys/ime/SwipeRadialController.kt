// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * What a paused swipe's radial ring is doing, and the pure geometry a finger's position
 * resolves to while it is open.
 *
 * A tiny state machine, not a feature: it holds no `InputConnection`, no view, and makes no
 * native call of its own -- see [LanguageSwitchCorrector]'s own doc for why that split is worth
 * making, which is the same reason here. [KeyboardCanvasView] decides *when* a pause and a lift
 * happened; [BorderKeysService] decides what to draw and what to commit; this decides only what
 * state that sequence is in, and which wedge (if any) a steered position lands on.
 *
 * Two states only, on purpose: there is no more separate low-opacity "preview" a big-enough
 * movement could dismiss back to normal swiping. The design settled on committing to the ring
 * the moment it opens -- simpler to reason about and to test, and it matches the single
 * continuous stroke the whole feature exists for (see `RadialSuggestionMenuView`'s own doc for
 * the interaction this state machine backs).
 */
class SwipeRadialController {

    enum class State { IDLE, OPEN }

    var state: State = State.IDLE
        private set

    /** The pause fired with [candidateWords] to show. Only takes effect from [State.IDLE] --
     *  opening twice for one gesture is not a thing [KeyboardCanvasView] can produce (there is
     *  exactly one pause per gesture, see its own doc), so this is defensive, not expected. */
    fun onRingOpened(candidateWords: List<String>): Boolean {
        if (state != State.IDLE || candidateWords.isEmpty()) {
            return false
        }
        state = State.OPEN
        return true
    }

    /** The finger lifted, timed out, or the touch stream was cancelled -- [State.OPEN] ->
     *  [State.IDLE] in every case; what actually got applied is the caller's business; this only
     *  closes the state machine. A no-op from [State.IDLE]. */
    fun onResolved() {
        state = State.IDLE
    }

    companion object {
        /**
         * Whether a swipe in progress has gone far enough to open the ring at all.
         *
         * Guards against a slow-starting swipe reading as an instant pause: the dwell timer
         * fires from stillness, and the very first samples of a real swipe are, by definition,
         * still close to where the finger went down. Pure so it is testable against plain
         * numbers -- no [GestureCapture], no [android.view.MotionEvent].
         */
        fun isEligibleForPreview(
            gestureCount: Int,
            pathLengthPx: Float,
            minPoints: Int,
            minPathPx: Float,
        ): Boolean = gestureCount >= minPoints && pathLengthPx >= minPathPx
    }
}
