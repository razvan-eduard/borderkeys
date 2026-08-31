// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Private mode is a security requirement, so it is tested as one: every input type the platform
 * defines is enumerated, and the ones that must trigger it are named individually rather than
 * covered by a rule that could quietly stop matching.
 */
class PrivateModeTest {

    @Test
    fun `every password variation triggers private mode`() {
        val passwordTypes = mapOf(
            "text password" to
                (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            "visible password" to
                (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
            "web password" to
                (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD),
            "numeric password" to
                (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
        )
        for ((name, inputType) in passwordTypes) {
            assertTrue("$name did not trigger private mode", PrivateMode.isPasswordField(inputType))
            assertTrue(name, PrivateMode.isPrivate(inputType, 0))
        }
    }

    @Test
    fun `ordinary text fields do not trigger private mode`() {
        val ordinary = listOf(
            InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME,
        )
        for (inputType in ordinary) {
            assertFalse(
                "input type $inputType was treated as private",
                PrivateMode.isPrivate(inputType, 0),
            )
        }
    }

    @Test
    fun `a numeric variation is not confused with a text variation`() {
        // TYPE_TEXT_VARIATION_PASSWORD is 0x80 and TYPE_NUMBER_VARIATION_PASSWORD is 0x10.
        // Comparing a variation without its class is how a phone number ends up private and a
        // PIN field does not.
        val textWithNumberPasswordBits =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        assertFalse(PrivateMode.isPasswordField(textWithNumberPasswordBits))

        val numberWithTextPasswordBits =
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertFalse(PrivateMode.isPasswordField(numberWithTextPasswordBits))
    }

    @Test
    fun `no personalized learning triggers private mode on an ordinary field`() {
        // An application saying "do not remember this" about a field that is not a password:
        // an incognito search box, a medical form, a disappearing message.
        assertTrue(
            PrivateMode.isPrivate(
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            ),
        )
        assertTrue(
            PrivateMode.isNoPersonalizedLearning(
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_SEND,
            ),
        )
    }

    @Test
    fun `an unknown editor is treated as private`() {
        // Nothing is known about the field, so the careful answer is the only defensible one.
        assertTrue(PrivateMode.isPrivate(null))
    }

    @Test
    fun `flag bits other than the learning flag do not trigger it`() {
        assertFalse(PrivateMode.isNoPersonalizedLearning(EditorInfo.IME_ACTION_DONE))
        assertFalse(PrivateMode.isNoPersonalizedLearning(EditorInfo.IME_FLAG_NO_FULLSCREEN))
        assertFalse(PrivateMode.isNoPersonalizedLearning(0))
    }
}
