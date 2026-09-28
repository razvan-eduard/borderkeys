// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LanguageSwitchCorrector]'s decisions, without an InputConnection or native calls. */
class LanguageSwitchCorrectorTest {

    private fun flag(typed: String, applied: String, start: Int, end: Int) =
        LanguageSwitchCorrector.Flag(typed, applied, start, end)

    @Test
    fun `no dominant pack yet is not a flip`() {
        val corrector = LanguageSwitchCorrector()
        assertFalse(corrector.observeDominantPack(-1))
    }

    @Test
    fun `the same dominant pack twice in a row is not a flip`() {
        val corrector = LanguageSwitchCorrector()
        assertTrue(corrector.observeDominantPack(0))
        assertFalse(corrector.observeDominantPack(0))
    }

    @Test
    fun `a real change from one pack to another is a flip`() {
        val corrector = LanguageSwitchCorrector()
        corrector.observeDominantPack(0)
        assertTrue(corrector.observeDominantPack(1))
    }

    @Test
    fun `losing dominance entirely is not itself a flip worth checking`() {
        val corrector = LanguageSwitchCorrector()
        corrector.observeDominantPack(0)
        assertFalse(corrector.observeDominantPack(-1))
    }

    @Test
    fun `snapshot returns everything tracked and clears it`() {
        val corrector = LanguageSwitchCorrector()
        corrector.recordCorrection(flag("salut", "salute", 0, 6))
        corrector.recordCorrection(flag("buna", "bună", 10, 14))
        val snapshot = corrector.snapshot()
        assertEquals(2, snapshot.size)
        assertTrue(corrector.snapshot().isEmpty())
    }

    @Test
    fun `more than the tracking bound drops the oldest, not the newest`() {
        val corrector = LanguageSwitchCorrector()
        for (i in 0 until 25) {
            corrector.recordCorrection(flag("w$i", "c$i", i, i + 1))
        }
        val snapshot = corrector.snapshot()
        assertEquals(20, snapshot.size)
        assertEquals("w5", snapshot.first().typedText)
        assertEquals("w24", snapshot.last().typedText)
    }

    @Test
    fun `resolve skips a word the new pack has nothing different to say about`() {
        val flags = listOf(flag("salut", "salut", 0, 5))
        val replacements = LanguageSwitchCorrector().resolve(flags, listOf(null))
        assertTrue(replacements.isEmpty())
    }

    @Test
    fun `resolve skips a word the new pack would spell the same way it already reads`() {
        val flags = listOf(flag("salut", "salut", 0, 5))
        val replacements = LanguageSwitchCorrector().resolve(flags, listOf("salut"))
        assertTrue(replacements.isEmpty())
    }

    @Test
    fun `resolve keeps a word the new pack genuinely disagrees with`() {
        val flags = listOf(flag("salut", "salut", 0, 5))
        val replacements = LanguageSwitchCorrector().resolve(flags, listOf("salute"))
        assertEquals(1, replacements.size)
        assertEquals(
            LanguageSwitchCorrector.Replacement(0, 5, "salut", "salute"),
            replacements[0],
        )
    }

    @Test
    fun `resolve gives the replacement the case of the word it replaces`() {
        val flags = listOf(flag("In", "În", 0, 2))
        val replacements = LanguageSwitchCorrector().resolve(flags, listOf("in"))
        assertEquals(1, replacements.size)
        assertEquals("In", replacements[0].text)
    }

    @Test
    fun `resolve leaves a mid-sentence word lower case`() {
        val flags = listOf(flag("in", "în", 10, 12))
        val replacements = LanguageSwitchCorrector().resolve(flags, listOf("in"))
        assertEquals("in", replacements.single().text)
    }

    @Test
    fun `resolve keeps a shout shouting`() {
        val flags = listOf(flag("SALUT", "SALUT", 0, 5))
        val replacements = LanguageSwitchCorrector().resolve(flags, listOf("salute"))
        assertEquals("SALUTE", replacements.single().text)
    }

    @Test
    fun `resolve skips a pack that disagrees only about case`() {
        val flags = listOf(flag("In", "In", 0, 2))
        val replacements = LanguageSwitchCorrector().resolve(flags, listOf("in"))
        assertTrue(replacements.isEmpty())
    }

    private fun replacement(start: Int, end: Int, previous: String, text: String) =
        LanguageSwitchCorrector.Replacement(start, end, previous, text)

    @Test
    fun `a same-length repair behind the caret leaves the caret where it was`() {
        val applied = listOf(replacement(0, 2, "În", "In"))
        assertEquals(30, LanguageSwitchCorrector().caretAfter(30, applied))
    }

    @Test
    fun `a repair behind the caret that grows moves the caret by what it gained`() {
        val applied = listOf(replacement(0, 5, "salut", "salute"))
        assertEquals(31, LanguageSwitchCorrector().caretAfter(30, applied))
    }

    @Test
    fun `several repairs behind the caret accumulate`() {
        // Right-to-left, as resolve orders them; each gains a letter.
        val applied = listOf(
            replacement(20, 25, "salut", "salute"),
            replacement(0, 5, "salut", "salute"),
        )
        assertEquals(32, LanguageSwitchCorrector().caretAfter(30, applied))
    }

    @Test
    fun `a diacritic-only repair moves nothing, even alongside one that does`() {
        val applied = listOf(
            replacement(20, 24, "bună", "buna"),
            replacement(0, 5, "salut", "salute"),
        )
        assertEquals(31, LanguageSwitchCorrector().caretAfter(30, applied))
    }

    @Test
    fun `a repair ahead of the caret does not move it`() {
        val applied = listOf(replacement(40, 45, "salut", "salute"))
        assertEquals(30, LanguageSwitchCorrector().caretAfter(30, applied))
    }

    @Test
    fun `a repair ending exactly at the caret still counts as behind it`() {
        val applied = listOf(replacement(25, 30, "salut", "salute"))
        assertEquals(31, LanguageSwitchCorrector().caretAfter(30, applied))
    }

    @Test
    fun `a caret inside a replaced word lands at the end of the new spelling`() {
        val applied = listOf(replacement(28, 33, "salut", "salute"))
        assertEquals(34, LanguageSwitchCorrector().caretAfter(30, applied))
    }

    @Test
    fun `a repair that shrinks pulls the caret back by what it lost`() {
        val applied = listOf(replacement(0, 6, "salute", "hi"))
        assertEquals(26, LanguageSwitchCorrector().caretAfter(30, applied))
    }

    @Test
    fun `resolve orders replacements right-to-left by offset`() {
        val flags = listOf(
            flag("salut", "salut", 0, 5),
            flag("buna", "bună", 20, 24),
            flag("noapte", "noapte", 10, 16),
        )
        val suggestions = listOf("salute", "buna", "night")
        val replacements = LanguageSwitchCorrector().resolve(flags, suggestions)
        assertEquals(listOf(20, 10, 0), replacements.map { it.startOffset })
    }
}
