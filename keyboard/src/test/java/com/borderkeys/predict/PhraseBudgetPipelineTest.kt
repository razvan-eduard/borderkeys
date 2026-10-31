// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.PhraseBudget
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.language.LanguageTags
import com.borderkeys.typing.Commit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phrase budget run once, at each commit, through [Pipeline] on the shipped packs: what the
 * lookup keeps is what the store holds and what the engine is handed; the store read through the
 * personal dictionary on tables in memory.
 */
class PhraseBudgetPipelineTest {

    @Test
    fun `followers of one context past its limit leave the lookup, the store and the engine alike, the newest kept`() {
        Pipeline.require()
        val english = Pipeline.open(ENGLISH)
        try {
            val language = LanguageTags.primary(ENGLISH)
            val most = PhraseBudget.PAIRS.most
            val followers = (0..most).map { "$FOLLOWER${'a' + it}" }
            // Each follower learned first, so its pair is learnable.
            english.learnHistory(followers.flatMap { follower -> List(KeyboardPreferences.DEFAULT_LEARN_AFTER_USES) { listOf(follower) } })
            for ((index, follower) in followers.withIndex()) {
                english.learnHistory(listOf(listOf(CONTEXT, follower)))
                val lookup = english.heldAfter(language, CONTEXT)
                val engine = english.sliceAfter(language, CONTEXT).links.mapTo(HashSet()) { it.word }
                val store = english.fromStore { dictionary ->
                    dictionary.pairsToLoad().filter { it.previousWord == CONTEXT }.map { it.word }.toSet()
                }
                assertEquals("after ${index + 1} followers, the store", lookup, store)
                assertEquals("and the engine", lookup, engine)
                assertEquals(minOf(index + 1, most), lookup.size)
                assertTrue("the one just recorded is kept", follower in lookup)
            }
        } finally {
            english.close()
        }
    }

    @Test
    fun `a word the pack lacks is learnable through its bare form, its possessive's stem or its hyphen's parts`() {
        Pipeline.require()
        val english = Pipeline.open(ENGLISH)
        try {
            for (word in listOf(BARE_FORM, POSSESSIVE, COMPOUND, BROKEN_COMPOUND)) {
                assertFalse("$word itself is not in the pack", english.spells(word))
            }
            assertEquals(listOf(true, true, true, false), english.learnable(listOf(BARE_FORM, POSSESSIVE, COMPOUND, BROKEN_COMPOUND)))
        } finally {
            english.close()
        }
    }

    @Test
    fun `a loaded pair's and triple's chosen marks reach the engine`() {
        Pipeline.require()
        val english = Pipeline.open(ENGLISH)
        try {
            val language = LanguageTags.primary(ENGLISH)
            english.load(
                PersonalModelLoad(
                    words = emptyList(),
                    pairs = listOf(UserBigram(language, CONTEXT, "cat", 1, AT, 1L, 1L, asserted = 1)),
                    pairContexts = listOf(UserPairContext(language, CONTEXT, 1L, AT, 1L)),
                    triples = listOf(UserTrigram(language, "a", CONTEXT, "cat", 1, AT, 1L, 1L, asserted = 1)),
                    tripleContexts = listOf(UserTripleContext(language, "a", CONTEXT, 1L, AT, 1L)),
                    clocks = emptyList(),
                ),
            )
            assertEquals(listOf("cat" to 1), english.sliceAfter(language, CONTEXT).links.map { it.word to it.asserted })
            assertEquals(listOf("cat" to 1), english.sliceAfter(language, CONTEXT, "a").links.map { it.word to it.asserted })
        } finally {
            english.close()
        }
    }

    @Test
    fun `a pair and a triple committed as chosen carry the mark to the engine and stay outside the budget`() {
        Pipeline.require()
        val english = Pipeline.open(ENGLISH)
        try {
            val language = LanguageTags.primary(ENGLISH)
            val copy = english.workingCopy
            val chosen = copy.commit(
                Commit(LearnedWord("cat", ENGLISH, 1, AT), CONTEXT, "a", language, pairAsserted = true, tripleAsserted = true),
            )
            assertEquals(listOf(1, 1), listOf(chosen.pairs.single().asserted, chosen.triples.single().asserted))
            // One follower more than a context of pairs keeps, each learned first, so its pair is learnable.
            val followers = (0..PhraseBudget.PAIRS.most).map { "$FOLLOWER${'a' + it}" }
            for (follower in followers) {
                repeat(KeyboardPreferences.DEFAULT_LEARN_AFTER_USES) {
                    copy.commit(Commit(LearnedWord(follower, ENGLISH, 1, AT), null, null, language))
                }
            }
            val dropped = followers.flatMap { follower ->
                val writes = copy.commit(Commit(LearnedWord(follower, ENGLISH, 1, AT), CONTEXT, "a", language))
                writes.gonePairs.map { it.word } + writes.goneTriples.map { it.word }
            }
            assertTrue("the budget gave up others", dropped.isNotEmpty())
            assertFalse("never the chosen links: $dropped", "cat" in dropped)
            for (before in listOf(null, "a")) {
                assertEquals("after $before", 1, english.sliceAfter(language, CONTEXT, before).links.single { it.word == "cat" }.asserted)
            }
        } finally {
            english.close()
        }
    }

    @Test
    fun `a committed phrase's rows are in lower case, under its language, at its use's time`() {
        Pipeline.require()
        val english = Pipeline.open(ENGLISH)
        try {
            val language = LanguageTags.primary(ENGLISH)
            val writes = english.workingCopy.commit(Commit(LearnedWord("Cat", ENGLISH, 1, AT), "The", "On", language))
            val pair = writes.pairs.single()
            assertEquals(listOf(language, "the", "cat", AT), listOf(pair.language, pair.previousWord, pair.word, pair.lastUsedAt))
            val triple = writes.triples.single()
            assertEquals(
                listOf(language, "on", "the", "cat", AT),
                listOf(triple.language, triple.previousWord2, triple.previousWord1, triple.word, triple.lastUsedAt),
            )
        } finally {
            english.close()
        }
    }

    private companion object {
        const val ENGLISH = "en-US"
        const val CONTEXT = "the"
        const val FOLLOWER = "follower"

        /** A use's time. */
        const val AT = 1_000L

        /** Words the English pack does not spell, and what in it makes each learnable or not. */
        const val BARE_FORM = "'cause"
        const val POSSESSIVE = "illness's"
        const val COMPOUND = "pencil-green"
        const val BROKEN_COMPOUND = "pencil-grean"
    }
}
