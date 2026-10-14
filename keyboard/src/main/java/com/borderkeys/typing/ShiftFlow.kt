// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import android.view.KeyEvent
import com.borderkeys.ime.AutoCorrection
import com.borderkeys.ime.AutoShift
import com.borderkeys.ime.Modmap
import com.borderkeys.ime.ShiftState
import com.borderkeys.predict.Candidate

/**
 * Shift: pressed, held into caps lock, set by the field and the text before the caret, spent by a
 * letter; and the casing it gives the strip's words and a swipe's candidates.
 */
class ShiftFlow(
    /** The field being typed into, or null when none is bound. */
    private val currentEditor: () -> FieldEditor?,
    private val host: TypingHost,
    private val clock: TypingClock,
) : TypingFlow() {

    /** A [ShiftState] value. */
    var state = ShiftState.OFF
        private set

    /** Set when the user pressed shift, cleared by the character it applied to. */
    var heldByUser = false
        private set

    /** Set when the user released a caps lock that auto-shift applied, until the next letter. */
    private var userReleasedAutoLock = false

    /** Whether the current lock came from the field asking for capitals rather than from shift. */
    private var autoLocked = false
    private var lastPressAt = 0L

    /**
     * Shift as it stood before the last swiped word was cased, until shift is pressed or the word
     * is kept by a letter, a pick or a delimiter.
     */
    private var beforeSwipe: Pair<Int, Boolean>? = null

    /**
     * Whether a capital written now is the user's: shift pressed for it ([heldByUser]), or a caps
     * lock the user set rather than the field. Whether a capital is written is [userCapital]'s.
     */
    val capitalByUser: Boolean
        get() = heldByUser || (state == ShiftState.LOCKED && !autoLocked)

    /** Whether [written], a word's first letter as it comes out, is a capital the user chose. */
    fun userCapital(written: Int): Boolean = capitalByUser && Character.isUpperCase(written)

    /** Each field starts with shift and caps lock off. */
    override fun onFieldStarted(field: FieldSession) {
        heldByUser = false
        userReleasedAutoLock = false
        autoLocked = false
        beforeSwipe = null
        state = ShiftState.OFF
        host.showShiftState(state)
    }

    /** The shift key: on, off, or locked by two taps within [DOUBLE_TAP_MILLIS] from any state. */
    fun press() {
        val now = clock.currentTimeMillis()
        val doubleTap = now - lastPressAt < DOUBLE_TAP_MILLIS
        val releasedAutoLock = state == ShiftState.LOCKED && autoLocked
        state = when {
            state == ShiftState.LOCKED -> ShiftState.OFF
            doubleTap -> ShiftState.LOCKED
            state == ShiftState.ON -> ShiftState.OFF
            else -> ShiftState.ON
        }
        lastPressAt = now
        heldByUser = state != ShiftState.OFF
        autoLocked = false
        userReleasedAutoLock = releasedAutoLock
        beforeSwipe = null
        host.showShiftState(state)
    }

    /** Locks shift, from holding it. */
    fun lock() {
        state = ShiftState.LOCKED
        heldByUser = true
        autoLocked = false
        userReleasedAutoLock = false
        beforeSwipe = null
        host.showShiftState(state)
    }

    /** The shown layout's modmap, which [shifted] follows. */
    var modmap: Modmap = Modmap.NONE

    /** [code] as the letter key types it under the current shift. */
    fun shifted(code: Int): Int = if (state != ShiftState.OFF) modmap.shifted(code) else code

    /** A one-shot shift is spent. */
    fun spendOneShot() {
        if (state == ShiftState.ON) {
            state = ShiftState.OFF
            host.showShiftState(state)
        }
    }

    /** A letter was typed: the user's shift press and a released automatic lock are used up. */
    fun letterTyped() {
        heldByUser = false
        userReleasedAutoLock = false
        beforeSwipe = null
    }

    /** The user's shift press is used up. */
    fun clearHeld() {
        heldByUser = false
        beforeSwipe = null
    }

    /**
     * The shift bits for a hardware key: set only while the user holds shift. With [spend], a
     * one-shot shift is spent by the key.
     */
    fun heldMeta(spend: Boolean): Int {
        if (!heldByUser || state == ShiftState.OFF) {
            return 0
        }
        if (spend && state == ShiftState.ON) {
            state = ShiftState.OFF
            host.showShiftState(state)
        }
        return SHIFT_META
    }

    /**
     * Re-derives shift after a delimiter, unless caps lock is on or the user pressed shift
     * ([heldByUser]). [justCommitted] is what this keystroke wrote; see [applyAuto].
     */
    fun afterDelimiter(heldByUser: Boolean, composingEmpty: Boolean, justCommitted: String = "") {
        beforeSwipe = null
        if (state == ShiftState.LOCKED || heldByUser) {
            return
        }
        applyAuto(composingEmpty, justCommitted)
    }

    /**
     * Sets shift from what the field asks for and the text before the caret, unless the user set
     * it. [justCommitted] is text just written, appended to what the editor reports.
     */
    fun applyAuto(composingEmpty: Boolean, justCommitted: String = "") {
        if (state == ShiftState.LOCKED && !autoLocked) {
            return
        }
        if (heldByUser || userReleasedAutoLock) {
            return
        }
        val wanted = autoState(composingEmpty, justCommitted)
        autoLocked = wanted == ShiftState.LOCKED
        if (state != wanted) {
            state = wanted
            host.showShiftState(state)
        }
    }

    /**
     * Cases the strip's [candidates]: after [typed] as it was typed; with nothing typed, by shift.
     * Caps lock wins over a name's capital; otherwise a name is capitalised and any other word
     * starts lower case.
     */
    fun caseForStrip(candidates: List<Candidate>, typed: String): List<Candidate> =
        candidates.map { candidate ->
            val word = candidate.text
            candidate.copy(
                text = if (typed.isNotEmpty()) {
                    AutoCorrection.matchCase(
                        typed, word, candidate.isProperNoun && settings.capitaliseNames,
                    )
                } else {
                    when {
                        state == ShiftState.LOCKED -> word.uppercase()
                        candidate.isProperNoun && settings.capitaliseNames ->
                            word.replaceFirstChar { it.uppercaseChar() }
                        state == ShiftState.ON -> word.replaceFirstChar { it.uppercaseChar() }
                        else -> word.replaceFirstChar { it.lowercaseChar() }
                    }
                },
            )
        }

    /**
     * Cases a swipe's [candidates] as typed letters would come out under the current shift, a name
     * capitalised or not as the strip writes it. Lower-cases only a name. Drops candidates that
     * become the same text.
     */
    fun caseSwiped(candidates: List<Candidate>): List<Candidate> {
        var cased = candidates.map { candidate ->
            when {
                !candidate.isProperNoun -> candidate
                settings.capitaliseNames ->
                    candidate.copy(text = candidate.text.replaceFirstChar { it.uppercaseChar() })
                else -> candidate.copy(text = candidate.text.replaceFirstChar { it.lowercaseChar() })
            }
        }
        val current = state
        if (current != ShiftState.OFF) {
            cased = cased.map { candidate ->
                candidate.copy(
                    text = if (current == ShiftState.LOCKED) {
                        candidate.text.uppercase()
                    } else {
                        candidate.text.replaceFirstChar { it.uppercaseChar() }
                    },
                )
            }
        }
        return cased.distinctBy { it.text }
    }

    /**
     * A swiped word was cased: a one-shot shift is spent and the user's shift press used up, both
     * given back by [restoreAfterSwipeTakenBack].
     */
    fun swipedWordCased() {
        beforeSwipe = state to heldByUser
        spendOneShot()
        heldByUser = false
    }

    /** The swiped word last cased was taken back: shift is as it stood before it. */
    fun restoreAfterSwipeTakenBack() {
        val (before, held) = beforeSwipe ?: return
        beforeSwipe = null
        heldByUser = held
        if (state != before) {
            state = before
            host.showShiftState(state)
        }
    }

    /**
     * What shift should be here, per [AutoShift], from the field's caps mode and the text before
     * the cursor.
     */
    private fun autoState(composingEmpty: Boolean, justCommitted: String): Int {
        // Switched off by hand, the keys get no capital they did not press.
        if (!session.described || session.policy.offByHand) {
            return ShiftState.OFF
        }
        return AutoShift.stateFor(
            autoCapitaliseEnabled = settings.autoCapitalise,
            inputType = session.inputType,
            composingIsEmpty = composingEmpty,
            forceCapitaliseSentences = settings.forceCapitaliseSentences,
            capsMode = {
                currentEditor()?.cursorCapsMode(session.inputType) ?: session.initialCapsMode
            },
            textBeforeCursor = {
                val before = currentEditor()?.textBeforeCursor(TypingOrchestrator.CONTEXT_WINDOW_CHARS)
                if (justCommitted.isEmpty()) before else (before ?: "").toString() + justCommitted
            },
        )
    }

    companion object {
        const val DOUBLE_TAP_MILLIS = 400L

        /** Shift held, as a key event carries it. */
        const val SHIFT_META = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
    }
}
