// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldPolicyTest {

    @Test
    fun `a password field allows no dictionary words and nothing personal, and takes the keys verbatim`() {
        val policy = FieldPolicy.of(passwordField = true, privateField = true, learningEnabled = true)
        assertFalse(policy.suggestionsAllowed)
        assertTrue(policy.privateField)
        assertFalse(policy.personalAllowed)
        assertTrue(policy.verbatim)
    }

    @Test
    fun `a private field that is not a password field still allows dictionary words`() {
        val policy = FieldPolicy.of(passwordField = false, privateField = true, learningEnabled = true)
        assertTrue(policy.suggestionsAllowed)
        assertFalse(policy.personalAllowed)
        assertFalse(policy.verbatim)
    }

    @Test
    fun `the personal dictionary needs the Learning switch`() {
        val off = FieldPolicy.of(passwordField = false, privateField = false, learningEnabled = false)
        assertFalse(off.personalAllowed)
        assertTrue(off.withLearning(true).personalAllowed)
        assertFalse(off.withLearning(true).withLearning(false).personalAllowed)
    }

    @Test
    fun `switching Learning on does not open a private field`() {
        val private = FieldPolicy.of(passwordField = false, privateField = true, learningEnabled = false)
        assertFalse(private.withLearning(true).personalAllowed)
    }
}
