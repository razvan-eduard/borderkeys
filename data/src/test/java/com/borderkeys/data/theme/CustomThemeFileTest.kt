// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomThemeFileTest {

    @Test
    fun `a theme survives a round trip through its own file format`() {
        val theme = KeyboardTheme(accentColor = 0xFF778899.toInt(), keyCornerRadiusDp = 16f)
        val text = CustomThemeFile.write("Slate Two", theme)
        val (name, restored) = requireNotNull(CustomThemeFile.read(text))
        assertEquals("Slate Two", name)
        assertEquals(theme, restored)
    }

    @Test
    fun `the file is meant to be readable by a person`() {
        val text = CustomThemeFile.write("Readable", KeyboardTheme())
        assertTrue("expected a pretty-printed file", text.contains("\n"))
    }

    @Test
    fun `garbage is refused rather than half-parsed`() {
        assertNull(CustomThemeFile.read("not a theme file"))
        assertNull(CustomThemeFile.read("{}"))
    }

    @Test
    fun `an out-of-range dimension in an imported file is still clamped`() {
        val text = """{"name":"Bad","theme":{"rowHeightDp":40000.0}}"""
        val (_, theme) = requireNotNull(CustomThemeFile.read(text))
        assertTrue(theme.rowHeightDp in 28f..96f)
    }
}
