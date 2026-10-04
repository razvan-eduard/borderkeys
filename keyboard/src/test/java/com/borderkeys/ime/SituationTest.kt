// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.ime.AutoCorrection.Situation
import com.borderkeys.predict.CorrectionOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One case per [AutoCorrection.Situation], including the two that are exceptions to the rule
 * above them, and how [AutoCorrection.pick] walks a list of several offers.
 */
class SituationTest {

    private fun offer(
        text: String,
        isName: Boolean = false,
        inflection: Boolean = false,
        slipsOnly: Boolean = false,
    ) = CorrectionOffer(text, isName, inflection, slipsOnly)

    private fun pick(
        typed: String,
        vararg offers: CorrectionOffer,
        suggestionQuery: String = typed,
        knownWord: String = "",
        knownWordExact: Boolean = false,
        knownWordIsName: Boolean = false,
        minimumLength: Int = 3,
        maxEdits: Int = Int.MAX_VALUE,
        capitaliseNames: Boolean = true,
        maxSlipEdits: Int = maxEdits,
    ): AutoCorrection.Pick = AutoCorrection.pick(
        typed, offers.toList(), suggestionQuery, knownWord, knownWordExact, knownWordIsName,
        minimumLength, maxEdits, capitaliseNames, maxSlipEdits,
    )

    private fun situation(
        typed: String,
        suggestion: String?,
        suggestionQuery: String = typed,
        knownWord: String = "",
        knownWordExact: Boolean = false,
        minimumLength: Int = 3,
        isProperNoun: Boolean = false,
        maxEdits: Int = Int.MAX_VALUE,
        capitaliseNames: Boolean = true,
        inflection: Boolean = false,
    ): Situation = pick(
        typed,
        *listOfNotNull(suggestion?.takeIf { it.isNotEmpty() }?.let { offer(it, isProperNoun, inflection) })
            .toTypedArray(),
        suggestionQuery = suggestionQuery,
        knownWord = knownWord,
        knownWordExact = knownWordExact,
        knownWordIsName = isProperNoun && knownWord.isNotEmpty(),
        minimumLength = minimumLength,
        maxEdits = maxEdits,
        capitaliseNames = capitaliseNames,
    ).situation

    @Test
    fun `nothing offered`() {
        assertEquals(Situation.NothingOffered, situation("teh", null))
        assertEquals(Situation.NothingOffered, situation("teh", ""))
    }

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
        // With the capitalisation preference off too.
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
        // An accent restored below the minimum length.
        assertEquals(Situation.Correctable, situation("in", "în", minimumLength = 3))
    }

    @Test
    fun `a word the dictionaries spell is left alone`() {
        // Spelled exactly, case aside: nothing to change.
        assertEquals(Situation.NoChange,
            situation("put", "out", knownWord = "put", knownWordExact = true))
        // Spelled only in another case, as a name: known, and not recased.
        assertEquals(Situation.KnownWord, situation("put", "out", knownWord = "put"))
    }

    @Test
    fun `a name being recased is correctable though the dictionaries spell it`() {
        val recased = pick("ana", offer("ana", isName = true), knownWord = "ana",
                           knownWordExact = true, knownWordIsName = true)
        assertEquals(Situation.Correctable, recased.situation)
        assertEquals("Ana", recased.text)
        // With the capital switched off, nothing changes.
        assertEquals(Situation.NoChange,
            situation("ana", "ana", knownWord = "ana", knownWordExact = true, isProperNoun = true,
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

    @Test
    fun `the first admissible of several wins`() {
        val picked = pick("loke", offer("looked"), offer("like"), maxEdits = 1)
        assertEquals(Situation.Correctable, picked.situation)
        assertEquals("like", picked.text)
    }

    @Test
    fun `a refusal of the best does not refuse the second`() {
        val name = pick("thanks", offer("Hanks", isName = true), offer("thank"), maxEdits = 2)
        assertEquals("thank", name.text)
        val inflected = pick("smooths", offer("smooth", inflection = true), offer("smooths"), offer("smoothes"))
        assertEquals("smoothes", inflected.text)
    }

    @Test
    fun `a too short first offer stops the walk`() {
        val picked = pick("ab", offer("abc"), offer("ab"), offer("abd"), minimumLength = 3)
        assertEquals(Situation.TooShort, picked.situation)
        assertNull(picked.text)
    }

    @Test
    fun `the reason on exhaustion is the first offer's`() {
        val picked = pick("snobul", offer("noul"), offer("snob", isName = true), maxEdits = 1)
        assertEquals(Situation.TooFar, picked.situation)
        assertNull(picked.text)
    }

    @Test
    fun `two slips onto neighbouring keys pass where two other edits are too far`() {
        val slips = pick("joyse", offer("house", slipsOnly = true), maxEdits = 1, maxSlipEdits = 2)
        assertEquals(Situation.Correctable, slips.situation)
        assertEquals("house", slips.text)
        val other = pick("piffle", offer("pile"), maxEdits = 1, maxSlipEdits = 2)
        assertEquals(Situation.TooFar, other.situation)
    }

    @Test
    fun `the slip ceiling follows the distance setting and the word's length`() {
        val normal = KeyboardPreferences.CORRECTION_DISTANCE_NORMAL
        assertEquals(2, AutoCorrection.maxSlipEditsFor(4, normal))
        assertEquals(1, AutoCorrection.maxSlipEditsFor(3, normal))
        assertEquals(1, AutoCorrection.maxSlipEditsFor(5, AutoCorrection.DISTANCE_STRICT))
        assertEquals(2, AutoCorrection.maxSlipEditsFor(3, AutoCorrection.DISTANCE_LOOSE))
        assertEquals(1, AutoCorrection.maxEditsFor(5, normal))
    }
}
