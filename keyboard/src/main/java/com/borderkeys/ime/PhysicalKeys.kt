// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.view.KeyEvent

/**
 * The hardware key a character sits on, for a character typed while control or alt is held.
 *
 * An application reading a shortcut looks at the key, not the character: control with the
 * key that carries `a` is "select all" whatever the keyboard's letters say. Only the keys
 * a plain keyboard has are mapped; a character off that set has no key to send.
 */
internal object PhysicalKeys {

    /** The key code for [character], or 0 when no plain key carries it. */
    fun keyCodeFor(character: Int): Int {
        val lower = Character.toLowerCase(character)
        return when {
            lower in 'a'.code..'z'.code -> KeyEvent.KEYCODE_A + (lower - 'a'.code)
            lower in '0'.code..'9'.code -> KeyEvent.KEYCODE_0 + (lower - '0'.code)
            else -> when (lower) {
                ' '.code -> KeyEvent.KEYCODE_SPACE
                ','.code -> KeyEvent.KEYCODE_COMMA
                '.'.code -> KeyEvent.KEYCODE_PERIOD
                '-'.code -> KeyEvent.KEYCODE_MINUS
                '='.code -> KeyEvent.KEYCODE_EQUALS
                '/'.code -> KeyEvent.KEYCODE_SLASH
                '\\'.code -> KeyEvent.KEYCODE_BACKSLASH
                ';'.code -> KeyEvent.KEYCODE_SEMICOLON
                '\''.code -> KeyEvent.KEYCODE_APOSTROPHE
                '`'.code -> KeyEvent.KEYCODE_GRAVE
                '['.code -> KeyEvent.KEYCODE_LEFT_BRACKET
                ']'.code -> KeyEvent.KEYCODE_RIGHT_BRACKET
                else -> 0
            }
        }
    }
}
