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
    /** The second symbols page: the one behind "=\\<". */
    const val SYMBOLS_SHIFT = -8

    /** The modifier row: each sends the application a hardware key rather than a character. */
    const val ESCAPE = -9
    const val TAB = -10
    /** Held for the next key, like [ALT]: the next character or arrow goes out with it set. */
    const val CONTROL = -11
    const val ALT = -12
    const val ARROW_LEFT = -13
    const val ARROW_RIGHT = -14
    const val ARROW_UP = -15
    const val ARROW_DOWN = -16
    const val HOME = -17
    const val END = -18
    const val PAGE_UP = -19
    const val PAGE_DOWN = -20
    /** Deletes the character after the caret, as the hardware key does. */
    const val FORWARD_DELETE = -21
    const val INSERT = -22
    const val NONE = -100

    fun isCharacter(code: Int): Boolean = code > 0

    fun isArrow(code: Int): Boolean =
        code == ARROW_LEFT || code == ARROW_RIGHT || code == ARROW_UP || code == ARROW_DOWN

    /** A key that moves the caret: an arrow, home, end, page up or page down. */
    fun isNavigation(code: Int): Boolean =
        isArrow(code) || code == HOME || code == END || code == PAGE_UP || code == PAGE_DOWN

    /** A key that repeats while held on the modifier row: the arrows and forward delete. */
    fun repeatsOnModifierRow(code: Int): Boolean = isArrow(code) || code == FORWARD_DELETE

    /** [CONTROL] or [ALT]: a key that arms itself for the next key instead of typing. */
    fun isHeldModifier(code: Int): Boolean = code == CONTROL || code == ALT

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
        "symbols_shift" -> SYMBOLS_SHIFT
        "escape" -> ESCAPE
        "tab" -> TAB
        "control" -> CONTROL
        "alt" -> ALT
        "left" -> ARROW_LEFT
        "right" -> ARROW_RIGHT
        "up" -> ARROW_UP
        "down" -> ARROW_DOWN
        "home" -> HOME
        "end" -> END
        "page_up" -> PAGE_UP
        "page_down" -> PAGE_DOWN
        "forward_delete" -> FORWARD_DELETE
        "insert" -> INSERT
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

    /** Repeats while held: delete and the arrow keys. */
    const val REPEATABLE = 1 shl 1

    /** Participates in swipe typing. Letters only: a gesture across shift means nothing. */
    const val LETTER = 1 shl 2

    /** Has long-press alternatives. */
    const val HAS_ALTERNATIVES = 1 shl 3

    /** Shows a preview bubble on press. Suppressed for modifiers and for space. */
    const val PREVIEW = 1 shl 4

    /** Drawn in the modifier fill without being a modifier: the number row, set apart from the
     *  letters the way a physical keyboard's function row is. */
    const val SECONDARY_ROW = 1 shl 5

    /**
     * Takes the width of an optional key the layout drops, instead of the space bar.
     *
     * Only the numpad symbol pages set it, and only on the key that sits between the space bar
     * and the digits. Everywhere else the space bar absorbs, which is right: it is the key that
     * gave the width up when the emoji or globe key took it, and it has no column to keep.
     * A digit block does: the `0` is under the `1` only while nothing between them has moved,
     * and giving the freed width to the space bar slid every key on its side of the row.
     */
    const val ABSORBS_FREED_WIDTH = 1 shl 6

    fun has(flags: Int, flag: Int): Boolean = (flags and flag) != 0
}
