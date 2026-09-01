// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

/** The lookup and substitution rules, on catalogues written in the test rather than shipped. */
class LanguageManagerTest {

    @Test
    fun `a flat object becomes a map`() {
        val parsed = LanguageManager.parse("""{"a": "one", "b": "two"}""")
        assertEquals(mapOf("a" to "one", "b" to "two"), parsed)
    }

    /** An entry that is not text is a mistake in the file, and coercing it would hide it. */
    @Test
    fun `non-string entries are dropped`() {
        assertEquals(mapOf("a" to "one"), LanguageManager.parse("""{"a": "one", "b": 2}"""))
    }

    @Test
    fun `placeholders are filled left to right`() {
        assertEquals("1 of 2", LanguageManager.format("%s of %s", "1", "2"))
    }

    /** A translator's stray percent sign must show as a percent sign, not crash a screen. */
    @Test
    fun `a stray percent survives`() {
        assertEquals("100% of 2", LanguageManager.format("100% of %s", "2"))
    }

    /** Fewer arguments than placeholders leaves the rest standing, rather than throwing. */
    @Test
    fun `an unfilled placeholder is left alone`() {
        assertEquals("%s of %s", LanguageManager.format("%s of %s"))
        assertEquals("1 of %s", LanguageManager.format("%s of %s", "1"))
    }

    @Test
    fun `one takes the singular form in every language`() {
        assertEquals("k_one", LanguageManager.countedKey("k", 1, usesLargeNumberForm = true))
        assertEquals("k_one", LanguageManager.countedKey("k", 1, usesLargeNumberForm = false))
    }

    @Test
    fun `romanian counts in three`() {
        assertEquals("k_many", LanguageManager.countedKey("k", 0, usesLargeNumberForm = true))
        assertEquals("k", LanguageManager.countedKey("k", 5, usesLargeNumberForm = true))
        assertEquals("k_many", LanguageManager.countedKey("k", 20, usesLargeNumberForm = true))
    }

    @Test
    fun `languages without a large-number form never see it`() {
        assertEquals("k", LanguageManager.countedKey("k", 0, usesLargeNumberForm = false))
        assertEquals("k", LanguageManager.countedKey("k", 99, usesLargeNumberForm = false))
    }
}
