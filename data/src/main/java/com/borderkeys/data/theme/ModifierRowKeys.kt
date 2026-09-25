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

    /** Every key the row can carry, in the order the editor offers them. */
    val ALL: List<String> = listOf(
        ESCAPE, TAB, CONTROL, ALT, LEFT, DOWN, UP, RIGHT,
        HOME, END, PAGE_UP, PAGE_DOWN, FORWARD_DELETE, INSERT,
    )

    /** The row as shipped. */
    val DEFAULT: List<String> = listOf(ESCAPE, TAB, CONTROL, ALT, LEFT, DOWN, UP, RIGHT)

    /** The most keys one row takes; past this each key is too narrow to hit. */
    const val MAX = 12

    fun sanitised(names: List<String>): List<String> =
        names.distinct().filter { it in ALL }.take(MAX)
}
