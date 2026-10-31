// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.PhraseBudget
import com.borderkeys.data.PhraseKey
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext
import com.borderkeys.data.entity.UserWord
import com.borderkeys.data.personal.PersonalLookup
import com.borderkeys.data.personal.WordRows
import com.borderkeys.language.LanguageTags
import com.borderkeys.typing.Commit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The words, pairs and triples the lookup holds against what the engine is handed, on the host
 * library and the shipped English pack: a loaded model, a store loaded past its cap, then random
 * commits in two languages, with choices, sentence starts, words in other cases, words the load
 * did not hold and links the clean-up gives up. Every word the lookup holds is the engine's word
 * table entry under the same id, and every context written lists, in the lookup, the links the
 * engine holds after it once a request there hands it the context's slice, in the order the engine
 * offers them, with their counts, choices and observations and the context's total and
 * observations.
 */
class LookupLinksMatchEngineLearningTest {

    /** A context: its language and its one or two words, in lower case. */
    private data class Written(val language: String, val previous2: String?, val previous1: String)

    @Test
    fun `the lookup's words, and its links after every context, are what the engine is handed`() {
        Pipeline.require()
        val english = Pipeline.open(ENGLISH)
        try {
            val copy = english.workingCopy
            val random = Random(SEED)
            val written = LinkedHashSet<Written>()
            val pastTheCap = LinkedHashSet<Written>()
            copy.load(loaded(random, written, pastTheCap))
            assertSameModel(english, copy.lookup, written + pastTheCap, "after the load")
            assertEquals(
                "the store loaded past its cap holds the cap",
                PhraseBudget.LANGUAGE_CAP,
                pastTheCap.filter { it.previous2 == null }
                    .sumOf { copy.lookup.pairsAfter(it.language, it.previous1).links.size },
            )
            var mostHeld = 0
            for (commit in 1..COMMITS) {
                val language = LANGUAGES.random(random)
                val words = if (random.nextInt(UNLOADED_ONE_IN) == 0) UNLOADED else VOCABULARY
                val word = spelled(random, words.random(random))
                val previous1 = when {
                    random.nextInt(SENTENCE_START_ONE_IN) == 0 -> UserBigram.SENTENCE_START
                    random.nextInt(HUB_ONE_IN) == 0 -> spelled(random, HUB)
                    random.nextInt(UNLOADED_ONE_IN) == 0 -> spelled(random, (UNLOADED + JUST_BEFORE).random(random))
                    else -> spelled(random, VOCABULARY.random(random))
                }
                val previous2 = (if (random.nextInt(UNLOADED_ONE_IN) == 0) UNLOADED + TWO_BEFORE else VOCABULARY).random(random)
                    .takeIf { previous1 != UserBigram.SENTENCE_START && random.nextBoolean() }
                val uses = if (random.nextInt(UNCOUNTED_ONE_IN) == 0) 0 else 1
                val chosen = if (random.nextInt(CHOSEN_ONE_IN) == 0) 1 else 0
                copy.commit(
                    Commit(
                        LearnedWord(
                            word, ENGLISH, uses, AT + commit, deliberateCapital = random.nextInt(CAPITALISED_ONE_IN) == 0,
                            asserted = chosen,
                        ),
                        previous1, previous2, language,
                        pairAsserted = random.nextInt(CHOSEN_ONE_IN) == 0,
                        tripleAsserted = random.nextInt(CHOSEN_ONE_IN) == 0,
                    ),
                )
                val pairContext = Written(language, null, PhraseKey.word(previous1))
                written += pairContext
                previous2?.let { written += Written(language, PhraseKey.word(it), PhraseKey.word(previous1)) }
                mostHeld = maxOf(mostHeld, copy.lookup.pairsAfter(language, pairContext.previous1).links.size)
                if (random.nextInt(STORE_DROP_ONE_IN) == 0) {
                    dropOne(copy, random, written.random(random))
                }
                if (commit % COMPARE_EVERY == 0) {
                    assertSameModel(english, copy.lookup, written, "after $commit commits")
                }
            }
            assertTrue("a context reached its budget, $mostHeld links", mostHeld >= PhraseBudget.PAIRS.most)
            val learnedAfter = written.filter { copy.lookup.linksOf(it).links.isNotEmpty() }
            assertTrue(
                "a word only ever written before another was learned before it",
                learnedAfter.any { it.previous1 in JUST_BEFORE },
            )
            assertTrue(
                "and one only ever written two words before another, before the two",
                learnedAfter.any { it.previous2 in TWO_BEFORE },
            )
        } finally {
            english.close()
        }
    }

    /**
     * Asserts that every word [lookup] holds is the engine's word table entry under the same id,
     * and that every context of [written] lists in [lookup] the links the engine is handed after it
     * ([Pipeline.sliceAfter]), the language's clock, which is not handed, aside.
     */
    private fun assertSameModel(english: Pipeline, lookup: PersonalLookup, written: Set<Written>, moment: String) {
        for (word in lookup.heldWords()) {
            val expected = if (word.id == WordRows.NEW_WORD) null else word
            assertEquals("$moment, the word ${word.entry.key}", expected, english.engineWord(word.entry.key))
        }
        val keys = english.wordKeys()
        for (context in written) {
            val held = lookup.linksOf(context)
            val engine = english.sliceAfter(context.language, context.previous1, context.previous2, keys)
            assertEquals("$moment, $context", engine, held.copy(links = held.links.map { it.copy(languageSeen = 0L) }))
        }
    }

    /** The links [this] lookup holds after [context], in the order it offers them. */
    private fun PersonalLookup.linksOf(context: Written): PersonalLookup.ContextLinks =
        if (context.previous2 == null) {
            pairsAfter(context.language, context.previous1)
        } else {
            triplesAfter(context.language, context.previous2, context.previous1)
        }

    /** Drops one of the lookup's links after [context], as the clean-up gives one up. */
    private fun dropOne(copy: EngineWorkingCopy, random: Random, context: Written) {
        val links = copy.lookup.linksOf(context).links
        if (links.isEmpty()) {
            return
        }
        val gone = links.random(random)
        if (context.previous2 == null) {
            val pair = UserBigram(context.language, context.previous1, gone.word, gone.count, AT, 0L, 0L)
            copy.lookup.drop(listOf(pair), emptyList())
        } else {
            val triple = UserTrigram(context.language, context.previous2, context.previous1, gone.word, gone.count, AT, 0L, 0L)
            copy.lookup.drop(emptyList(), listOf(triple))
        }
    }

    /**
     * A model with words, a word with no uses left, pairs, triples, their contexts and clocks in
     * both languages, and one more store loaded with one more pair and one more triple than its
     * cap; its contexts go to [written], and the full store's to [pastTheCap].
     */
    private fun loaded(random: Random, written: MutableSet<Written>, pastTheCap: MutableSet<Written>): PersonalModelLoad {
        val words = VOCABULARY.map {
            val capitals = if (random.nextInt(CAPITALISED_ONE_IN) == 0) 1 else 0
            UserWord(spelled(random, it), LanguageTags.primary(ENGLISH), 1 + random.nextInt(USES), AT, deliberateCapitals = capitals)
        } +
            UserWord(UNUSED, LanguageTags.primary(ENGLISH), 0, AT, deliberateCapitals = USES)
        val pairs = LANGUAGES.flatMap { language ->
            List(LOADED_LINKS) {
                val seen = random.nextInt(USES).toLong()
                UserBigram(
                    language, VOCABULARY.random(random), VOCABULARY.random(random), random.nextInt(USES), AT, seen, seen,
                    asserted = if (random.nextInt(CHOSEN_ONE_IN) == 0) 1 else 0,
                )
            }
        }.distinctBy { listOf(it.language, it.previousWord, it.word) }
        val triples = LANGUAGES.flatMap { language ->
            List(LOADED_LINKS) {
                val seen = random.nextInt(USES).toLong()
                UserTrigram(
                    language, VOCABULARY.random(random), VOCABULARY.random(random), VOCABULARY.random(random),
                    random.nextInt(USES), AT, seen, seen,
                )
            }
        }.distinctBy { listOf(it.language, it.previousWord2, it.previousWord1, it.word) }
        val pairContexts = pairs.map { it.language to it.previousWord }.distinct().map { (language, previous) ->
            val total = random.nextInt(CONTEXT_TOTAL).toLong()
            UserPairContext(language, previous, total, AT, random.nextInt(CONTEXT_TOTAL).toLong())
        }
        val tripleContexts = triples.map { Triple(it.language, it.previousWord2, it.previousWord1) }.distinct()
            .map { (language, previous2, previous1) ->
                val total = random.nextInt(CONTEXT_TOTAL).toLong()
                UserTripleContext(language, previous2, previous1, total, AT, random.nextInt(CONTEXT_TOTAL).toLong())
            }
        pairs.forEach { written += Written(it.language, null, it.previousWord) }
        triples.forEach { written += Written(it.language, it.previousWord2, it.previousWord1) }
        // One more pair and one more triple than a store loads.
        val links = PhraseBudget.LANGUAGE_CAP + 1
        val befores = (0 until links / CAP_FOLLOWERS + 1).map { "$CAP_WORD$it" }
        val fullPairs = (0 until links).map {
            UserBigram(CAP_LANGUAGE, befores[it / CAP_FOLLOWERS], "$CAP_WORD${it % CAP_FOLLOWERS}x", 1, AT, 1L, it.toLong())
        }
        val fullTriples = (0 until links).map {
            UserTrigram(
                CAP_LANGUAGE, befores[it / CAP_FOLLOWERS], "$CAP_WORD${it % CAP_FOLLOWERS}x", HUB, 1, AT, 1L, it.toLong(),
            )
        }
        fullPairs.forEach { pastTheCap += Written(CAP_LANGUAGE, null, it.previousWord) }
        fullTriples.forEach { pastTheCap += Written(CAP_LANGUAGE, it.previousWord2, it.previousWord1) }
        val clocks = (LANGUAGES + CAP_LANGUAGE).map {
            UserPhraseClock(it, random.nextInt(CONTEXT_TOTAL).toLong(), random.nextInt(CONTEXT_TOTAL).toLong())
        }
        return PersonalModelLoad(words, pairs + fullPairs, pairContexts, triples + fullTriples, tripleContexts, clocks)
    }

    /** [word], its first letter a capital now and then. */
    private fun spelled(random: Random, word: String): String =
        if (random.nextInt(CAPITALISED_ONE_IN) == 0) word.replaceFirstChar(Char::uppercase) else word

    private companion object {
        const val ENGLISH = "en-US"
        val LANGUAGES = listOf("en", "ro")
        const val SEED = 20261030
        const val COMMITS = 1500
        const val COMPARE_EVERY = 100
        const val AT = 1_000L

        /**
         * How often a commit opens a sentence, follows [HUB], names a word of [UNLOADED],
         * [JUST_BEFORE] or [TWO_BEFORE], is not counted, is a choice or writes a capital, and how
         * often a link is given up.
         */
        const val SENTENCE_START_ONE_IN = 6
        const val HUB_ONE_IN = 3
        const val UNLOADED_ONE_IN = 8
        const val UNCOUNTED_ONE_IN = 7
        const val CHOSEN_ONE_IN = 9
        const val CAPITALISED_ONE_IN = 5
        const val STORE_DROP_ONE_IN = 25

        /** The most uses a loaded word or link has, the most a loaded context counts, and the links loaded per language. */
        const val USES = 6
        const val CONTEXT_TOTAL = 40
        const val LOADED_LINKS = 60

        /** The store loaded past its cap: its language, the words its links are made of, and the followers per context. */
        const val CAP_LANGUAGE = "xx"
        const val CAP_WORD = "capword"
        const val CAP_FOLLOWERS = 100

        /** The word most commits follow, so its context outgrows its budget. */
        const val HUB = "the"

        /** Words the English pack spells, and two it does not, learnable once used enough. */
        val VOCABULARY = listOf(
            "the", "a", "cat", "dog", "house", "garden", "tree", "blue", "red", "green", "run", "walk", "talk", "sing",
            "read", "write", "book", "table", "chair", "door", "window", "street", "city", "river", "water", "fire",
            "light", "night", "day", "morning", "evening", "friend", "family", "school", "work", "home", "car",
            "train", "plane", "music", "zqxwalpha", "zqxwbeta",
        )

        /** A word the load holds with no uses left, a deliberate capital still counted on its row. */
        const val UNUSED = "paper"

        /** Words the English pack spells that the load does not hold: [UNUSED], and words in no loaded row. */
        val UNLOADED = setOf(UNUSED, "bread", "coffee")

        /**
         * Words the English pack spells that are in no loaded row: ones only ever the word just
         * before the one committed, and ones only ever two words before it.
         */
        val JUST_BEFORE = setOf("mountain", "valley")
        val TWO_BEFORE = setOf("ocean", "forest")
    }
}
