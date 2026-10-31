// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.personal

import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext
import org.junit.Assert.assertEquals
import org.junit.Test

/** The rows a flush writes, gathered over commits ([LinkWrites.then]) and with a word forgotten ([LinkWrites.without]). */
class LinkWritesTest {

    private fun pair(previous: String, word: String, count: Int) = UserBigram("en", previous, word, count, 1L, 0L, 0L)

    @Test
    fun `later writes replace earlier ones by key, and a link written after it was given up is written again`() {
        val first = LinkWrites(
            pairs = listOf(pair("the", "cat", 1), pair("the", "dog", 1)),
            pairContexts = listOf(UserPairContext("en", "the", 2L, 1L, 2L)),
            clocks = listOf(UserPhraseClock("en", 2L, 0L)),
            gonePairs = listOf(pair("the", "fox", 1)),
        )
        val later = LinkWrites(
            pairs = listOf(pair("the", "cat", 2), pair("the", "fox", 1)),
            pairContexts = listOf(UserPairContext("en", "the", 4L, 2L, 4L)),
            clocks = listOf(UserPhraseClock("en", 4L, 0L)),
            gonePairs = listOf(pair("the", "dog", 1)),
        )
        assertEquals(
            LinkWrites(
                pairs = listOf(pair("the", "cat", 2), pair("the", "fox", 1)),
                pairContexts = listOf(UserPairContext("en", "the", 4L, 2L, 4L)),
                clocks = listOf(UserPhraseClock("en", 4L, 0L)),
                gonePairs = listOf(pair("the", "dog", 1)),
            ),
            first.then(later),
        )
    }

    @Test
    fun `writes without a word leave out its links and the contexts they follow, and keep the links given up`() {
        val writes = LinkWrites(
            pairs = listOf(pair("the", "cat", 1), pair("a", "dog", 1), pair("cat", "sat", 1)),
            triples = listOf(UserTrigram("en", "on", "the", "Cat", 1, 1L, 0L, 0L), UserTrigram("en", "on", "a", "dog", 1, 1L, 0L, 0L)),
            pairContexts = listOf(
                UserPairContext("en", "the", 1L, 1L, 1L), UserPairContext("en", "a", 1L, 1L, 1L),
                UserPairContext("en", "cat", 1L, 1L, 1L),
            ),
            tripleContexts = listOf(UserTripleContext("en", "on", "the", 1L, 1L, 1L), UserTripleContext("en", "on", "a", 1L, 1L, 1L)),
            gonePairs = listOf(pair("the", "cat", 1)),
        ).copy(triples = listOf(UserTrigram("en", "on", "the", "cat", 1, 1L, 0L, 0L), UserTrigram("en", "on", "a", "dog", 1, 1L, 0L, 0L)))
        val left = writes.without("Cat")
        assertEquals(listOf(pair("a", "dog", 1)), left.pairs)
        assertEquals(listOf("dog"), left.triples.map { it.word })
        assertEquals(listOf("a"), left.pairContexts.map { it.previousWord })
        assertEquals(listOf("a"), left.tripleContexts.map { it.previousWord1 })
        assertEquals(listOf(pair("the", "cat", 1)), left.gonePairs)
    }
}
