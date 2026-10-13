// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.text.InputType

/**
 * Whether the field being typed into holds an address: an e-mail address, in either of Android's
 * variations, or a URI. The keyboard adds no automatic spaces or full stops in one. An e-mail
 * field ([isEmail]) and a number or phone field ([isNumberOrPhone]) take the keys as typed, while
 * dictionary words and swipe still work. A URI field is not.
 */
object AddressField {

    /** An e-mail address field, in either of Android's variations. */
    fun isEmail(inputType: Int): Boolean {
        val variation = textVariation(inputType)
        return variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
    }

    /** A number or a phone field. */
    fun isNumberOrPhone(inputType: Int): Boolean = when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE -> true
        else -> false
    }

    fun isAddress(inputType: Int): Boolean =
        isEmail(inputType) || textVariation(inputType) == InputType.TYPE_TEXT_VARIATION_URI

    /** The field's variation within the text class; null in another class. */
    private fun textVariation(inputType: Int): Int? =
        if ((inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT) {
            inputType and InputType.TYPE_MASK_VARIATION
        } else {
            null
        }
}
