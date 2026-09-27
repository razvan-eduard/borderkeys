// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

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
}
