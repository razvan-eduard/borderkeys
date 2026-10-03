// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.ime.ComposeSequences
import com.borderkeys.ime.DeadKeys

/**
 * A dead key waiting for its letter, or a compose sequence being spelled. A dead key latches
 * until the next character; pressed twice, or held, it locks until pressed again; an arrow
 * writes its bare combining mark. The compose key gathers characters until they spell an entry
 * of [sequences], or none can.
 */
class AccentFlow(private val host: TypingHost) : TypingFlow() {

    /** What the orchestrator writes for a character typed while an accent is pending. */
    sealed interface Resolution {
        /** One character, typed as its key would be. */
        class Code(val code: Int) : Resolution

        /** Text written whole. */
        class Text(val text: String) : Resolution

        /** Text written whole, then one character typed. */
        class TextThenCode(val text: String, val code: Int) : Resolution

        /** Taken into the sequence, or dropped with it: nothing is written. */
        object Absorbed : Resolution
    }

    var sequences: ComposeSequences = ComposeSequences.EMPTY

    private var deadCode = 0
    private var deadLocked = false
    private var composeTyped: StringBuilder? = null

    /** Whether a dead key or a compose sequence is waiting. */
    val pending: Boolean get() = deadCode != 0 || composeTyped != null

    override fun onFieldStarted(field: FieldSession) = clear()

    override fun onFieldFinished() = clear()

    /** A dead key: arms it; the same one again locks it, and once more releases it. */
    fun onDeadKey(code: Int) {
        composeTyped = null
        when {
            deadCode != code -> {
                deadCode = code
                deadLocked = false
            }
            !deadLocked -> deadLocked = true
            else -> deadCode = 0
        }
        show()
    }

    /** A dead key held: locked until pressed again. */
    fun lockDeadKey(code: Int) {
        composeTyped = null
        deadCode = code
        deadLocked = true
        show()
    }

    /** The compose key: starts a sequence, or drops the one being spelled. */
    fun onCompose() {
        deadCode = 0
        deadLocked = false
        composeTyped = if (composeTyped == null) StringBuilder() else null
        show()
    }

    /**
     * The character [shifted] typed while pending: a dead key's letter with its mark, its bare
     * accent for a space, both for a character that takes no mark; a compose sequence's text
     * once spelled.
     */
    fun resolve(shifted: Int, isSpace: Boolean): Resolution {
        val typed = composeTyped
        if (typed != null) {
            typed.appendCodePoint(shifted)
            return when (val step = sequences.step(typed.toString())) {
                is ComposeSequences.Step.Done -> {
                    composeTyped = null
                    show()
                    val text = step.text
                    if (text.codePointCount(0, text.length) == 1) Resolution.Code(text.codePointAt(0)) else Resolution.Text(text)
                }
                ComposeSequences.Step.More -> {
                    show()
                    Resolution.Absorbed
                }
                ComposeSequences.Step.NoMatch -> {
                    composeTyped = null
                    show()
                    Resolution.Absorbed
                }
            }
        }
        val dead = deadCode
        val accent = DeadKeys.accent(dead) ?: return Resolution.Code(shifted)
        if (!deadLocked || isSpace) {
            deadCode = 0
            deadLocked = false
            show()
        }
        if (isSpace) {
            return Resolution.Text(accent.spacing)
        }
        val combined = DeadKeys.combine(shifted, dead)
        return if (combined != null) Resolution.Code(combined) else Resolution.TextThenCode(accent.spacing, shifted)
    }

    /** An arrow while a dead key waits: its combining mark, the dead key released; null when none waits. */
    fun onArrow(): String? {
        val accent = DeadKeys.accent(deadCode) ?: return null
        deadCode = 0
        deadLocked = false
        show()
        return accent.mark.toString()
    }

    /** Backspace while pending: a compose sequence loses its last character, a dead key is dropped. Returns whether it was taken. */
    fun onDelete(): Boolean {
        val typed = composeTyped
        if (typed != null) {
            if (typed.isEmpty()) {
                composeTyped = null
            } else {
                typed.setLength(typed.offsetByCodePoints(typed.length, -1))
            }
            show()
            return true
        }
        if (deadCode != 0) {
            clear()
            return true
        }
        return false
    }

    /** Drops whatever is pending. */
    fun clear() {
        if (!pending) {
            return
        }
        deadCode = 0
        deadLocked = false
        composeTyped = null
        show()
    }

    private fun show() {
        host.showAccent(deadCode, deadLocked, composeTyped?.toString())
    }
}
