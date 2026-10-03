// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.json.JSONObject

/**
 * What a layout's shift and control turn a character into where the usual rule does not hold,
 * from the `"modmap"` object at the layout's root: `{"shift": {"i": "İ"}, "ctrl": {"с": "c"}}`.
 * Shift otherwise upper-cases; control otherwise sends the character's own key.
 */
class Modmap(val shift: Map<Int, Int>, val ctrl: Map<Int, Int>) {

    /** [code] under shift. */
    fun shifted(code: Int): Int = shift[code] ?: Character.toUpperCase(code)

    /** [character] under shift, for drawing; a mapping outside the basic plane draws upper-cased. */
    fun shiftedChar(character: Char): Char {
        val mapped = shift[character.code] ?: return Character.toUpperCase(character)
        return if (Character.isBmpCodePoint(mapped)) mapped.toChar() else Character.toUpperCase(character)
    }

    /** The character whose key control sends for [code]. */
    fun forControl(code: Int): Int = ctrl[code] ?: code

    val isEmpty: Boolean get() = shift.isEmpty() && ctrl.isEmpty()

    companion object {
        val NONE = Modmap(emptyMap(), emptyMap())

        /** The names a modmap may hold. */
        val SECTIONS = listOf("shift", "ctrl")

        /** The modmap in [root]'s `"modmap"`; none when absent. Entries that are not one character each are left out. */
        fun parse(root: JSONObject): Modmap {
            val json = root.optJSONObject("modmap") ?: return NONE
            fun section(name: String): Map<Int, Int> {
                val entries = json.optJSONObject(name) ?: return emptyMap()
                val out = HashMap<Int, Int>()
                for (from in entries.keys()) {
                    val to = entries.optString(from)
                    if (from.codePointCount(0, from.length) == 1 && to.codePointCount(0, to.length) == 1) {
                        out[from.codePointAt(0)] = to.codePointAt(0)
                    }
                }
                return out
            }
            val modmap = Modmap(section("shift"), section("ctrl"))
            return if (modmap.isEmpty) NONE else modmap
        }
    }
}
