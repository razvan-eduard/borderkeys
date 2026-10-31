// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.personal

import com.borderkeys.data.personal.PersonalSpelling.Form
import org.junit.Assert.assertEquals
import org.junit.Test

/** One word's case forms, merged into the entry the engine is handed and the pages list. */
class PersonalSpellingTest {

    @Test
    fun `a word's case forms count together`() {
        val merged = PersonalSpelling.merge(
            "paris",
            listOf(
                Form("paris", count = 3, lastUsedAt = 10L, deliberateCapitals = 1, asserted = 1, added = 1),
                Form("Paris", count = 2, lastUsedAt = 20L, deliberateCapitals = 2, asserted = 2, added = 1),
            ),
        )
        assertEquals("paris", merged.key)
        assertEquals(5, merged.count)
        assertEquals(3, merged.deliberateCapitals)
        assertEquals(3, merged.asserted)
        assertEquals(2, merged.added)
    }

    @Test
    fun `the spelling served is the one added by hand`() {
        val merged = PersonalSpelling.merge(
            "paris",
            listOf(Form("paris", count = 9, lastUsedAt = 30L), Form("Paris", count = 1, lastUsedAt = 10L, added = 1)),
        )
        assertEquals("Paris", merged.spelling)
    }

    @Test
    fun `with none added by hand, the most used`() {
        val merged = PersonalSpelling.merge(
            "paris",
            listOf(Form("Paris", count = 2, lastUsedAt = 30L), Form("paris", count = 5, lastUsedAt = 10L)),
        )
        assertEquals("paris", merged.spelling)
    }

    @Test
    fun `among forms used as often, the last used`() {
        val merged = PersonalSpelling.merge(
            "paris",
            listOf(Form("paris", count = 4, lastUsedAt = 10L), Form("Paris", count = 4, lastUsedAt = 30L)),
        )
        assertEquals("Paris", merged.spelling)
    }
}
