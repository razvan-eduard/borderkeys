// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.ime.AutoCorrection.Situation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One case per [AutoCorrection.Situation], including the two that are exceptions to the rule
 * above them.
 *
 * These used to be reachable only through a chain of early returns, where a test could say the
 * answer was null but never which of six reasons produced it -- so a guard could stop working
 * and another could cover for it, and the suite would stay green. Naming them is what makes
 * that impossible.
 */
class SituationTest {

    private fun situation(
        typed: String,
        suggestion: String?,
        suggestionQuery: String = typed,
        knownWord: String = "",
        minimumLength: Int = 3,
        isProperNoun: Boolean = false,
        maxEdits: Int = Int.MAX_VALUE,
        capitaliseNames: Boolean = true,
        inflection: Boolean = false,
    ): Situation {
        val cased = AutoCorrection.matchCase(typed, suggestion.orEmpty(),
                                             isProperNoun && capitaliseNames)
        return AutoCorrection.situationOf(typed, suggestion, suggestionQuery, knownWord, cased,
                                          minimumLength, isProperNoun, maxEdits, capitaliseNames,
                                          inflection)
    }

    @Test
    fun `nothing offered`() {
        assertEquals(Situation.NothingOffered, situation("teh", null))
        assertEquals(Situation.NothingOffered, situation("teh", ""))
    }

    /** The engine answers by posting back, so an answer can describe a word already finished. */
    @Test
    fun `an answer about a different word is refused before anything else is asked`() {
        assertEquals(Situation.StaleAnswer,
            situation("tinde", "idependent", suggestionQuery = "indepen"))
    }

    @Test
    fun `further away than a slip could account for`() {
        assertEquals(Situation.TooFar, situation("snobul", "noul", maxEdits = 1))
        // The same pair is correctable once the ceiling admits two edits.
        assertEquals(Situation.Correctable, situation("snobul", "noul", maxEdits = 3))
    }

    @Test
    fun `a name may correct only its own letters`() {
        assertEquals(Situation.NameMismatch,
            situation("everyone", "everton", isProperNoun = true))
        // ...whatever the capitalisation preference says, which gates the capital and not this.
        assertEquals(Situation.NameMismatch,
            situation("everyone", "everton", isProperNoun = true, capitaliseNames = false))
    }

    @Test
    fun `an answer that changes nothing once cased`() {
        assertEquals(Situation.NoChange, situation("carte", "carte"))
    }

    @Test
    fun `too short to guess about, unless an accent is being restored`() {
        assertEquals(Situation.TooShort, situation("ab", "abc", minimumLength = 3))
        // "in" to "în" is two real words that differ by an accent, not a coin toss.
        assertEquals(Situation.Correctable, situation("in", "în", minimumLength = 3))
    }

    @Test
    fun `a word the dictionaries spell is left alone`() {
        assertEquals(Situation.KnownWord, situation("put", "out", knownWord = "put"))
    }

    /** The exception: for a name, knownWord matching is *why* there is something to do -- the
     *  dictionary is offering the same word with a capital, not a different word. */
    @Test
    fun `a name being recased is correctable though the dictionaries spell it`() {
        assertEquals(Situation.Correctable,
            situation("ana", "ana", knownWord = "ana", isProperNoun = true))
        // With the capital switched off there is no capital to apply, so nothing changes.
        assertEquals(Situation.NoChange,
            situation("ana", "ana", knownWord = "ana", isProperNoun = true,
                      capitaliseNames = false))
    }

    @Test
    fun `a regular inflection of a known stem is left alone`() {
        assertEquals(Situation.Inflection, situation("smooths", "smooth", inflection = true))
        // An accent, a case or a mark restored is not another word.
        assertEquals(Situation.Correctable, situation("cartii", "cărții", inflection = true))
        assertEquals(Situation.Correctable, situation("marias", "maria's", inflection = true))
    }

    @Test
    fun `an ordinary correction`() {
        assertEquals(Situation.Correctable, situation("teh", "the"))
    }
}
