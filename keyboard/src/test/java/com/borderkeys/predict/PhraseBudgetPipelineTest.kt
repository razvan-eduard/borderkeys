// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.PhraseBudget
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.language.LanguageTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phrase budget run as each phrase is recorded, through [Pipeline] on the shipped packs. */
class PhraseBudgetPipelineTest {

    @Test
    fun `followers of one context past its limit leave the engine and the store alike, the newest kept`() {
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
                val engine = english.heldAfter(language, CONTEXT)
                val store = english.personalSnapshot().pairs.filter { it.previousWord == CONTEXT }.map { it.word }.toSet()
                assertEquals("after ${index + 1} followers", store, engine)
                assertEquals(minOf(index + 1, most), engine.size)
                assertTrue("the one just recorded is kept", follower in engine)
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
    fun `the bridge reads a held context's links and an empty context's none`() {
        Pipeline.require()
        val handle = NativePredictor.nativeCreate()
        try {
            val language = LanguageTags.primary(ENGLISH)
            NativePredictor.nativeLearn(handle, "cat", CONTEXT, null, false, false, language, true)
            val words = arrayOfNulls<String>(SLOTS)
            val counts = IntArray(SLOTS)
            val seen = LongArray(SLOTS)
            val counted = LongArray(NativePredictor.CONTEXT_SLOTS)
            assertEquals(1, NativePredictor.nativeContextLinks(handle, language, null, CONTEXT, words, counts, seen, counted))
            assertEquals(listOf("cat", 1), listOf(words[0], counts[0]))
            assertEquals(1L, counted[NativePredictor.CONTEXT_TOTAL])
            assertEquals(1L, counted[NativePredictor.CONTEXT_SEEN])
            val empty = LongArray(NativePredictor.CONTEXT_SLOTS) { Long.MAX_VALUE }
            assertEquals(
                0,
                NativePredictor.nativeContextLinks(
                    handle, language, null, "cat", arrayOfNulls(SLOTS), IntArray(SLOTS), LongArray(SLOTS), empty,
                ),
            )
            assertEquals(listOf(0L, 0L), empty.toList())
        } finally {
            NativePredictor.nativeDestroy(handle)
        }
    }

    @Test
    fun `a learned phrase is reported in lower case, under its language, at its use's time`() {
        Pipeline.require()
        val handle = NativePredictor.nativeCreate()
        try {
            val language = LanguageTags.primary(ENGLISH)
            val outcome = PersonalLinks.learn(handle, LearnedWord("Cat", ENGLISH, 1, AT), "The", "On", language)
            val pair = outcome.pairs.single().use
            assertEquals(listOf(language, "the", "cat", AT), listOf(pair?.language, pair?.previousWord, pair?.word, pair?.lastUsedAt))
            val triple = outcome.triples.single().use
            assertEquals(
                listOf(language, "on", "the", "cat", AT),
                listOf(triple?.language, triple?.previousWord2, triple?.previousWord1, triple?.word, triple?.lastUsedAt),
            )
        } finally {
            NativePredictor.nativeDestroy(handle)
        }
    }

    private companion object {
        const val ENGLISH = "en-US"
        const val CONTEXT = "the"
        const val FOLLOWER = "follower"

        /** Room for more links than one context is given here. */
        const val SLOTS = 4

        /** A use's time. */
        const val AT = 1_000L

        /** Words the English pack does not spell, and what in it makes each learnable or not. */
        const val BARE_FORM = "'cause"
        const val POSSESSIVE = "illness's"
        const val COMPOUND = "pencil-green"
        const val BROKEN_COMPOUND = "pencil-grean"
    }
}
