// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.theme.KeyboardPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageNamesTest {

    @Test
    fun `every known package has the shape of a package name`() {
        for (packageName in ClipboardExclusions.KNOWN) {
            assertTrue(packageName, PackageNames.isPackageName(packageName))
        }
        assertFalse(PackageNames.isPackageName("vault"))
        assertFalse(PackageNames.isPackageName("com.example."))
        assertFalse(PackageNames.isPackageName("com.1example.vault"))
        assertFalse(PackageNames.isPackageName("com example"))
    }

    @Test
    fun `the stored list is trimmed, shaped, each entry once and bounded`() {
        val sanitised = PackageNames.sanitised(
            listOf(" com.example.vault ", "vault", "com.example.vault", "org.example.otp"),
        )
        assertEquals(listOf("com.example.vault", "org.example.otp"), sanitised)
        val many = (0 until PackageNames.MAX_ADDED + 5).map { "com.example.app$it" }
        assertEquals(PackageNames.MAX_ADDED, PackageNames.sanitised(many).size)
    }

    @Test
    fun `the preferences carry both lists through the same sanitising`() {
        val preferences = KeyboardPreferences(
            clipboardExcludedPackages = listOf("com.example.vault", "nonsense"),
            terminalPackages = listOf(" com.example.shell ", "shell", "com.example.shell"),
        ).sanitised()
        assertEquals(listOf("com.example.vault"), preferences.clipboardExcludedPackages)
        assertEquals(listOf("com.example.shell"), preferences.terminalPackages)
    }
}
