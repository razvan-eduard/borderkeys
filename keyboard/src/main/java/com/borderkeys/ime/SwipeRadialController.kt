// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * What a paused, then lifted, swipe should show, and when it should stop showing it.
 *
 * A tiny state machine, not a feature: it holds no `InputConnection`, no view, and makes no
 * native call of its own -- see [LanguageSwitchCorrector]'s own doc for why that split is worth
 * making, which is the same reason here. [KeyboardCanvasView] decides *when* a pause or a lift
 * happened; [BorderKeysService] decides what to draw and what to commit; this decides only what
 * state that sequence of events is in and whether the caller has anything to do about it.
 *
 * Three states. [State.IDLE] is nothing showing. [State.PREVIEW] is the faint, non-interactive
 * hint a mid-swipe pause shows. [State.AWAITING_PICK] is the real, tappable menu a lift always
 * shows, whether or not a preview was up at the moment it happened -- see [onGestureLifted].
 */
class SwipeRadialController {

    enum class State { IDLE, PREVIEW, AWAITING_PICK }

    var state: State = State.IDLE
        private set

    /**
     * A pause fired with [candidateWords] to show. Only takes effect from [State.IDLE] -- a
     * pause detected while the real menu is already up ([State.AWAITING_PICK]) is not a thing
     * that should happen (the gesture already ended), and one detected mid-[State.PREVIEW] is
     * already showing what it would show again. Returns whether the caller should actually show
     * the preview, so a caller that always calls this on every pause never has to duplicate the
     * gate itself.
     */
    fun onPauseDetected(candidateWords: List<String>): Boolean {
        if (state != State.IDLE || candidateWords.isEmpty()) {
            return false
        }
        state = State.PREVIEW
        return true
    }

    /** [State.PREVIEW] -> [State.IDLE]. A no-op from anywhere else: resuming a swipe that was
     *  never showing a preview -- already lifted, or never paused -- has nothing to close. */
    fun onResumed() {
        if (state == State.PREVIEW) {
            state = State.IDLE
        }
    }

    /**
     * The finger lifted: the real menu is shown from here, always -- from [State.IDLE] (a swipe
     * that never paused), from [State.PREVIEW] (a pause was showing right up to the lift), or
     * even from [State.AWAITING_PICK] itself (a second gesture landing while an old menu from a
     * dropped/superseded lift was somehow still up -- defensive, not expected in practice).
     */
    fun onGestureLifted() {
        state = State.AWAITING_PICK
    }

    /** A wedge was tapped. [State.AWAITING_PICK] -> [State.IDLE]; the caller's own job is
     *  committing the word that was picked, this only closes the menu. */
    fun onPicked() = dismiss()

    /** The pick timeout elapsed with nothing tapped. [State.AWAITING_PICK] -> [State.IDLE]; the
     *  caller's own job is committing candidate 0 the same way a tap on it would -- this method
     *  itself does not know which candidate that is. */
    fun onTimedOut() = dismiss()

    /** Backspace was pressed while the menu was up. [State.AWAITING_PICK] -> [State.IDLE], and
     *  nothing else -- the caller's own backspace handling runs unmodified after this. */
    fun onBackspace() = dismiss()

    /** Any other key, a new gesture starting, committed text arriving some other way, or the
     *  field resetting outright -- every "something else is happening now" moment besides the
     *  three above. [State.AWAITING_PICK] -> [State.IDLE]. */
    fun onOtherKeyOrAction() = dismiss()

    private fun dismiss() {
        state = State.IDLE
    }

    companion object {
        /**
         * Whether a swipe in progress has gone far enough to preview at all.
         *
         * Guards against a slow-starting swipe reading as an instant pause: the dwell timer
         * fires from stillness, and the very first samples of a real swipe are, by definition,
         * still close to where the finger went down. Pure so it is testable against plain
         * numbers -- no [GestureCapture], no [android.view.MotionEvent] -- the same reasoning
         * [GestureCapture]'s own doc gives for keeping its invariants checkable without a
         * `Canvas`.
         */
        fun isEligibleForPreview(
            gestureCount: Int,
            pathLengthPx: Float,
            minPoints: Int,
            minPathPx: Float,
        ): Boolean = gestureCount >= minPoints && pathLengthPx >= minPathPx
    }
}
