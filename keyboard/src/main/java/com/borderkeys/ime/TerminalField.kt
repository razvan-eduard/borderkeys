// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.borderkeys.data.TerminalApps

/**
 * Whether the field being typed into is a terminal, which shows commits at once and deletes by
 * key events: a field of no class (`TYPE_NULL`), or an app in [TerminalApps] or added by the
 * person.
 */
internal object TerminalField {

    /** Whether the field in [info] is a terminal, with [added] the packages the person added. */
    fun isTerminal(info: EditorInfo?, added: List<String> = emptyList()): Boolean {
        if (info == null) {
            return false
        }
        return isBareField(info.inputType) || TerminalApps.isTerminalApp(info.packageName, added)
    }

    /** A field that declares no class at all: `TYPE_NULL`, whatever flags ride with it. */
    fun isBareField(inputType: Int): Boolean =
        (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL
}
