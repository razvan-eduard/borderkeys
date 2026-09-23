// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PunctuationSpaceTest {

    private fun follows(
        before: Char?,
        mark: Char = '.',
        after: Char? = null,
        enabled: Boolean = true,
        insideNumbers: Boolean = false,
    ) =
        PunctuationSpace.follows(
            enabled = enabled,
            insideNumbers = insideNumbers,
            tightPunctuation = mark in ".,!?;:",
            before = { before },
            after = { after },
        )

    @Test
    fun `a mark after a digit stays inside the number`() {
        assertFalse("12.55 must not become 12. 55", follows('2', '.'))
        assertFalse("12,55 is how Romanian writes a decimal", follows('2', ','))
        assertFalse("10:30 is a time", follows('0', ':'))
        assertFalse("1.500 is a thousands separator", follows('1', '.'))
    }

    @Test
    fun `a mark after a letter still ends a sentence`() {
        assertTrue(follows('i', '.'))
        assertTrue(follows('ă', '.'))
        assertTrue(follows('t', ','))
        assertTrue(follows('e', '?'))
    }

    @Test
    fun `the edge of a field is not a digit`() {
        assertTrue("nothing in front is not a number", follows(null, '.'))
    }

    @Test
    fun `the toggle puts the space back inside numbers`() {
        assertTrue(follows('2', '.', insideNumbers = true))
        assertTrue(follows('0', ':', insideNumbers = true))
        assertFalse("the setting above still governs", 
                    follows('2', '.', enabled = false, insideNumbers = true))
    }

    @Test
    fun `the rules that were already there still hold`() {
        assertFalse("the setting is off", follows('i', '.', enabled = false))
        assertFalse("not a mark this rule spaces", follows('i', '-'))
        assertFalse("a space is already there", follows('i', '.', after = ' '))
        assertTrue("something other than a space follows", follows('i', '.', after = 'X'))
    }
}
