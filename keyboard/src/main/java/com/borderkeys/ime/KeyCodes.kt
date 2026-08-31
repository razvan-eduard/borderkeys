// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * What a key on the keyboard is, as an int.
 *
 * Character keys carry their own Unicode code point, so the common case -- the one that runs on
 * every key press -- needs no lookup at all: the code *is* the character to commit. Actions are
 * negative, which makes "is this a character?" a sign test rather than a set membership check.
 *
 * Space is 32 rather than an action code, because it is a character and behaves like one
 * everywhere except that it also ends a word.
 */
object KeyCodes {
    const val SPACE = ' '.code

    const val SHIFT = -1
    const val DELETE = -2
    const val ENTER = -3
    const val SYMBOLS = -4
    const val LANGUAGE = -5
    const val EMOJI = -6
    const val SETTINGS = -7
    const val NONE = -100

    fun isCharacter(code: Int): Boolean = code > 0

    /** Maps the names used in the layout assets. Returns [NONE] for anything unrecognised. */
    fun named(name: String): Int = when (name) {
        "shift" -> SHIFT
        "delete" -> DELETE
        "enter" -> ENTER
        "space" -> SPACE
        "symbols" -> SYMBOLS
        "language" -> LANGUAGE
        "emoji" -> EMOJI
        "settings" -> SETTINGS
        else -> NONE
    }
}

/**
 * Per-key bit flags, packed into one int so the draw and touch paths read a primitive array
 * rather than dereferencing an object per key.
 */
object KeyFlags {
    const val NONE = 0

    /** Drawn in the modifier colour: shift, delete, symbols, enter. */
    const val MODIFIER = 1 shl 0

    /** Repeats while held. Delete, and the arrow keys if they ever exist. */
    const val REPEATABLE = 1 shl 1

    /** Participates in swipe typing. Letters only: a gesture across shift means nothing. */
    const val LETTER = 1 shl 2

    /** Has long-press alternatives. */
    const val HAS_ALTERNATIVES = 1 shl 3

    /** Shows a preview bubble on press. Suppressed for modifiers and for space. */
    const val PREVIEW = 1 shl 4

    fun has(flags: Int, flag: Int): Boolean = (flags and flag) != 0
}
