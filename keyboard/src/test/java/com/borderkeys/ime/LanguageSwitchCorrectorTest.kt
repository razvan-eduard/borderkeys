// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision behind revisiting a correction once the conversation's language turns out to have
 * been misjudged, kept separate from the InputConnection and native calls it needs answers from
 * -- the same reason [AutoCorrection]/[AutoShift] are tested this way.
 */
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
        // Nothing to revisit against -1: there is no pack to ask for a better spelling of
        // anything, so this is not the "worth spending anything further on" case.
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
    fun `resolve orders replacements right-to-left by offset`() {
        // Applying the leftmost edit first would shift every offset to its right by however much
        // the replacement text's length differs from the original -- right-to-left is what lets
        // the caller apply these in the order given without re-reading anything in between.
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
