// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

/** The parity rule on catalogues written in the test. */
class TranslationParityTest {

    private val english = setOf("title", "items", "items_one", "the_one")

    @Test
    fun `a counted key is one english carries with its base and a one form`() {
        assertEquals(setOf("items"), TranslationParity.countedKeys(english))
    }

    @Test
    fun `each language carries the forms its plural rules use`() {
        assertEquals(setOf("title", "items", "items_one", "the_one"), TranslationParity.expectedKeys(english, "de"))
        assertEquals(
            setOf("title", "items", "items_one", "items_few", "the_one"),
            TranslationParity.expectedKeys(english, "ro"),
        )
        assertEquals(setOf("title", "items", "the_one"), TranslationParity.expectedKeys(english, "ja"))
        assertEquals(
            setOf("title", "items", "items_one", "items_few", "items_many", "the_one"),
            TranslationParity.expectedKeys(english, "ru"),
        )
    }

    @Test
    fun `a missing form and a form the language does not use are both reported`() {
        val problems = TranslationParity.problems(
            mapOf(
                "en" to english,
                "ro" to setOf("title", "items", "items_one", "the_one"),
                "ja" to setOf("title", "items", "items_one", "the_one"),
                "de" to setOf("title", "items", "items_one", "the_one"),
            ),
        )
        assertEquals(
            listOf("ro is missing 'items_few'", "ja has 'items_one' which it should not"),
            problems,
        )
    }
}
