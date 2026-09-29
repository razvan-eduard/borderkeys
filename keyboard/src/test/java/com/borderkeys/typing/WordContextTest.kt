// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import org.junit.Assert.assertEquals
import org.junit.Test

class WordContextTest {

    @Test
    fun `a written word becomes the nearest and pushes the nearest one back`() {
        val context = WordContext.NONE.then("hello").then("there")
        assertEquals(WordContext("there", "hello"), context)
    }

    @Test
    fun `only two words are kept`() {
        val context = WordContext.NONE.then("one").then("two").then("three")
        assertEquals(WordContext("three", "two"), context)
    }
}
