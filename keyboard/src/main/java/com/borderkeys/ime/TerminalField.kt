// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.borderkeys.data.TerminalApps

/**
 * Whether the field being typed into is a terminal: a view with a buffer of its own, which
 * shows what is committed to it the moment it is committed and never a composing region, and
 * which deletes by the key events it is sent rather than by the text around a caret it does not
 * have.
 *
 * Two independent signs, either enough:
 *
 *  * **A field of no class.** `InputType.TYPE_NULL` is what a terminal declares, being no kind
 *    of text field at all; an ordinary editor always declares a class.
 *  * **An app known to be a terminal**, or one the person added, for the ones that declare a
 *    text class anyway: [TerminalApps].
 *
 * Pure, so the test suite can enumerate the input types the platform defines.
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
