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

    /** Each field starts with shift and caps lock off. */
    override fun onFieldStarted(field: FieldSession) {
        heldByUser = false
        userReleasedAutoLock = false
        autoLocked = false
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
        host.showShiftState(state)
    }

    /** Locks shift, from holding it. */
    fun lock() {
        state = ShiftState.LOCKED
        heldByUser = true
        autoLocked = false
        userReleasedAutoLock = false
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
    }

    /** The user's shift press is used up. */
    fun clearHeld() {
        heldByUser = false
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
     * Cases a swipe's [candidates] as typed letters would come out under the current shift, names
     * capitalised, then spends a one-shot shift. Never lower-cases. Drops candidates that become
     * the same text.
     */
    fun caseSwiped(candidates: List<Candidate>): List<Candidate> {
        var cased = candidates.map { candidate ->
            if (candidate.isProperNoun) {
                candidate.copy(text = candidate.text.replaceFirstChar { it.uppercaseChar() })
            } else {
                candidate
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
        spendOneShot()
        heldByUser = false
        return cased.distinctBy { it.text }
    }

    /**
     * What shift should be here, per [AutoShift], from the field's caps mode and the text before
     * the cursor.
     */
    private fun autoState(composingEmpty: Boolean, justCommitted: String): Int {
        if (!session.described) {
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
