// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardThemeSanitisingTest {

    @Test
    fun `the opacity is whole by default and never below the floor`() {
        assertEquals(1f, KeyboardTheme().opacity, 0f)
        assertEquals(KeyboardTheme.MIN_OPACITY, KeyboardTheme(opacity = 0f).sanitised().opacity, 0f)
        assertEquals(1f, KeyboardTheme(opacity = 4f).sanitised().opacity, 0f)
        assertEquals(0.6f, KeyboardTheme(opacity = 0.6f).sanitised().opacity, 0f)
    }
}
