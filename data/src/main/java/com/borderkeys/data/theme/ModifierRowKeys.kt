// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

/**
 * The keys the modifier row can carry, by the names the layout assets use, and the order
 * the row shows by default. A stored list is read through [sanitised], which drops a name
 * this build does not know and a repeat.
 */
object ModifierRowKeys {
    const val ESCAPE = "escape"
    const val TAB = "tab"
    const val CONTROL = "control"
    const val ALT = "alt"
    const val LEFT = "left"
    const val DOWN = "down"
    const val UP = "up"
    const val RIGHT = "right"
    const val HOME = "home"
    const val END = "end"
    const val PAGE_UP = "page_up"
    const val PAGE_DOWN = "page_down"
    const val FORWARD_DELETE = "forward_delete"
    const val INSERT = "insert"
    const val KEYBOARD_PICKER = "ime_picker"
    const val VOICE = "voice"
    const val DEAD_ACUTE = "dead_acute"
    const val DEAD_GRAVE = "dead_grave"
    const val DEAD_CIRCUMFLEX = "dead_circumflex"
    const val DEAD_DIAERESIS = "dead_diaeresis"
    const val DEAD_TILDE = "dead_tilde"
    const val DEAD_CARON = "dead_caron"
    const val DEAD_BREVE = "dead_breve"
    const val DEAD_CEDILLA = "dead_cedilla"
    const val DEAD_OGONEK = "dead_ogonek"
    const val DEAD_RING = "dead_ring"
    const val DEAD_MACRON = "dead_macron"
    const val DEAD_COMMA_BELOW = "dead_comma_below"
    const val COMPOSE = "compose"

    /** The dead keys, in the order the editor offers them. */
    val DEAD_KEYS: List<String> = listOf(
        DEAD_ACUTE, DEAD_GRAVE, DEAD_CIRCUMFLEX, DEAD_DIAERESIS, DEAD_TILDE, DEAD_CARON, DEAD_BREVE, DEAD_CEDILLA, DEAD_OGONEK, DEAD_RING, DEAD_MACRON, DEAD_COMMA_BELOW,
    )

    /** Every key the row can carry, in the order the editor offers them. */
    val ALL: List<String> = listOf(
        ESCAPE, TAB, CONTROL, ALT, LEFT, DOWN, UP, RIGHT,
        HOME, END, PAGE_UP, PAGE_DOWN, FORWARD_DELETE, INSERT, KEYBOARD_PICKER, VOICE,
    ) + DEAD_KEYS + COMPOSE

    /** The row as shipped. */
    val DEFAULT: List<String> = listOf(ESCAPE, TAB, CONTROL, ALT, LEFT, DOWN, UP, RIGHT)

    /** The most keys one row takes; past this each key is too narrow to hit. */
    const val MAX = 12

    fun sanitised(names: List<String>): List<String> =
        names.distinct().filter { it in ALL }.take(MAX)
}
