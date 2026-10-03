// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * What a key is, as an int: a character key's code point, space included, or a negative action
 * code.
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
    /** Opens the system's keyboard picker, or switches back to the previous keyboard. */
    const val KEYBOARD_PICKER = -23
    /** Switches to a voice keyboard. */
    const val VOICE = -24
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
        "ime_picker" -> KEYBOARD_PICKER
        "voice" -> VOICE
        else -> NONE
    }
}

/** Per-key bit flags. */
object KeyFlags {
    const val NONE = 0

    /** Drawn in the modifier colour: shift, delete, symbols, enter. */
    const val MODIFIER = 1 shl 0

    /** Repeats while held: delete and the arrow keys. */
    const val REPEATABLE = 1 shl 1

    /** Participates in swipe typing: letters only. */
    const val LETTER = 1 shl 2

    /** Has long-press alternatives. */
    const val HAS_ALTERNATIVES = 1 shl 3

    /** Shows a preview bubble on press. Suppressed for modifiers and for space. */
    const val PREVIEW = 1 shl 4

    /** Drawn in the modifier fill without being a modifier: the number row. */
    const val SECONDARY_ROW = 1 shl 5

    /** Takes the width of an optional key the layout drops, instead of the space bar. */
    const val ABSORBS_FREED_WIDTH = 1 shl 6

    fun has(flags: Int, flag: Int): Boolean = (flags and flag) != 0
}
