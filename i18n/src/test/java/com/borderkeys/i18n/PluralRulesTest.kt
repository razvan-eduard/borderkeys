// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

/** CLDR's plural categories, checked against its own example numbers. */
class PluralRulesTest {

    private fun assertCategory(language: String, category: String, vararg numbers: Long) {
        for (n in numbers) {
            assertEquals("$language $n", category, PluralRules.category(language, n))
        }
    }

    @Test
    fun `english, german, spanish, italian and the rest of one and other`() {
        for (language in listOf("en", "de", "es", "it", "nl", "hu", "tr")) {
            assertCategory(language, PluralRules.ONE, 1)
            assertCategory(language, PluralRules.OTHER, 0, 2, 11, 21, 100)
        }
    }

    @Test
    fun `french and portuguese count zero as one`() {
        assertCategory("fr", PluralRules.ONE, 0, 1)
        assertCategory("fr", PluralRules.OTHER, 2, 10, 100)
        assertCategory("pt", PluralRules.ONE, 0, 1)
    }

    @Test
    fun `romanian takes the few form below twenty and the de form from twenty on`() {
        assertCategory("ro", PluralRules.ONE, 1)
        assertCategory("ro", PluralRules.FEW, 0, 2, 16, 19, 101, 119)
        assertCategory("ro", PluralRules.OTHER, 20, 35, 100, 120, 1000)
    }

    @Test
    fun `czech counts one, two to four, and the rest`() {
        assertCategory("cs", PluralRules.ONE, 1)
        assertCategory("cs", PluralRules.FEW, 2, 3, 4)
        assertCategory("cs", PluralRules.OTHER, 0, 5, 19, 100)
    }

    @Test
    fun `polish, russian and ukrainian follow the last digits`() {
        assertCategory("pl", PluralRules.ONE, 1)
        assertCategory("pl", PluralRules.FEW, 2, 3, 4, 22, 24, 32)
        assertCategory("pl", PluralRules.MANY, 0, 5, 11, 12, 14, 21, 25, 112)
        for (language in listOf("ru", "uk")) {
            assertCategory(language, PluralRules.ONE, 1, 21, 31, 101)
            assertCategory(language, PluralRules.FEW, 2, 4, 22, 24, 102)
            assertCategory(language, PluralRules.MANY, 0, 5, 11, 12, 14, 20, 111, 114)
        }
    }

    @Test
    fun `latvian has a zero form for tens and the teens`() {
        assertCategory("lv", PluralRules.ZERO, 0, 10, 11, 19, 20, 30, 100)
        assertCategory("lv", PluralRules.ONE, 1, 21, 31, 101)
        assertCategory("lv", PluralRules.OTHER, 2, 9, 22, 29, 102)
    }

    @Test
    fun `filipino and the languages without plurals`() {
        assertCategory("fil", PluralRules.ONE, 1, 2, 3, 5, 7, 8, 10)
        assertCategory("fil", PluralRules.OTHER, 4, 6, 9, 14, 19)
        for (language in listOf("ja", "ko", "zh-cn", "vi", "id")) {
            assertCategory(language, PluralRules.OTHER, 0, 1, 2, 100)
        }
    }

    @Test
    fun `persian counts zero and one alike`() {
        assertCategory("fa", PluralRules.ONE, 0, 1)
        assertCategory("fa", PluralRules.OTHER, 2, 10)
    }

    @Test
    fun `every category a language returns is one it declares`() {
        val languages = listOf(
            "en", "de", "es", "fr", "it", "ro", "cs", "pl", "ru", "uk", "lv", "fil", "ja", "ko", "zh-cn", "vi",
            "id", "fa", "hu", "nl", "pt", "tr",
        )
        for (language in languages) {
            val declared = PluralRules.categories(language)
            for (n in 0L..250L) {
                val category = PluralRules.category(language, n)
                assertEquals("$language $n gives $category, not declared in $declared", true, category in declared)
            }
            for (shown in listOf("0.0", "0.5", "1.0", "1.5", "2.0", "2.5", "11.1", "0.01", "0.11")) {
                val category = PluralRules.category(language, shown)
                assertEquals("$language $shown gives $category, not declared in $declared", true, category in declared)
            }
        }
    }

    private fun assertShown(language: String, category: String, vararg shown: String) {
        for (number in shown) {
            assertEquals("$language $number", category, PluralRules.category(language, number))
        }
    }

    @Test
    fun `a number shown with digits after the point follows them`() {
        assertShown("en", PluralRules.OTHER, "1.0", "1.5", "0.5")
        assertShown("en", PluralRules.ONE, "1")
        assertShown("de", PluralRules.OTHER, "1,0", "1,5")
        assertShown("fr", PluralRules.ONE, "0.5", "1.0", "1.5")
        assertShown("fr", PluralRules.OTHER, "2.0", "2.5")
        assertShown("ro", PluralRules.FEW, "1.0", "1.5", "20.5")
        assertShown("cs", PluralRules.MANY, "1.5", "2.0")
        assertShown("ru", PluralRules.OTHER, "1.5", "2.0")
        assertShown("pl", PluralRules.OTHER, "1.5")
        assertShown("lv", PluralRules.ZERO, "10.0", "0.0")
        assertShown("lv", PluralRules.ONE, "0.1", "2.1")
        assertShown("lv", PluralRules.OTHER, "0.5")
        assertShown("fil", PluralRules.ONE, "0.5", "1.5")
        assertShown("fil", PluralRules.OTHER, "0.4", "1.6")
        assertShown("fa", PluralRules.ONE, "0.5")
        assertShown("fa", PluralRules.OTHER, "1.5")
    }

    @Test
    fun `the categories each language declares`() {
        assertEquals(listOf("one", "few", "other"), PluralRules.categories("ro"))
        assertEquals(listOf("one", "few", "many", "other"), PluralRules.categories("cs"))
        assertEquals(listOf("other"), PluralRules.categories("zh-cn"))
        assertEquals(listOf("one", "other"), PluralRules.categories("en"))
    }
}
