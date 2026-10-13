// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.language

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageTagsTest {

    @Test
    fun `a tag's primary subtag comes before either separator, in lower case`() {
        assertEquals("ro", LanguageTags.primary("ro-RO"))
        assertEquals("en", LanguageTags.primary("en_US"))
        assertEquals("pt", LanguageTags.primary("PT-br"))
        assertEquals("fr", LanguageTags.primary("fr"))
    }

    @Test
    fun `a tag naming no language is unknown`() {
        assertEquals(LanguageTags.UNKNOWN, LanguageTags.primary(""))
        assertEquals(LanguageTags.UNKNOWN, LanguageTags.primary(null))
        assertEquals(LanguageTags.UNKNOWN, LanguageTags.primary("-RO"))
    }
}
