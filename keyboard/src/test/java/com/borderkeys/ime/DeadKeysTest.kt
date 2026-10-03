// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeadKeysTest {

    @Test
    fun `every pair gives one code point in the basic plane, or nothing`() {
        var composed = 0
        for (dead in DeadKeys.CODES) {
            for (base in ('a'..'z') + ('A'..'Z')) {
                val result = DeadKeys.combine(base.code, dead) ?: continue
                composed++
                assertTrue("$base + $dead", Character.isBmpCodePoint(result))
                assertTrue("$base + $dead", Character.isLetter(result))
                assertTrue("$base + $dead gave $base back", result != base.code)
            }
        }
        assertTrue("only $composed pairs compose", composed > 200)
    }

    @Test
    fun `the common accents land where a typist expects`() {
        assertEquals('é'.code, DeadKeys.combine('e'.code, KeyCodes.DEAD_ACUTE))
        assertEquals('Á'.code, DeadKeys.combine('A'.code, KeyCodes.DEAD_ACUTE))
        assertEquals('ñ'.code, DeadKeys.combine('n'.code, KeyCodes.DEAD_TILDE))
        assertEquals('ș'.code, DeadKeys.combine('s'.code, KeyCodes.DEAD_COMMA_BELOW))
        assertEquals('ş'.code, DeadKeys.combine('s'.code, KeyCodes.DEAD_CEDILLA))
        assertEquals('ă'.code, DeadKeys.combine('a'.code, KeyCodes.DEAD_BREVE))
        assertEquals('ů'.code, DeadKeys.combine('u'.code, KeyCodes.DEAD_RING))
        assertEquals('ą'.code, DeadKeys.combine('a'.code, KeyCodes.DEAD_OGONEK))
        assertEquals('ļ'.code, DeadKeys.combine('l'.code, KeyCodes.DEAD_COMMA_BELOW))
        assertEquals('Ķ'.code, DeadKeys.combine('K'.code, KeyCodes.DEAD_COMMA_BELOW))
    }

    @Test
    fun `a character that takes no mark gives nothing`() {
        assertNull(DeadKeys.combine('q'.code, KeyCodes.DEAD_ACUTE))
        assertNull(DeadKeys.combine('1'.code, KeyCodes.DEAD_ACUTE))
        assertNull(DeadKeys.combine('e'.code, KeyCodes.SHIFT))
    }

    @Test
    fun `every dead key has a spacing form and a cap`() {
        assertEquals(12, DeadKeys.CODES.size)
        for (dead in DeadKeys.CODES) {
            assertTrue(DeadKeys.accent(dead)!!.spacing.isNotEmpty())
            assertTrue(DeadKeys.cap(dead)!!.startsWith("◌"))
            assertEquals(dead, KeyCodes.named(DeadKeys.accent(dead)!!.name))
        }
    }

    @Test
    fun `the compose table spells its entries and no entry starts another`() {
        val table = ComposeSequences.parse(File("src/main/assets/compose/latin.json").readText())
        assertTrue(table.size > 400)
        assertTrue(table.step("'e") is ComposeSequences.Step.Done)
        assertEquals("é", (table.step("'e") as ComposeSequences.Step.Done).text)
        assertEquals("©", (table.step("oc") as ComposeSequences.Step.Done).text)
        assertEquals("—", (table.step("---") as ComposeSequences.Step.Done).text)
        assertTrue(table.step("--") is ComposeSequences.Step.More)
        assertTrue(table.step("'") is ComposeSequences.Step.More)
        assertTrue(table.step("'q") is ComposeSequences.Step.NoMatch)
        assertTrue(ComposeSequences.parse("{ not json").size == 0)
    }
}
