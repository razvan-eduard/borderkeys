// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressFieldTest {

    @Test
    fun `an e-mail field is an address, in both of the platform's spellings`() {
        assertTrue(AddressField.isAddress(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertTrue(AddressField.isAddress(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS))
    }

    @Test
    fun `a web address field is an address`() {
        assertTrue(AddressField.isAddress(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI))
    }

    @Test
    fun `the flags a field adds do not change the answer`() {
        val field = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        assertTrue(AddressField.isAddress(field))
    }

    @Test
    fun `ordinary text, a password and a person's name are not addresses`() {
        assertFalse(AddressField.isAddress(InputType.TYPE_CLASS_TEXT))
        assertFalse(AddressField.isAddress(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(AddressField.isAddress(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME))
        assertFalse(AddressField.isAddress(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT))
    }

    @Test
    fun `the same variation bits outside the text class mean something else`() {
        val emailBits = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        assertFalse(AddressField.isAddress(InputType.TYPE_CLASS_NUMBER or emailBits))
        assertFalse(AddressField.isAddress(InputType.TYPE_CLASS_PHONE or emailBits))
        assertFalse(AddressField.isAddress(InputType.TYPE_CLASS_DATETIME or emailBits))
    }
}
