// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.text.InputType

/**
 * Whether the field being typed into holds an address: an e-mail address, in either of
 * Android's two spellings of it, or a URI. An address has no words and no sentences, so nothing
 * the keyboard adds after one -- the space after a mark, the space after a picked suggestion,
 * the full stop two spaces make -- belongs in it.
 *
 * Pure, so the test suite can enumerate the input types the platform defines.
 */
object AddressField {

    fun isAddress(inputType: Int): Boolean {
        // The variation bits only mean this inside the text class: a number or a phone field
        // can carry the same bit values for reasons of its own.
        if ((inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
            return false
        }
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_URI
    }
}
