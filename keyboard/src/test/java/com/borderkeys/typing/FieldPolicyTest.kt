// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldPolicyTest {

    private fun policy(
        password: Boolean = false,
        private: Boolean = false,
        learning: Boolean = true,
        heatmap: Boolean = true,
    ) = FieldPolicy.of(
        passwordField = password,
        privateField = private,
        learningEnabled = learning,
        heatmapEnabled = heatmap,
    )

    @Test
    fun `a password field allows no dictionary words and nothing personal, and takes the keys verbatim`() {
        val policy = policy(password = true, private = true)
        assertFalse(policy.suggestionsAllowed)
        assertTrue(policy.privateField)
        assertFalse(policy.personalAllowed)
        assertTrue(policy.verbatim)
    }

    @Test
    fun `a private field that is not a password field still allows dictionary words`() {
        val policy = policy(private = true)
        assertTrue(policy.suggestionsAllowed)
        assertFalse(policy.personalAllowed)
        assertFalse(policy.verbatim)
    }

    @Test
    fun `the personal dictionary needs the Learning switch`() {
        val off = policy(learning = false)
        assertFalse(off.personalAllowed)
        assertTrue(off.withSwitches(true, true).personalAllowed)
        assertFalse(off.withSwitches(true, true).withSwitches(false, true).personalAllowed)
    }

    @Test
    fun `the heatmap needs Learning and its own switch`() {
        val both = policy()
        assertTrue(both.heatmapAllowed)
        assertFalse(both.withSwitches(learning = false, heatmap = true).heatmapAllowed)
        assertFalse(both.withSwitches(learning = true, heatmap = false).heatmapAllowed)
        assertTrue(both.withSwitches(learning = true, heatmap = false).personalAllowed)
    }

    @Test
    fun `a private field never records the heatmap`() {
        val private = policy(private = true)
        assertFalse(private.heatmapAllowed)
        assertFalse(private.withSwitches(learning = true, heatmap = true).heatmapAllowed)
    }

    @Test
    fun `switching Learning on does not open a private field`() {
        val private = policy(private = true, learning = false)
        assertFalse(private.withSwitches(true, true).personalAllowed)
    }

    @Test
    fun `before the first unlock nothing personal is allowed, and the switches do not open it`() {
        val locked = FieldPolicy.of(
            passwordField = false, privateField = false, learningEnabled = true, heatmapEnabled = true,
            userUnlocked = false,
        )
        assertTrue(locked.suggestionsAllowed)
        assertFalse(locked.personalAllowed)
        assertFalse(locked.heatmapAllowed)
        assertFalse(locked.withSwitches(learning = true, heatmap = true).personalAllowed)
    }

    @Test
    fun `the unlock opens what the switches allow, and no more`() {
        val locked = FieldPolicy.of(
            passwordField = false, privateField = false, learningEnabled = true, heatmapEnabled = true,
            userUnlocked = false,
        )
        assertTrue(locked.unlocked(learning = true, heatmap = true).personalAllowed)
        assertTrue(locked.unlocked(learning = true, heatmap = true).heatmapAllowed)
        assertFalse(locked.unlocked(learning = false, heatmap = true).personalAllowed)
        assertFalse(locked.unlocked(learning = true, heatmap = false).heatmapAllowed)
        val private = FieldPolicy.of(
            passwordField = false, privateField = true, learningEnabled = true, heatmapEnabled = true,
            userUnlocked = false,
        )
        assertFalse(private.unlocked(learning = true, heatmap = true).personalAllowed)
    }
}
