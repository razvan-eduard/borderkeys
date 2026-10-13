// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.PersonalRows
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext
import com.borderkeys.data.entity.UserWord
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.ime.OffensiveWords
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/** What [PersonalModelLoad.of] makes of the rows the personal dictionary gives the loader. */
class PersonalModelLoadTest {

    private val rows = PersonalRows(
        words = listOf(word("Damn"), word("nice")),
        pairs = listOf(pair("damn", "it"), pair("so", "damn"), pair("so", "nice")),
        pairContexts = listOf(UserPairContext(LANGUAGE, "damn", TOTAL, NOW, SEEN)),
        triples = listOf(
            triple("damn", "it", "all"),
            triple("oh", "damn", "it"),
            triple("oh", "so", "damn"),
            triple("oh", "so", "nice"),
        ),
        tripleContexts = listOf(UserTripleContext(LANGUAGE, "oh", "damn", TOTAL, NOW, SEEN)),
        clocks = listOf(UserPhraseClock(LANGUAGE, SEEN, SEEN)),
    )

    @Test
    fun `with the switch on, a word, a pair or a triple holding an offensive word in any case stays out`() {
        val model = PersonalModelLoad.of(rows, KeyboardPreferences(blockOffensiveWords = true), OFFENSIVE, NOW)
        assertEquals(listOf("nice"), model.words.map { it.word })
        assertEquals(listOf("so nice"), model.pairs.map { "${it.previousWord} ${it.word}" })
        assertEquals(listOf("oh so nice"), model.triples.map { "${it.previousWord2} ${it.previousWord1} ${it.word}" })
        assertEquals(rows.pairContexts, model.pairContexts)
        assertEquals(rows.tripleContexts, model.tripleContexts)
        assertEquals(rows.clocks, model.clocks)
    }

    @Test
    fun `with the switch off, nothing stays out`() {
        val model = PersonalModelLoad.of(rows, KeyboardPreferences(blockOffensiveWords = false), OFFENSIVE, NOW)
        assertEquals(
            PersonalModelLoad(rows.words, rows.pairs, rows.pairContexts, rows.triples, rows.tripleContexts, rows.clocks),
            model,
        )
    }

    @Test
    fun `every count and context total fades by the half-life set, the clocks as they are`() {
        val halfLife = TimeUnit.DAYS.toMillis(HALF_LIFE_DAYS.toLong())
        val unused = NOW - halfLife
        val stale = PersonalRows(
            words = listOf(UserWord("nice", LANGUAGE, COUNT, unused)),
            pairs = listOf(UserBigram(LANGUAGE, "so", "nice", COUNT, unused, SEEN, SEEN)),
            pairContexts = listOf(UserPairContext(LANGUAGE, "so", TOTAL, unused, SEEN)),
            triples = listOf(UserTrigram(LANGUAGE, "oh", "so", "nice", COUNT, unused, SEEN, SEEN)),
            tripleContexts = listOf(UserTripleContext(LANGUAGE, "oh", "so", TOTAL, unused, SEEN)),
            clocks = listOf(UserPhraseClock(LANGUAGE, SEEN, SEEN)),
        )
        val settings = KeyboardPreferences(unlearnHalfLifeDays = HALF_LIFE_DAYS)
        val model = PersonalModelLoad.of(stale, settings, emptySet(), NOW)
        assertEquals(listOf(COUNT / 2), model.words.map { it.count })
        assertEquals(listOf(COUNT / 2), model.pairs.map { it.count })
        assertEquals(listOf(TOTAL / 2), model.pairContexts.map { it.total })
        assertEquals(listOf(COUNT / 2), model.triples.map { it.count })
        assertEquals(listOf(TOTAL / 2), model.tripleContexts.map { it.total })
        assertEquals(stale.clocks, model.clocks)
    }

    private fun word(text: String) = UserWord(text, LANGUAGE, COUNT, NOW)

    private fun pair(previous: String, word: String) = UserBigram(LANGUAGE, previous, word, COUNT, NOW, SEEN, SEEN)

    private fun triple(previous2: String, previous1: String, word: String) =
        UserTrigram(LANGUAGE, previous2, previous1, word, COUNT, NOW, SEEN, SEEN)

    private companion object {
        const val LANGUAGE = "en"
        const val NOW = 1_000_000_000_000L
        const val COUNT = 8
        const val TOTAL = 40L
        const val SEEN = 5L

        /** One of [KeyboardPreferences.unlearnHalfLifeDays]' stops other than its default. */
        const val HALF_LIFE_DAYS = 30

        /** The list as the loader holds it: folded. */
        val OFFENSIVE = OffensiveWords.parse("damn\n")
    }
}
