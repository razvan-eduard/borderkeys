// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.PhraseMigration.StoredLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseMigrationTest {

    private val locales = mapOf(
        "să" to "ro-RO", "acasă" to "ro-RO", "Friday" to "en-US", "night" to "en-US", "the" to "en-US",
    )

    @Test
    fun `a link takes the language of its last word, else the one most words have`() {
        val rows = PhraseMigration.convert(
            locales,
            listOf(StoredLink(listOf("vreau", "să"), 2, 10L), StoredLink(listOf("x", "unheard"), 1, 10L)),
            emptyList(),
            FALLBACK_LANGUAGE,
        )
        assertEquals(mapOf("să" to "ro", "unheard" to "en"), rows.pairs.associate { it.word to it.language })
    }

    @Test
    fun `case forms of one link meet, their counts added, the latest use kept`() {
        val rows = PhraseMigration.convert(
            locales,
            listOf(StoredLink(listOf("The", "Friday"), 2, 10L), StoredLink(listOf("the", "Friday"), 3, 20L)),
            listOf(
                StoredLink(listOf("On", "the", "Friday"), 1, 5L),
                StoredLink(listOf("on", "the", "friday"), 4, 7L),
            ),
            FALLBACK_LANGUAGE,
        )
        val pair = rows.pairs.single()
        assertEquals(listOf("en", "the", "friday", "5", "20"),
            listOf(pair.language, pair.previousWord, pair.word, "${pair.count}", "${pair.lastUsedAt}"))
        assertEquals(5, rows.triples.single().count)
    }

    @Test
    fun `totals and clocks are the sums, and every link is stamped as just seen`() {
        val rows = PhraseMigration.convert(
            locales,
            listOf(
                StoredLink(listOf("vreau", "să"), 3, 10L),
                StoredLink(listOf("vreau", "acasă"), 1, 30L),
                StoredLink(listOf("the", "night"), 2, 10L),
            ),
            listOf(StoredLink(listOf("good", "the", "night"), 2, 10L)),
            FALLBACK_LANGUAGE,
        )
        val vreau = rows.pairContexts.single { it.previousWord == "vreau" }
        assertEquals(4L, vreau.total)
        assertEquals(30L, vreau.lastUsedAt)
        assertEquals(setOf(4L), rows.pairs.filter { it.language == "ro" }.map { it.contextSeen }.toSet())
        assertEquals(setOf(4L), rows.pairs.filter { it.language == "ro" }.map { it.languageSeen }.toSet())
        assertEquals(
            listOf("en" to (2L to 2L), "ro" to (4L to 0L)),
            rows.clocks.map { it.language to (it.pairs to it.triples) },
        )
        assertEquals(2L, rows.tripleContexts.single().total)
    }

    @Test
    fun `a context past its limit keeps its most used`() {
        val most = PhraseBudget.PAIRS.most
        val links = (0 until most + 3).map { StoredLink(listOf("the", "w$it"), it + 1, 10L) }
        val rows = PhraseMigration.convert(locales, links, emptyList(), FALLBACK_LANGUAGE)
        assertEquals(most, rows.pairs.size)
        assertEquals(setOf("w0", "w1", "w2"), links.map { it.words[1] }.toSet() - rows.pairs.map { it.word }.toSet())
        assertEquals(
            "the total counts what was given up",
            links.sumOf { it.count }.toLong(),
            rows.pairContexts.single().total,
        )
    }

    @Test
    fun `a language past its cap keeps nine in ten`() {
        val links = (0..PhraseBudget.LANGUAGE_CAP).map { StoredLink(listOf("w$it", "the"), 1 + it % 3, 10L) }
        val rows = PhraseMigration.convert(locales, links, emptyList(), FALLBACK_LANGUAGE)
        assertEquals(PhraseBudget.LANGUAGE_KEEP, rows.pairs.size)
    }

    @Test
    fun `a context's observations start at its uses`() {
        val rows = PhraseMigration.convert(
            locales,
            listOf(StoredLink(listOf("vreau", "să"), 3, 10L)),
            listOf(StoredLink(listOf("eu", "vreau", "să"), 2, 10L)),
            FALLBACK_LANGUAGE,
        )
        assertEquals(rows.pairContexts.map { it.total }, rows.pairContexts.map { it.seen })
        assertEquals(rows.tripleContexts.map { it.total }, rows.tripleContexts.map { it.seen })
    }

    @Test
    fun `no context is kept that the prune left without a link`() {
        val links = (0..PhraseBudget.LANGUAGE_CAP).map { StoredLink(listOf("w$it", "the"), 1, 10L) }
        val rows = PhraseMigration.convert(locales, links, emptyList(), FALLBACK_LANGUAGE)
        assertEquals(
            rows.pairs.map { it.language to it.previousWord }.toSet(),
            rows.pairContexts.map { it.language to it.previousWord }.toSet(),
        )
    }

    @Test
    fun `a link whose last two words are one is left out`() {
        val rows = PhraseMigration.convert(
            locales,
            listOf(StoredLink(listOf("very", "Very"), 2, 10L), StoredLink(listOf("the", "night"), 1, 10L)),
            listOf(StoredLink(listOf("so", "very", "VERY"), 1, 10L)),
            FALLBACK_LANGUAGE,
        )
        assertEquals(listOf("night"), rows.pairs.map { it.word })
        assertTrue(rows.triples.isEmpty())
        assertEquals(listOf("the"), rows.pairContexts.map { it.previousWord })
    }

    @Test
    fun `a link no word's locale names a language for takes the fallback`() {
        val rows = PhraseMigration.convert(
            emptyMap(),
            listOf(StoredLink(listOf("vreau", "să"), 1, 10L)),
            listOf(StoredLink(listOf("eu", "vreau", "să"), 1, 10L)),
            FALLBACK_LANGUAGE,
        )
        assertEquals(listOf(FALLBACK_LANGUAGE), rows.pairs.map { it.language })
        assertEquals(listOf(FALLBACK_LANGUAGE), rows.triples.map { it.language })
    }

    private companion object {
        /** A language no word in [locales] was learned under. */
        const val FALLBACK_LANGUAGE = "de"
    }
}
