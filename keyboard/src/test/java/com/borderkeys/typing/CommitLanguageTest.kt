// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import org.junit.Assert.assertEquals
import org.junit.Test

class CommitLanguageTest {

    @Test
    fun `the pack the answer leaned on names the language, not the subtype`() {
        assertEquals("ro", CommitLanguage.decide("ro-RO", "en-US"))
    }

    @Test
    fun `with no pack named, the subtype names it`() {
        assertEquals("en", CommitLanguage.decide(null, "en-US"))
        assertEquals("en", CommitLanguage.decide("", "en-US"))
    }
}
