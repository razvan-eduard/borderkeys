// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.theme.TextShortcut
import com.borderkeys.ime.WordCommit.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which claim a delimiter honours, and under which switch: a shortcut, then the apostrophe map,
 * then the possessive, then autocorrect.
 */
class WordCommitTest {

    private val contractions = mapOf("cant" to "can't", "dont" to "don't", "i" to "I", "im" to "I'm")

    private fun settings(
        autoCorrectOnSpace: Boolean = true,
        autoCapitalise: Boolean = true,
    ) = WordCommit.Settings(
        autoCorrectOnSpace = autoCorrectOnSpace,
        autoCapitalise = autoCapitalise,
        minimumLength = 3,
        correctionDistance = 1,
        capitaliseNames = true,
    )

    private fun decide(
        typed: String,
        endedBy: Int = ' '.code,
        suggestion: String? = null,
        knownWord: String = "",
        possessive: String? = null,
        shortcuts: List<TextShortcut> = emptyList(),
        fromGesture: Boolean = false,
        runningText: Boolean = true,
        inflection: Boolean = false,
        settings: WordCommit.Settings = settings(),
    ) = WordCommit.decide(
        typed = typed,
        endedBy = endedBy,
        fromGesture = fromGesture,
        runningText = runningText,
        shortcuts = shortcuts,
        contractions = contractions,
        possessive = possessive,
        suggestion = suggestion,
        suggestionQuery = typed,
        knownWord = knownWord,
        isProperNoun = false,
        inflection = inflection,
        settings = settings,
    )

    @Test
    fun `an apostrophe left out is restored, even for a word the dictionaries hold`() {
        val outcome = decide("cant", suggestion = "cant", knownWord = "cant")
        assertEquals("can't", outcome.text)
        assertEquals(Kind.CONTRACTION, outcome.kind)
        assertEquals("Contraction", outcome.reason)
    }

    @Test
    fun `the map keeps the capital the user typed`() {
        assertEquals("Don't", decide("Dont").text)
    }

    @Test
    fun `the map answers to the corrections switch`() {
        val outcome = decide("dont", settings = settings(autoCorrectOnSpace = false))
        assertNull(outcome.text)
        assertEquals(WordCommit.REASON_OFF, outcome.reason)
    }

    @Test
    fun `the map does not fire inside an address`() {
        val outcome = decide("dont", runningText = false)
        assertNull(outcome.text)
        assertEquals(WordCommit.REASON_NOT_PROSE, outcome.reason)
    }

    @Test
    fun `the pronoun's capital follows the capitalisation switch, not the corrections one`() {
        val off = decide("i", settings = settings(autoCorrectOnSpace = false))
        assertEquals("I", off.text)
        assertEquals(Kind.CAPITAL, off.kind)

        val noCapitals = decide("i", settings = settings(autoCapitalise = false))
        assertNull(noCapitals.text)
    }

    @Test
    fun `a capital already typed is nothing to rewrite`() {
        val outcome = decide("I", suggestion = null)
        assertNull(outcome.text)
        assertEquals(Kind.NONE, outcome.kind)
    }

    @Test
    fun `a contraction that also carries the capital is a contraction`() {
        assertEquals(Kind.CONTRACTION, decide("im").kind)
        assertEquals("I'm", decide("im").text)
    }

    @Test
    fun `the user's own shortcut outranks the map`() {
        val outcome = decide(
            "cant",
            shortcuts = listOf(TextShortcut(trigger = "cant", expansion = "cannot")),
        )
        assertEquals("cannot", outcome.text)
        assertEquals(Kind.SHORTCUT, outcome.kind)
    }

    @Test
    fun `a shortcut applies with corrections off, a swiped word takes none of them`() {
        val shortcuts = listOf(TextShortcut(trigger = "omw", expansion = "on my way"))
        assertEquals(
            "on my way",
            decide("omw", shortcuts = shortcuts, settings = settings(autoCorrectOnSpace = false)).text,
        )
        assertNull(decide("omw", shortcuts = shortcuts, fromGesture = true).text)
        assertNull(decide("dont", fromGesture = true).text)
    }

    @Test
    fun `the possessive covers a name the map never wrote, and takes the name's capital`() {
        val outcome = decide("marias", suggestion = "maria", possessive = "maria's")
        assertEquals("Maria's", outcome.text)
        assertEquals(Kind.POSSESSIVE, outcome.kind)

        val plain = decide(
            "marias", suggestion = "maria", possessive = "maria's",
            settings = WordCommit.Settings(
                autoCorrectOnSpace = true, autoCapitalise = true, minimumLength = 3,
                correctionDistance = 1, capitaliseNames = false,
            ),
        )
        assertEquals("maria's", plain.text)
    }

    @Test
    fun `a delimiter with no word typed writes no possessive`() {
        val outcome = decide("", possessive = "maria's")
        assertNull(outcome.text)
        assertEquals(Kind.NONE, outcome.kind)
    }

    @Test
    fun `a word a digit ends is part of a number or a code, and nothing rewrites it`() {
        val correction = decide("teh", endedBy = '2'.code, suggestion = "the")
        assertNull(correction.text)
        assertEquals(Kind.NONE, correction.kind)
        assertEquals(WordCommit.REASON_NOT_PROSE, correction.reason)
        assertNull(decide("i", endedBy = '7'.code).text)
        assertNull(decide("dont", endedBy = '2'.code).text)
        assertNull(decide("marias", endedBy = '1'.code, suggestion = "maria", possessive = "maria's").text)
    }

    @Test
    fun `autocorrect gets the word none of the rewrites wanted, with its own reason`() {
        val corrected = decide("teh", suggestion = "the")
        assertEquals("the", corrected.text)
        assertEquals(Kind.CORRECTION, corrected.kind)
        assertEquals("Correctable", corrected.reason)

        val known = decide("put", suggestion = "out", knownWord = "put")
        assertNull(known.text)
        assertEquals(Kind.NONE, known.kind)
        assertEquals("KnownWord", known.reason)
    }
}
