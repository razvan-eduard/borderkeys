// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalCorpusLanguagesTest {

    @Test
    fun `no languages named reads every corpus`() {
        assertEquals(listOf("en", "ro", "es"), PersonalCorpusTest.languagesNamed(null).map { it.second })
        assertEquals(listOf("en", "ro", "es"), PersonalCorpusTest.languagesNamed(" ").map { it.second })
    }

    @Test
    fun `the languages named are read in the corpora's order`() {
        assertEquals(listOf("en-US" to "en"), PersonalCorpusTest.languagesNamed("en"))
        assertEquals(listOf("en", "es"), PersonalCorpusTest.languagesNamed("es, en").map { it.second })
    }

    @Test
    fun `a language with no corpus is refused by name`() {
        val refused = assertThrows(IllegalArgumentException::class.java) {
            PersonalCorpusTest.languagesNamed("en,de")
        }
        assertTrue(refused.message.orEmpty(), refused.message.orEmpty().contains("de"))
    }
}
