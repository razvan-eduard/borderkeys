// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseBudgetTest {

    private class Link(val name: String, val count: Int, val seen: Long, val asserted: Boolean = false)

    private val limits = PhraseBudget.Limits(established = 3, newcomers = 2, promotion = 3, halfLife = 10)

    private fun overflow(links: List<Link>, kept: Link?, total: Long) =
        PhraseBudget.overflow(links, kept, total, limits, { it.count }, { it.seen }, { it.asserted }).map { it.name }

    @Test
    fun `a context within its limit keeps everything`() {
        val links = listOf(Link("a", 5, 10), Link("b", 1, 11), Link("c", 4, 12))
        assertTrue(overflow(links, links[1], 12).isEmpty())
    }

    @Test
    fun `the link just recorded is never the one that goes`() {
        val established = (1..3).map { Link("e$it", 9, 20) }
        val newcomers = listOf(Link("n1", 1, 18), Link("n2", 1, 19))
        val recorded = Link("new", 1, 21)
        val gone = overflow(established + newcomers + recorded, recorded, 21)
        assertEquals(listOf("n1"), gone)
    }

    @Test
    fun `two newcomers typed in turn both survive while established links make room`() {
        val established = listOf(Link("strong", 9, 30), Link("weak", 3, 2), Link("mid", 6, 29))
        val first = Link("first", 1, 31)
        val second = Link("second", 1, 32)
        val extra = Link("extra", 4, 28)
        val gone = overflow(established + extra + first + second, second, 32)
        assertEquals("the weakest established link goes, not a newcomer", listOf("weak"), gone)
    }

    @Test
    fun `an established link not seen for a long while yields to a fresh one`() {
        val stale = Link("stale", 8, 0)
        val fresh = Link("fresh", 4, 99)
        val others = listOf(Link("a", 9, 98), Link("b", 9, 97))
        val recorded = Link("recorded", 1, 100)
        val newcomer = Link("newcomer", 1, 96)
        val gone = overflow(listOf(stale, fresh) + others + newcomer + recorded, recorded, 100)
        assertEquals(listOf("stale"), gone)
        assertTrue(
            "eight uses ten half-lives ago score below four uses now",
            PhraseBudget.agedScore(8, 100, limits.halfLife) < PhraseBudget.agedScore(4, 1, limits.halfLife),
        )
    }

    @Test
    fun `with nothing just recorded every link competes on its aged score`() {
        val links = listOf(
            Link("a", 9, 50), Link("b", 9, 50), Link("c", 9, 50),
            Link("d", 1, 50), Link("e", 1, 49), Link("f", 2, 50),
        )
        assertEquals(listOf("e"), overflow(links, null, 50))
    }

    @Test
    fun `equal scores go to the one seen longest ago`() {
        val halfLife = limits.halfLife
        val halving = PersonalWordDecay.HALVING
        assertEquals(1.0, PhraseBudget.agedScore(halving, halfLife.toLong(), halfLife), 0.0)
        assertEquals(halving.toDouble(), PhraseBudget.agedScore(halving, -halfLife.toLong(), halfLife), 0.0)
        val clock = halfLife.toLong()
        val recent = Link("recent", 1, clock)
        val older = Link("older", halving, clock - halfLife)
        assertEquals(
            PhraseBudget.agedScore(recent.count, clock - recent.seen, halfLife),
            PhraseBudget.agedScore(older.count, clock - older.seen, halfLife),
            0.0,
        )
        val two = PhraseBudget.Limits(established = 1, newcomers = 1, promotion = limits.promotion, halfLife = halfLife)
        val gone = PhraseBudget.overflow(
            listOf(recent, older, Link("established", limits.promotion, clock)), null, clock, two,
            { it.count }, { it.seen }, { it.asserted },
        )
        assertEquals(listOf("older"), gone.map { it.name })
    }

    @Test
    fun `a link chosen on purpose is never the victim, as a newcomer or among established links`() {
        val established = (1..3).map { Link("e$it", 9, 20) }
        val chosen = Link("chosen", 1, 10, asserted = true)
        val newcomers = listOf(Link("n1", 1, 19), Link("n2", 1, 20))
        val recorded = Link("new", 1, 21)
        assertEquals(listOf("n1"), overflow(established + chosen + newcomers + recorded, recorded, 21))
        val chosenAll = (1..6).map { Link("c$it", 1, it.toLong(), asserted = true) }
        assertEquals(emptyList<String>(), overflow(chosenAll, null, 6))
    }

    @Test
    fun `links chosen on purpose are not counted against the context's limit`() {
        val established = (1..5).map { Link("e$it", 9, 20) }
        val chosen = (1..2).map { Link("c$it", 1, 10, asserted = true) }
        assertEquals(emptyList<String>(), overflow(established + chosen, established[0], 21))
        val oneMore = established + Link("e6", 9, 21) + chosen
        assertEquals(1, overflow(oneMore, oneMore[5], 21).size)
    }

    @Test
    fun `chosen newcomers do not count against the newcomers' places`() {
        val established = (1..4).map { Link("e$it", 9, 20) }
        val chosen = (1..3).map { Link("c$it", 1, 10, asserted = true) }
        val newcomer = Link("n1", 1, 19)
        val recorded = Link("new", 1, 21)
        val gone = overflow(established + chosen + newcomer + recorded, recorded, 21)
        assertTrue(gone.toString(), gone.single() in established.map { it.name })
    }

    @Test
    fun `a language's cap counts the links not chosen on purpose`() {
        val unchosen = (1..PhraseBudget.LANGUAGE_CAP).map { row("w$it", asserted = 0) }
        val chosen = row("chosen", asserted = 1)
        assertEquals(emptyList<LinkRow>(), languageGone(unchosen + chosen, emptyMap(), clock = 0L, kept = null))
        val oneMore = unchosen + row("more", asserted = 0) + chosen
        val gone = languageGone(oneMore, emptyMap(), clock = 0L, kept = null)
        assertEquals(PhraseBudget.LANGUAGE_CAP + 1 - PhraseBudget.LANGUAGE_KEEP, gone.size)
        assertTrue(gone.none { it.asserted > 0 })
    }

    private fun row(word: String, asserted: Int) = LinkRow("en", listOf("x"), word, 1, 0L, 0L, 0L, asserted = asserted)

    @Test
    fun `a language prune takes the lowest aged scores and never the kept link`() {
        val links = sequenceOf(
            Link("old", 10, 0),
            Link("recent", 10, PhraseBudget.LANGUAGE_HALF_LIFE.toLong()),
            Link("kept", 1, 0),
            Link("rare", 1, PhraseBudget.LANGUAGE_HALF_LIFE.toLong()),
        )
        val gone = PhraseBudget.languageOverflow(
            links, 2, PhraseBudget.LANGUAGE_HALF_LIFE.toLong(),
            isKept = { it.name == "kept" }, countOf = { it.count }, seenOf = { it.seen }, tieBreak = { 0.0 },
            assertedOf = { it.asserted },
        )
        assertEquals(listOf("rare", "old"), gone.map { it.name })
    }

    @Test
    fun `a language prune never takes a link chosen on purpose`() {
        val links = sequenceOf(
            Link("chosen", 1, 0, asserted = true),
            Link("old", 10, 0),
            Link("rare", 1, PhraseBudget.LANGUAGE_HALF_LIFE.toLong()),
        )
        val gone = PhraseBudget.languageOverflow(
            links, 2, PhraseBudget.LANGUAGE_HALF_LIFE.toLong(),
            isKept = { false }, countOf = { it.count }, seenOf = { it.seen }, tieBreak = { 0.0 },
            assertedOf = { it.asserted },
        )
        assertEquals(listOf("rare", "old"), gone.map { it.name })
    }

    @Test
    fun `a language gives up nothing up to its cap, and down to what it keeps past it`() {
        val past = PhraseBudget.LANGUAGE_CAP + 1
        assertEquals(0, PhraseBudget.languageExcess(PhraseBudget.LANGUAGE_CAP))
        assertEquals(past - PhraseBudget.LANGUAGE_KEEP, PhraseBudget.languageExcess(past))
    }

    @Test
    fun `a context keeps its established links and its newcomers`() {
        assertEquals(PhraseBudget.PAIR_ESTABLISHED + PhraseBudget.PAIR_NEWCOMERS, PhraseBudget.PAIRS.most)
        assertEquals(PhraseBudget.TRIPLE_ESTABLISHED + PhraseBudget.TRIPLE_NEWCOMERS, PhraseBudget.TRIPLES.most)
    }
}
