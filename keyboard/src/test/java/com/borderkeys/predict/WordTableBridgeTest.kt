// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserWord
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.language.LanguageTags
import com.borderkeys.typing.Commit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's word table and the lookup it is handed from, across the bridge on the host library
 * and the shipped English pack: each word put under the id the engine gave it reaches the strip as
 * the lookup spells it.
 */
class WordTableBridgeTest {

    @Test
    fun `a word put by the id the engine gave it reaches the strip as the lookup spells it`() {
        Pipeline.require()
        val english = Pipeline.open(ENGLISH)
        try {
            val copy = english.workingCopy
            val language = LanguageTags.primary(ENGLISH)
            copy.load(PersonalSnapshot.EMPTY.copy(words = listOf(FIRST, SECOND).map { UserWord(it, LanguageTags.primary(ENGLISH), USES, AT) }))
            assertEquals(listOf(FIRST, SECOND), english.strip(PREFIX).filter { it in WORDS })

            // Committed more often with a capital than without, the loaded word is served with it.
            repeat(USES + 1) { copy.commit(Commit(LearnedWord(SECOND_CAPITALISED, ENGLISH, 1, AT), null, null, language)) }
            assertEquals(listOf(SECOND_CAPITALISED, FIRST), english.strip(PREFIX).filter { it in WORDS })

            // A new word takes an id at its first commit, and its later commits are put under it.
            copy.commit(Commit(LearnedWord(THIRD, ENGLISH, 1, AT), null, null, language))
            assertFalse(THIRD in english.strip(PREFIX))
            repeat(KeyboardPreferences.DEFAULT_LEARN_AFTER_USES - 1) {
                copy.commit(Commit(LearnedWord(THIRD, ENGLISH, 1, AT), null, null, language))
            }
            assertEquals(listOf(SECOND_CAPITALISED, FIRST, THIRD), english.strip(PREFIX).filter { it in WORDS })
        } finally {
            english.close()
        }
    }

    @Test
    fun `a word's case forms count together towards the learn-after count`() {
        Pipeline.require()
        val english = Pipeline.open(ENGLISH)
        try {
            val learnAfter = KeyboardPreferences.DEFAULT_LEARN_AFTER_USES
            english.workingCopy.load(
                PersonalSnapshot.EMPTY.copy(
                    words = listOf(UserWord(FIRST_CAPITALISED, LanguageTags.primary(ENGLISH), learnAfter - 1, AT), UserWord(FIRST, LanguageTags.primary(ENGLISH), 1, AT)),
                ),
            )
            assertEquals(listOf(FIRST_CAPITALISED), english.strip(PREFIX).filter { it in WORDS })
            english.workingCopy.load(
                PersonalSnapshot.EMPTY.copy(
                    words = listOf(UserWord(FIRST_CAPITALISED, LanguageTags.primary(ENGLISH), learnAfter - 2, AT), UserWord(FIRST, LanguageTags.primary(ENGLISH), 1, AT)),
                ),
            )
            assertEquals(emptyList<String>(), english.strip(PREFIX).filter { it in WORDS })
        } finally {
            english.close()
        }
    }

    @Test
    fun `a word loaded as chosen is offered at once, and one added by hand before one used as often`() {
        Pipeline.require()
        val english = Pipeline.open(ENGLISH)
        try {
            english.workingCopy.load(
                PersonalSnapshot.EMPTY.copy(
                    words = listOf(
                        UserWord(FIRST, LanguageTags.primary(ENGLISH), 1, AT, asserted = 1),
                        UserWord(SECOND, LanguageTags.primary(ENGLISH), 1, AT),
                        UserWord(THIRD, LanguageTags.primary(ENGLISH), USES, AT),
                        UserWord(FOURTH, LanguageTags.primary(ENGLISH), USES, AT, added = 1),
                    ),
                ),
            )
            assertEquals(listOf(FOURTH, THIRD, FIRST), english.strip(PREFIX).filter { it in WORDS })
        } finally {
            english.close()
        }
    }

    @Test
    fun `a case form with no uses left adds nothing to its word`() {
        Pipeline.require()
        val english = Pipeline.open(ENGLISH)
        try {
            english.workingCopy.load(
                PersonalSnapshot.EMPTY.copy(
                    words = listOf(
                        UserWord(FIRST, LanguageTags.primary(ENGLISH), USES, AT),
                        UserWord(FIRST_CAPITALISED, LanguageTags.primary(ENGLISH), 0, AT, deliberateCapitals = USES),
                        UserWord(SECOND, LanguageTags.primary(ENGLISH), USES, AT),
                        UserWord(SECOND_CAPITALISED, LanguageTags.primary(ENGLISH), 1, AT, deliberateCapitals = 1),
                    ),
                ),
            )
            assertEquals(listOf(SECOND), english.namesOnStrip(PREFIX).filter { it in WORDS })
        } finally {
            english.close()
        }
    }

    @Test
    fun `the engine tells the lookup which words an active pack knows`() {
        Pipeline.require()
        val english = Pipeline.open(ENGLISH)
        try {
            val copy = english.workingCopy
            copy.load(PersonalSnapshot.EMPTY.copy(words = listOf(PACK_WORD, FIRST).map { UserWord(it, LanguageTags.primary(ENGLISH), USES, AT) }))
            assertTrue(copy.lookup.known(PACK_WORD))
            assertFalse(copy.lookup.known(FIRST))
            copy.commit(Commit(LearnedWord(SECOND, ENGLISH, 1, AT), PACK_WORD_PUT, null, LanguageTags.primary(ENGLISH)))
            assertTrue(copy.lookup.known(PACK_WORD_PUT))
            assertFalse(copy.lookup.known(SECOND))
        } finally {
            english.close()
        }
    }

    @Test
    fun `a load places each word's uses in no language known in the active packs that spell it, else in its links' languages, else in none`() {
        Pipeline.require()
        val both = Pipeline.open(ENGLISH, ROMANIAN)
        try {
            val english = LanguageTags.primary(ENGLISH)
            val romanian = LanguageTags.primary(ROMANIAN)
            val copy = both.workingCopy
            copy.load(
                PersonalSnapshot.EMPTY.copy(
                    words = listOf(PACK_WORD, ROMANIAN_WORD_CAPITALISED, SHARED_WORD, FIRST, SECOND)
                        .map { UserWord(it, UserWord.UNPLACED, USES, AT) },
                    pairs = listOf(
                        UserBigram(romanian, ROMANIAN_WORD, FIRST, 1, AT, 0L, 0L),
                        UserBigram(romanian, PACK_WORD, ROMANIAN_WORD, 1, AT, 0L, 0L),
                    ),
                ),
            )
            val placed = { word: String -> copy.lookup.counts(word)?.let { it.languages to it.unplaced } }
            assertEquals("the English pack, its Romanian link aside", mapOf(english to USES) to 0, placed(PACK_WORD))
            assertEquals("the Romanian pack, in any case", mapOf(romanian to USES) to 0, placed(ROMANIAN_WORD))
            assertEquals("both packs", mapOf(english to USES, romanian to USES) to 0, placed(SHARED_WORD))
            assertEquals("no pack, its link's language", mapOf(romanian to USES) to 0, placed(FIRST))
            assertEquals("no pack and no link", emptyMap<String, Int>() to USES, placed(SECOND))
        } finally {
            both.close()
        }
    }

    private companion object {
        const val ENGLISH = "en-US"
        const val ROMANIAN = "ro-RO"

        /** Words the Romanian pack spells and the English pack does not, and one both spell. */
        const val ROMANIAN_WORD = "casă"
        const val ROMANIAN_WORD_CAPITALISED = "Casă"
        const val SHARED_WORD = "hotel"

        /** Words no pack spells, past the learn-after count when loaded, and the letters they start with. */
        const val PREFIX = "zqxw"
        const val FIRST = "zqxwalpha"
        const val FIRST_CAPITALISED = "Zqxwalpha"
        const val SECOND = "zqxwbeta"
        const val SECOND_CAPITALISED = "Zqxwbeta"
        const val THIRD = "zqxwgamma"
        const val FOURTH = "zqxwdelta"
        val WORDS = setOf(FIRST, FIRST_CAPITALISED, SECOND, SECOND_CAPITALISED, THIRD, FOURTH)

        /** How many uses each loaded word has, and when it was last used. */
        const val USES = 5
        const val AT = 1_000L

        /** Words the English pack spells: one loaded, one first put as the word before a commit. */
        const val PACK_WORD = "house"
        const val PACK_WORD_PUT = "garden"
    }
}
