// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.ime.AutoCorrection.Situation
import com.borderkeys.predict.CorrectionOffer
import com.borderkeys.predict.DecodedCorrection
import com.borderkeys.predict.ListedCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        confident: Boolean = true,
    ) = ListedCorrection(text, isName, inflection, slipsOnly, confident)

    private fun decoded(text: String, inflection: Boolean = false) = DecodedCorrection(text, inflection)

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
        decoderAllowed: Boolean = true,
    ): AutoCorrection.Pick = AutoCorrection.pick(
        typed, offers.toList(), suggestionQuery, knownWord, knownWordExact, knownWordIsName,
        minimumLength, maxEdits, capitaliseNames, maxSlipEdits, decoderAllowed,
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
    fun `the decoder's word is applied only once every listed offer is passed over`() {
        val listed = pick("adterqwrds", offer("adters"), decoded("afterwards"))
        assertEquals(Situation.Correctable, listed.situation)
        assertEquals("adters", listed.text)
        val fallback = pick("Adterqwrds", offer("adters"), decoded("afterwards"), maxEdits = 1)
        assertEquals(Situation.Decoded, fallback.situation)
        assertEquals("Afterwards", fallback.text)
        assertEquals(Situation.Decoded, pick("adterqwrds", decoded("afterwards"), maxEdits = 1).situation)
    }

    @Test
    fun `the decoder's word is left out under the strict distance setting`() {
        val strict = AutoCorrection.decoderAllowedFor(AutoCorrection.DISTANCE_STRICT)
        val picked = pick("adterqwrds", offer("adters"), decoded("afterwards"), maxEdits = 1,
                          decoderAllowed = strict)
        assertEquals(Situation.TooFar, picked.situation)
        assertEquals(Situation.NothingOffered,
            pick("adterqwrds", decoded("afterwards"), decoderAllowed = strict).situation)
        assertTrue(AutoCorrection.decoderAllowedFor(KeyboardPreferences.CORRECTION_DISTANCE_NORMAL))
    }

    @Test
    fun `the decoder's word stands behind the length and inflection checks`() {
        assertEquals(Situation.TooShort, pick("ab", decoded("abc"), minimumLength = 3).situation)
        assertEquals(Situation.Inflection,
            pick("smooths", decoded("smoother", inflection = true)).situation)
    }

    @Test
    fun `an offer the taps leave uncertain is passed over for a confident one`() {
        val uncertain = pick("formsl", offer("formal", confident = false), offer("forms"))
        assertEquals(Situation.Correctable, uncertain.situation)
        assertEquals("forms", uncertain.text)
        val alone = pick("formsl", offer("formal", confident = false))
        assertEquals(Situation.Uncertain, alone.situation)
        assertNull(alone.text)
    }

    @Test
    fun `an accent restored needs no confidence check to pass the hard rules first`() {
        assertEquals(Situation.TooShort,
            pick("ab", offer("abc", confident = false), minimumLength = 3).situation)
        assertEquals(Situation.TooFar,
            pick("snobul", offer("noul", confident = false), maxEdits = 1).situation)
    }

    @Test
    fun `a known word is never decoded`() {
        assertEquals(Situation.NoChange,
            pick("form", decoded("from"), knownWord = "form", knownWordExact = true).situation)
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
