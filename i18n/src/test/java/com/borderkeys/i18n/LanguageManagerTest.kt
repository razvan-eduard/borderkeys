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

    @Test
    fun `non-string entries are dropped`() {
        assertEquals(mapOf("a" to "one"), LanguageManager.parse("""{"a": "one", "b": 2}"""))
    }

    @Test
    fun `placeholders are filled left to right`() {
        assertEquals("1 of 2", LanguageManager.format("%s of %s", "1", "2"))
    }

    @Test
    fun `a stray percent survives`() {
        assertEquals("100% of 2", LanguageManager.format("100% of %s", "2"))
    }

    @Test
    fun `an unfilled placeholder is left alone`() {
        assertEquals("%s of %s", LanguageManager.format("%s of %s"))
        assertEquals("1 of %s", LanguageManager.format("%s of %s", "1"))
    }

    @Test
    fun `a counted key takes its language's form, the base key for other`() {
        assertEquals("k_one", PluralRules.keyFor("k", "en", 1))
        assertEquals("k", PluralRules.keyFor("k", "en", 0))
        assertEquals("k_one", PluralRules.keyFor("k", "fr", 0))
        assertEquals("k", PluralRules.keyFor("k", "ja", 1))
    }

    private val romanian = mapOf(
        "words" to "%s de cuvinte", "words_one" to "%s cuvânt", "words_few" to "%s cuvinte",
        "added" to "%s de cuvinte pentru %s", "added_one" to "%s cuvânt pentru %s",
        "added_few" to "%s cuvinte pentru %s",
    )

    private fun counted(key: String, count: Long, vararg arguments: Any?): String =
        LanguageManager.form(romanian, key, PluralRules.category("ro", count), count.toString(), arguments)

    @Test
    fun `a count takes the form its number calls for`() {
        assertEquals("1 cuvânt", counted("words", 1))
        assertEquals("3 cuvinte", counted("words", 3))
        assertEquals("20 de cuvinte", counted("words", 20))
        assertEquals("101 cuvinte", counted("words", 101))
    }

    @Test
    fun `a counted line takes its own arguments when it has them`() {
        assertEquals("1 cuvânt pentru ro", counted("added", 1, 1, "ro"))
        assertEquals("25 de cuvinte pentru ro", counted("added", 25, 25, "ro"))
    }

    @Test
    fun `a missing form falls back to the base key, and a missing key to itself`() {
        val sparse = mapOf("words" to "%s de cuvinte")
        assertEquals("1 de cuvinte", LanguageManager.form(sparse, "words", PluralRules.ONE, "1", emptyArray()))
        assertEquals("gone", LanguageManager.form(sparse, "gone", PluralRules.ONE, "1", emptyArray()))
    }

    @Test
    fun `a decimal takes the form its digits call for`() {
        val english = mapOf("letters" to "%s letters", "letters_one" to "%s letter")
        fun shown(number: String) =
            LanguageManager.form(english, "letters", PluralRules.category("en", number), number, emptyArray())
        assertEquals("1.0 letters", shown("1.0"))
        assertEquals("1 letter", shown("1"))
    }
}
