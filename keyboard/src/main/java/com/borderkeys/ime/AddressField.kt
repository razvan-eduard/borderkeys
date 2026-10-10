// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.text.InputType

/**
 * Whether the field being typed into holds an address: an e-mail address, in either of Android's
 * variations, or a URI. The keyboard adds no automatic spaces or full stops in one. An e-mail,
 * number or phone field is also [isVerbatim]: the keys go in as typed and no swipe is read, while
 * dictionary words are still offered. A URI field is not, because browsers search from it.
 */
object AddressField {

    fun isVerbatim(inputType: Int): Boolean {
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE -> true
            InputType.TYPE_CLASS_TEXT -> {
                val variation = inputType and InputType.TYPE_MASK_VARIATION
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            }
            else -> false
        }
    }

    fun isAddress(inputType: Int): Boolean {
        // The variation counts only inside the text class.
        if ((inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
            return false
        }
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_URI
    }
}
