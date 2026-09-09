// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.text.InputType

/**
 * What shift should be, from a field's request and what the platform says about the text before
 * the cursor.
 *
 * Its own object, for the same reason as [AutoCorrection]: the decision is worth being able to
 * test without an editor, an input connection or a real field. Returns the same three states
 * [BorderKeysService] itself uses -- 0 off, 1 on for one character, 2 locked -- so the caller
 * needs no translation, only its own named constants for readability at the call site.
 *
 * [capsMode] is a callback rather than a plain value because it is the one part of the answer
 * that genuinely needs a live connection
 * ([android.view.inputmethod.InputConnection.getCursorCapsMode]), and it is called only on the
 * one branch that needs it -- a field asking for no capitalisation, one with something already
 * composing, or one that is not text at all never reaches it.
 */
internal object AutoShift {
    fun stateFor(
        autoCapitaliseEnabled: Boolean,
        inputType: Int,
        composingIsEmpty: Boolean,
        capsMode: () -> Int,
    ): Int {
        if (!autoCapitaliseEnabled) {
            return OFF
        }
        // Gated on the class before anything else: a field that is not text -- a number pad, a
        // phone field -- can have those same bit positions set for its own unrelated reasons,
        // and reading them without this check is how such a field could be misread as wanting
        // capitals.
        if ((inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
            return OFF
        }
        if ((inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0) {
            return LOCKED
        }
        val words = (inputType and InputType.TYPE_TEXT_FLAG_CAP_WORDS) != 0
        val sentences = (inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0
        if (!words && !sentences) {
            return OFF
        }
        // The composing region is text the user is in the middle of; if there is any, they are
        // inside a word and nothing should be capitalised.
        if (!composingIsEmpty) {
            return OFF
        }
        return if (capsMode() != 0) ON else OFF
    }

    private const val OFF = 0
    private const val ON = 1
    private const val LOCKED = 2
}
