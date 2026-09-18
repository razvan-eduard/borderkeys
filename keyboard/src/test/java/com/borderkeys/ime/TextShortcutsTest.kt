// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.theme.TextShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextShortcutsTest {

    private val shortcuts = listOf(
        TextShortcut("omw", "on my way"),
        TextShortcut("addr", "12 Strada Lungă, București"),
    )

    @Test
    fun `a trigger expands whatever its case, and the case carries over`() {
        assertEquals("on my way", TextShortcuts.expansionFor("omw", shortcuts))
        assertEquals("On my way", TextShortcuts.expansionFor("Omw", shortcuts))
        assertEquals("ON MY WAY", TextShortcuts.expansionFor("OMW", shortcuts))
        assertEquals("12 Strada Lungă, București", TextShortcuts.expansionFor("addr", shortcuts))
    }

    @Test
    fun `anything else is left alone`() {
        assertNull(TextShortcuts.expansionFor("omwx", shortcuts))
        assertNull(TextShortcuts.expansionFor("", shortcuts))
        assertNull(TextShortcuts.expansionFor("omw", emptyList()))
    }
}
