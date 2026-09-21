// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two ways a correction turns into an argument with the user.
 *
 * Both of these shipped: a correctly spelled word was replaced by a longer, more common one
 * because the engine ranks by likelihood and nothing was asking whether the word was already a
 * word; and a capitalised word was "corrected" to the same word in lower case, because the
 * dictionaries store one spelling and the comparison was exact.
 */
class AutoCorrectionTest {

    private val minimum = 3

    @Test
    fun `a word the dictionaries know is left alone`() {
        assertNull(
            "a real word was replaced by a more likely one",
            AutoCorrection.correctionFor(
                typed = "cana", suggestion = "canapea",
                suggestionQuery = "cana", knownWord = "cana",
                minimumLength = minimum,
            ),
        )
    }

    @Test
    fun `a word the dictionaries do not know is corrected`() {
        assertEquals(
            "canapea",
            AutoCorrection.correctionFor(
                typed = "canapae", suggestion = "canapea",
                suggestionQuery = "canapae", knownWord = "",
                minimumLength = minimum,
            ),
        )
    }

    @Test
    fun `a suggestion that differs only in case is not a correction`() {
        assertNull(
            "the capital the user typed was taken away",
            AutoCorrection.correctionFor(
                typed = "Daca", suggestion = "daca",
                suggestionQuery = "Daca", knownWord = "",
                minimumLength = minimum,
            ),
        )
    }

    @Test
    fun `a correction keeps the capital of the word it replaces`() {
        assertEquals(
            "Dacă",
            AutoCorrection.correctionFor(
                typed = "Daca", suggestion = "dacă",
                suggestionQuery = "Daca", knownWord = "",
                minimumLength = minimum,
            ),
        )
    }

    @Test
    fun `a shouted word is corrected in kind`() {
        assertEquals("DACĂ", AutoCorrection.matchCase("DACA", "dacă"))
    }

    @Test
    fun `a single capital letter starts a sentence, it does not shout`() {
        // One capital is where a sentence begins; two are a decision to shout. A correction of
        // a one-letter word must not come back in capitals.
        assertEquals("Ai", AutoCorrection.matchCase("A", "ai"))
    }

    @Test
    fun `a correction that is already capitalised keeps it when flagged a proper noun`() {
        assertEquals(
            "Bucureşti",
            AutoCorrection.matchCase("bucuresti", "Bucureşti", isProperNoun = true),
        )
    }

    @Test
    fun `with the names setting off a name is cased like any other word`() {
        // The capital is the only thing the preference governs. "maria" typed lower case stays
        // lower case, and the dictionary's own spelling no longer forces one.
        assertNull(
            AutoCorrection.correctionFor(
                "maria", "Maria", "maria", "maria", 3,
                isProperNoun = true, capitaliseNames = false,
            ),
        )
        // But the guard that keeps a name away from an ordinary word is not a preference: it
        // still refuses, exactly as it does with the setting on.
        assertNull(
            AutoCorrection.correctionFor(
                "everyone", "Everton", "everyone", "", 3,
                isProperNoun = true, maxEdits = 2, capitaliseNames = false,
            ),
        )
        // A name's own letters still correct -- what changes is only that the result is not
        // capitalised for having been flagged.
        assertEquals(
            "laurențiu",
            AutoCorrection.correctionFor(
                "laurentiu", "Laurențiu", "laurentiu", "", 3,
                isProperNoun = true, capitaliseNames = false,
            ),
        )
    }

    @Test
    fun `a correction capitalised for no reason the typed word gives is brought back down`() {
        // The personal dictionary keeps the literal case a word was last committed in (see
        // UserModel::learn), which can be capitalised from an unrelated earlier sentence start
        // and has nothing to do with isProperNoun -- the trie never sets that flag for a learned
        // word. Left alone, that stale capital would resurface here mid-sentence forever; this is
        // the bug matchCase's own doc now describes as the reason both branches fully decide the
        // case rather than only ever adding a capital.
        assertEquals("radial", AutoCorrection.matchCase("radial", "Radial"))
    }

    @Test
    fun `a name only ever corrects its own letters`() {
        assertNull(AutoCorrection.correctionFor("everyone", "Everton", "everyone", "", 3, isProperNoun = true, maxEdits = 2))
        assertNull(AutoCorrection.correctionFor("thanks", "Hanks", "thanks", "", 3, isProperNoun = true, maxEdits = 2))
        assertEquals("Maria", AutoCorrection.correctionFor("maria", "Maria", "maria", "maria", 3, isProperNoun = true))
        assertEquals("Laurențiu", AutoCorrection.correctionFor("laurentiu", "Laurențiu", "laurentiu", "", 3, isProperNoun = true))
    }

    @Test
    fun `a word shorter than the minimum is never corrected`() {
        assertNull(
            AutoCorrection.correctionFor(
                typed = "ai", suggestion = "aici",
                suggestionQuery = "ai", knownWord = "", minimumLength = minimum,
            ),
        )
    }

    @Test
    fun `a diacritic restores even on a word shorter than the minimum`() {
        // "in" and "în" are both real, unrelated Romanian words -- this is not a guess the way
        // "ai" -> "aici" above is, so the short-word gate must not apply to it.
        assertEquals(
            "în",
            AutoCorrection.correctionFor(
                typed = "in", suggestion = "în",
                suggestionQuery = "in", knownWord = "în", minimumLength = minimum,
            ),
        )
    }

    @Test
    fun `a diacritic restoration on a short word keeps its capital`() {
        assertEquals(
            "În",
            AutoCorrection.correctionFor(
                typed = "In", suggestion = "în",
                suggestionQuery = "In", knownWord = "în", minimumLength = minimum,
            ),
        )
    }

    @Test
    fun `a single letter is not corrected into an accented one`() {
        // Reported from a device: typing "t" outlined "ț" as the word space would commit. The
        // diacritic exemption waives the minimum because short words are where restoring an
        // accent matters, and it had no floor -- so one letter qualified. No one-letter word in
        // any language here needs one: "a", "o", "e" and "i" are words, "ă", "î", "ț" and "ș"
        // are not.
        assertNull(
            AutoCorrection.correctionFor(
                typed = "t", suggestion = "ț",
                suggestionQuery = "t", knownWord = "", minimumLength = minimum,
            ),
        )
        assertNull(
            AutoCorrection.correctionFor(
                typed = "s", suggestion = "ș",
                suggestionQuery = "s", knownWord = "", minimumLength = minimum,
            ),
        )
    }

    @Test
    fun `a short word that is not a diacritic match still needs the minimum`() {
        assertNull(
            AutoCorrection.correctionFor(
                typed = "sa", suggestion = "salut",
                suggestionQuery = "sa", knownWord = "", minimumLength = minimum,
            ),
        )
    }

    @Test
    fun `nothing is corrected when there is no suggestion`() {
        assertNull(
            AutoCorrection.correctionFor(
                typed = "qwrt", suggestion = null,
                suggestionQuery = "qwrt", knownWord = "", minimumLength = minimum,
            ),
        )
    }

    @Test
    fun `an answer about an earlier word is not applied to this one`() {
        // The engine posts its answer back rather than blocking, so a delimiter can be typed
        // before the answer for the word just finished has arrived -- suggestion would then
        // still be whatever "tinde" resolved to two words ago, not an answer about "harta" at
        // all. This shipped as "tinde" reaching "idependent": no edit-distance budget this
        // engine uses gets from one to the other, because it was never asked to.
        assertNull(
            "a suggestion answering for a different word than the one being committed was applied",
            AutoCorrection.correctionFor(
                typed = "harta", suggestion = "idependent",
                suggestionQuery = "tinde", knownWord = "", minimumLength = minimum,
            ),
        )
    }

    @Test
    fun `a name typed lower case mid-sentence is still capitalised`() {
        // The whole point of the proper-noun flag: "ana" is not the start of a sentence and
        // shiftState says nothing special about this position, yet a name is still "Ana" -- the
        // one override that is not about what was typed at all.
        assertEquals("Ana", AutoCorrection.matchCase("ana", "ana", isProperNoun = true))
    }

    @Test
    fun `a name typed in full caps still shouts`() {
        // Caps lock is a stronger, more deliberate signal than "capitalise this one name" -- see
        // matchCase's own doc for why this check has to come before the proper-noun one, not
        // after it.
        assertEquals("ANA", AutoCorrection.matchCase("ANA", "ana", isProperNoun = true))
    }

    @Test
    fun `correctionFor also applies the proper-noun override`() {
        // knownWord equals typed here on purpose: a name the dictionary knows is exactly the
        // realistic case, not an edge case -- the dictionary is not offering a different word,
        // only a capitalised spelling of the same one, and the "a word the dictionary knows is
        // left alone" rule below must not read that as "nothing to do" the way it correctly
        // does for an ordinary word (see "a word the dictionaries know is left alone" above).
        assertEquals(
            "Ana",
            AutoCorrection.correctionFor(
                typed = "ana", suggestion = "ana",
                suggestionQuery = "ana", knownWord = "ana", minimumLength = minimum,
                isProperNoun = true,
            ),
        )
    }

    @Test
    fun `an ordinary word the dictionary knows is still left alone even so`() {
        // The regression this feature must not cause: adding the isProperNoun escape hatch to
        // the knownWord gate must not loosen it for every OTHER word that happens to equal
        // knownWord -- only for a name.
        assertNull(
            AutoCorrection.correctionFor(
                typed = "cana", suggestion = "canapea",
                suggestionQuery = "cana", knownWord = "cana", minimumLength = minimum,
                isProperNoun = false,
            ),
        )
    }

    @Test
    fun `edit distance counts a swap of two adjacent letters as one edit`() {
        assertEquals(1, AutoCorrection.editDistance("teh", "the"))
        assertEquals(1, AutoCorrection.editDistance("helo", "hello"))
        assertEquals(1, AutoCorrection.editDistance("recieve", "receive"))
        assertEquals(2, AutoCorrection.editDistance("snobul", "noul"))
        assertEquals(1, AutoCorrection.editDistance("definately", "definitely"))
        assertEquals(2, AutoCorrection.editDistance("acomodate", "accommodate"))
        assertEquals(0, AutoCorrection.editDistance("same", "same"))
        assertEquals(4, AutoCorrection.editDistance("", "four"))
    }

    @Test
    fun `the default distance allows a second edit only in a long word`() {
        assertEquals(1, AutoCorrection.maxEditsFor(6, 1))
        assertEquals(2, AutoCorrection.maxEditsFor(8, 1))
        assertEquals(1, AutoCorrection.maxEditsFor(12, AutoCorrection.DISTANCE_STRICT))
        assertEquals(2, AutoCorrection.maxEditsFor(3, AutoCorrection.DISTANCE_LOOSE))
    }

    @Test
    fun `a correct word the dictionaries do not know is not replaced by something far away`() {
        // "snobul" is Romanian for "the snob"; the dictionaries only know "snob". Two edits on
        // six letters is a different word, not a slip -- left alone under the default ceiling.
        assertNull(
            AutoCorrection.correctionFor(
                typed = "snobul", suggestion = "noul",
                suggestionQuery = "snobul", knownWord = "", minimumLength = minimum,
                maxEdits = AutoCorrection.maxEditsFor(6, 1),
            ),
        )
        // The same distance on a long word is two slips, and still corrects.
        assertEquals(
            "accommodate",
            AutoCorrection.correctionFor(
                typed = "acomodate", suggestion = "accommodate",
                suggestionQuery = "acomodate", knownWord = "", minimumLength = minimum,
                maxEdits = AutoCorrection.maxEditsFor(9, 1),
            ),
        )
        // A transposition is one edit and always corrects.
        assertEquals(
            "the",
            AutoCorrection.correctionFor(
                typed = "teh", suggestion = "the",
                suggestionQuery = "teh", knownWord = "", minimumLength = minimum,
                maxEdits = AutoCorrection.maxEditsFor(3, AutoCorrection.DISTANCE_STRICT),
            ),
        )
        // An accent-only restoration is zero edits after folding, whatever the ceiling.
        assertEquals(
            "să",
            AutoCorrection.correctionFor(
                typed = "sa", suggestion = "să",
                suggestionQuery = "sa", knownWord = "", minimumLength = minimum,
                maxEdits = 1,
            ),
        )
    }

    @Test
    fun `an apostrophe or hyphen after one capital is not a shout`() {
        assertEquals("I'm", AutoCorrection.matchCase("I'", "i'm"))
        assertEquals("A-level", AutoCorrection.matchCase("A-", "a-level"))
        assertEquals("THE", AutoCorrection.matchCase("TH", "the"))
    }

    @Test
    fun `a suggestion identical to what was typed is not a correction`() {
        // Every other test here supplies a genuinely different suggestion or one differing by
        // case, which is the separate branch matchCase exists for -- none of them exercises the
        // exact-match branch directly. This is the plain "nothing to correct" case the doc
        // comment lists first, asserted on its own rather than only as a side effect.
        assertNull(
            AutoCorrection.correctionFor(
                typed = "canapea", suggestion = "canapea",
                suggestionQuery = "canapea", knownWord = "", minimumLength = minimum,
            ),
        )
    }
}
