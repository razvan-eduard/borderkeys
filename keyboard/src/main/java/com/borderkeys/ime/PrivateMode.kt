// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Decides whether the field currently being typed into is one the keyboard must forget entirely.
 *
 * In private mode there is no learning, no clipboard history, no suggestion from the personal
 * dictionary and no text assistant. This is a security requirement rather than a preference, so
 * it is a pure function of the [EditorInfo] the framework hands over -- no setting can switch it
 * off, and the test suite can enumerate every input type the platform defines.
 *
 * Two independent triggers:
 *
 *  * **A password field**, in any of its four spellings. The variation bits are checked against
 *    the right class each time, because `TYPE_TEXT_VARIATION_PASSWORD` (0x80) and
 *    `TYPE_NUMBER_VARIATION_PASSWORD` (0x10) are different values in different classes, and
 *    comparing a variation without its class is how a numeric PIN field ends up treated as
 *    ordinary text.
 *  * **`IME_FLAG_NO_PERSONALIZED_LEARNING`**, which is an application saying "do not remember
 *    this" about a field that is not a password -- a search box in an incognito tab, a medical
 *    form, a message in a disappearing chat. Honouring it is the whole reason the flag exists.
 */
object PrivateMode {

    fun isPrivate(info: EditorInfo?): Boolean {
        if (info == null) {
            return true // Nothing is known about the field, so assume the careful answer.
        }
        return isPrivate(info.inputType, info.imeOptions)
    }

    fun isPrivate(inputType: Int, imeOptions: Int): Boolean =
        isNoPersonalizedLearning(imeOptions) || isPasswordField(inputType)

    fun isNoPersonalizedLearning(imeOptions: Int): Boolean =
        (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

    fun isPasswordField(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD

            InputType.TYPE_CLASS_NUMBER ->
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD

            else -> false
        }
    }
}
