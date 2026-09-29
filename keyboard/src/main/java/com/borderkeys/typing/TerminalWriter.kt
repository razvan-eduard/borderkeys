// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import android.view.KeyEvent
import com.borderkeys.ime.PhysicalKeys

/**
 * Typing into a terminal: each character goes out at once as the key that carries it, never
 * composed, and the letters typed since the last delimiter are the word the strip completes.
 */
class TerminalWriter(private val host: TypingHost) : TypingFlow() {

    /** The letters typed since the last delimiter. */
    private val typedWord = StringBuilder()

    /** The letters typed since the last delimiter. */
    val word: String get() = typedWord.toString()

    /** Each field starts with no letters typed. */
    override fun onFieldStarted(field: FieldSession) {
        typedWord.setLength(0)
    }

    fun clearWord() {
        typedWord.setLength(0)
    }

    /** Whether [code] extends the word: a letter starts one, and a word character continues it. */
    fun continuesWord(code: Int): Boolean =
        if (typedWord.isEmpty()) Character.isLetter(code) else TypingOrchestrator.isWordCharacter(code)

    /** Types [code]; with [letter], from [continuesWord], it extends the word, else ends it. */
    fun type(editor: FieldEditor, code: Int, letter: Boolean) {
        write(editor, String(Character.toChars(code)))
        if (letter) {
            typedWord.appendCodePoint(code)
        } else {
            typedWord.setLength(0)
        }
    }

    /** One character back, as the key event a terminal deletes by. */
    fun deleteBack() {
        host.sendPhysicalKey(KeyEvent.KEYCODE_DEL, 0)
        if (typedWord.isNotEmpty()) {
            typedWord.setLength(typedWord.offsetByCodePoints(typedWord.length, -1))
        }
    }

    /**
     * Replaces the letters typed with [picked] and [space]. A word that continues the letters has
     * only its remainder written; any other deletes them first.
     */
    fun pick(editor: FieldEditor, picked: String, space: String) {
        val typed = typedWord.toString()
        editor.beginBatchEdit()
        if (picked.length >= typed.length && picked.startsWith(typed)) {
            write(editor, picked.substring(typed.length) + space)
        } else {
            repeat(typed.codePointCount(0, typed.length)) {
                host.sendPhysicalKey(KeyEvent.KEYCODE_DEL, 0)
            }
            write(editor, picked + space)
        }
        editor.endBatchEdit()
        typedWord.setLength(0)
        if (space.isEmpty()) {
            typedWord.append(picked)
        }
    }

    /**
     * Writes a swiped word whole, after a space when letters were typed just before it; the word
     * is then [swiped], so a pick replaces it the way it replaces typed letters.
     */
    fun writeSwiped(editor: FieldEditor, swiped: String) {
        editor.beginBatchEdit()
        write(editor, if (typedWord.isNotEmpty()) " $swiped" else swiped)
        editor.endBatchEdit()
        typedWord.setLength(0)
        typedWord.append(swiped)
    }

    /** Enter: the key itself, which is what runs the line. */
    fun enter() {
        typedWord.setLength(0)
        host.sendPhysicalKey(KeyEvent.KEYCODE_ENTER, 0)
    }

    /**
     * Writes [text]: each character as the key that carries it, shift held for a capital, and as
     * text only where no plain key carries it.
     */
    private fun write(editor: FieldEditor, text: String) {
        var index = 0
        while (index < text.length) {
            val code = text.codePointAt(index)
            index += Character.charCount(code)
            val keyCode = if (code < 128) PhysicalKeys.keyCodeFor(code) else 0
            if (keyCode == 0) {
                editor.commitText(String(Character.toChars(code)), 1)
                continue
            }
            val meta = if (Character.isUpperCase(code)) ShiftFlow.SHIFT_META else 0
            host.sendPhysicalKey(keyCode, meta)
        }
    }
}
