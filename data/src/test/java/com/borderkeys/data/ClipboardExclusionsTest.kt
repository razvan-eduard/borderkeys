// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.theme.KeyboardPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardExclusionsTest {

    @Test
    fun `a known password manager is excluded without anything added`() {
        assertTrue(ClipboardExclusions.isExcluded("com.x8bit.bitwarden", emptyList()))
        assertTrue(ClipboardExclusions.isExcluded("com.beemdevelopment.aegis", emptyList()))
        assertFalse(ClipboardExclusions.isExcluded("com.example.editor", emptyList()))
    }

    @Test
    fun `an added package is excluded too, exactly as written`() {
        val added = listOf("com.example.vault")
        assertTrue(ClipboardExclusions.isExcluded("com.example.vault", added))
        assertFalse(ClipboardExclusions.isExcluded("com.example.vault.free", added))
        assertFalse(ClipboardExclusions.isExcluded("com.example.Vault", added))
    }

    @Test
    fun `no package means no exclusion`() {
        assertFalse(ClipboardExclusions.isExcluded(null, listOf("com.example.vault")))
    }

    @Test
    fun `every known package has the shape of a package name`() {
        for (packageName in ClipboardExclusions.KNOWN) {
            assertTrue(packageName, ClipboardExclusions.isPackageName(packageName))
        }
        assertFalse(ClipboardExclusions.isPackageName("vault"))
        assertFalse(ClipboardExclusions.isPackageName("com.example."))
        assertFalse(ClipboardExclusions.isPackageName("com.1example.vault"))
        assertFalse(ClipboardExclusions.isPackageName("com example"))
    }

    @Test
    fun `the stored list is trimmed, shaped, each entry once and bounded`() {
        val sanitised = ClipboardExclusions.sanitised(
            listOf(" com.example.vault ", "vault", "com.example.vault", "org.example.otp"),
        )
        assertEquals(listOf("com.example.vault", "org.example.otp"), sanitised)
        val many = (0 until ClipboardExclusions.MAX_ADDED + 5).map { "com.example.app$it" }
        assertEquals(ClipboardExclusions.MAX_ADDED, ClipboardExclusions.sanitised(many).size)
    }

    @Test
    fun `the preferences carry the list through the same sanitising`() {
        val preferences = KeyboardPreferences(clipboardExcludedPackages = listOf("com.example.vault", "nonsense"))
        assertEquals(listOf("com.example.vault"), preferences.sanitised().clipboardExcludedPackages)
    }
}
