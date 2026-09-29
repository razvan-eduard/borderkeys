// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

/** The ring a paused or lifted swipe opens, as the typing flow uses it. */
interface RingUi {

    /** What the ring's highlight or a tap on it resolves to. */
    sealed interface Selection {
        /** The wedge at [index], holding [word]. */
        data class Word(val index: Int, val word: String) : Selection

        /** The centre, which discards the swipe. */
        data object Cancel : Selection

        /** Neither a wedge nor the centre. */
        data object None : Selection
    }

    /** Whether a ring is open. */
    val isOpen: Boolean

    /**
     * Opens a ring on [words], the wedge at [trustedIndex] marked as the word already in the
     * field; false when a ring is already open or [words] is empty. A ring that [waitsForTap]
     * takes its own touches; any other follows the stroke that opened it and resolves itself
     * after [pickTimeoutMillis], or never with null.
     */
    fun open(
        words: List<String>,
        trustedIndex: Int,
        waitsForTap: Boolean,
        pickTimeoutMillis: Long?,
    ): Boolean

    /** What the finger is over now; [Selection.None] with no ring. */
    fun selection(): Selection

    /** Keeps the ring up after the lift, waiting for a tap, with no pick timeout. */
    fun keepOpenForTap()

    /** Closes the ring; [celebrateIndex] is the picked wedge, or null. */
    fun close(celebrateIndex: Int?)

    /** Closes a swipe's ring and forgets its stroke; true when one was open. */
    fun dismiss(): Boolean

    /** A paused stroke that opened no ring goes back to being captured as a swipe. */
    fun resumeGestureCapture()
}
