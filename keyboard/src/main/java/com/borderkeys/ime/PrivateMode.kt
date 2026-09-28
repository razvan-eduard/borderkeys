// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Whether the field being typed into is one the keyboard must forget: a password field, each
 * variation checked within its own class, or one flagged `IME_FLAG_NO_PERSONALIZED_LEARNING`.
 * Private mode has no learning, no clipboard history, no personal suggestion and no assistant,
 * and no setting turns it off.
 */
object PrivateMode {

    fun isPrivate(info: EditorInfo?): Boolean {
        if (info == null) {
            return true // A field without EditorInfo is private.
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
