// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

/** The swipe ring as the typing flow sees it, recorded for the tests to read. */
internal class FakeRingUi : RingUi {

    /** One ring opened: its words, its marked wedge, and how it resolves. */
    class Opened(
        val words: List<String>,
        val trustedIndex: Int,
        val waitsForTap: Boolean,
        val pickTimeoutMillis: Long?,
    )

    /** Every ring opened, in order. */
    val opened = mutableListOf<Opened>()

    /** What the finger is over while a ring is open. */
    var selectionNow: RingUi.Selection = RingUi.Selection.None

    /** Whether rings refuse to open, as the real one does with another ring up. */
    var refuses = false

    /** The picked wedge of each ring closed, null for none. */
    val closed = mutableListOf<Int?>()

    var dismissals = 0
        private set
    var keptOpenForTap = 0
        private set
    var resumedCaptures = 0
        private set

    override var isOpen = false
        private set

    override fun open(
        words: List<String>,
        trustedIndex: Int,
        waitsForTap: Boolean,
        pickTimeoutMillis: Long?,
    ): Boolean {
        if (refuses || isOpen || words.isEmpty()) {
            return false
        }
        isOpen = true
        opened += Opened(words, trustedIndex, waitsForTap, pickTimeoutMillis)
        return true
    }

    override fun selection(): RingUi.Selection = if (isOpen) selectionNow else RingUi.Selection.None

    override fun keepOpenForTap() {
        keptOpenForTap++
    }

    override fun close(celebrateIndex: Int?) {
        closed += celebrateIndex
        isOpen = false
        selectionNow = RingUi.Selection.None
    }

    override fun dismiss(): Boolean {
        if (!isOpen) {
            return false
        }
        dismissals++
        isOpen = false
        selectionNow = RingUi.Selection.None
        return true
    }

    override fun resumeGestureCapture() {
        resumedCaptures++
    }
}
