// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.text.InputType

/**
 * What shift should be, 0 off, 1 for one character or 2 locked, from a field's request and the
 * text before the cursor. `capsMode` is asked only for a text field that requested capitals and
 * has nothing composing; `forceCapitaliseSentences` reads sentence starts from the text in a
 * field that requested none.
 */
internal object AutoShift {
    fun stateFor(
        autoCapitaliseEnabled: Boolean,
        inputType: Int,
        composingIsEmpty: Boolean,
        forceCapitaliseSentences: Boolean = false,
        textBeforeCursor: () -> CharSequence? = { null },
        capsMode: () -> Int,
    ): Int {
        if (!autoCapitaliseEnabled) {
            return OFF
        }
        // Only a text field gets capitals.
        if ((inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
            return OFF
        }
        if ((inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0) {
            return LOCKED
        }
        val words = (inputType and InputType.TYPE_TEXT_FLAG_CAP_WORDS) != 0
        val sentences = (inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0
        if (!words && !sentences) {
            // Never forced in a password or an address field.
            if (!forceCapitaliseSentences || isPasswordVariation(inputType) || AddressField.isAddress(inputType)) {
                return OFF
            }
            // Forced: a sentence start is read from the text, the start of the field included.
            if (!composingIsEmpty) {
                return OFF
            }
            val before = textBeforeCursor()
            return if (before.isNullOrEmpty() || sentenceEndsBeforeCursor(before)) ON else OFF
        }
        // No capital inside a word being composed.
        if (!composingIsEmpty) {
            return OFF
        }
        // The platform's answer, else a sentence mark followed by emoji, emoticons and spaces.
        if (capsMode() != 0) {
            return ON
        }
        return if (sentenceEndsBeforeCursor(textBeforeCursor())) ON else OFF
    }

    /**
     * Whether the text before the cursor ends in a sentence mark followed by at least one space
     * and nothing but characters that are neither letters nor digits.
     */
    private fun sentenceEndsBeforeCursor(before: CharSequence?): Boolean {
        if (before.isNullOrEmpty()) {
            return false
        }
        var sawSpace = false
        var i = before.length
        while (i > 0) {
            val c = before[i - 1]
            when {
                c == '\n' -> return true
                c.isLetterOrDigit() -> return false
                c in SENTENCE_ENDINGS -> return sawSpace
                c.isWhitespace() -> sawSpace = true
            }
            i--
        }
        return false
    }

    /** The marks that close a sentence: ASCII, the single-character ellipsis, and the CJK set. */
    private const val SENTENCE_ENDINGS = ".!?…。！？"

    /** Whether [inputType] is one of the three password variations. */
    private fun isPasswordVariation(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    }

    private const val OFF = 0
    private const val ON = 1
    private const val LOCKED = 2
}
