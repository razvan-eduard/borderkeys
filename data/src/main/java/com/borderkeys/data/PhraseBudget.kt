// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.theme.KeyboardPreferences

/**
 * How many learned pairs or triples a context and a language keep, and which go when there are
 * more.
 *
 * A context keeps up to [Limits.established] links that reached [Limits.promotion] uses, and
 * [Limits.newcomers] more that have not. Past that, one link goes at a time, never the one just
 * recorded: while the newcomers outnumber their slots, the newcomer seen longest ago; otherwise
 * the established link with the lowest [agedScore], the one seen longest ago among equals. With
 * nothing just recorded, as on an import, every link competes on its aged score.
 *
 * A link's staleness in its context is how many times the context was seen since the link was;
 * in its language, how many links the language recorded since. A language past [LANGUAGE_CAP]
 * pairs or triples is pruned to [LANGUAGE_KEEP] by aged score on that clock.
 */
object PhraseBudget {

    /** One context's limits; see [PhraseBudget]. */
    data class Limits(
        val established: Int,
        val newcomers: Int,
        val promotion: Int,
        val halfLife: Int,
    ) {
        init {
            require(established >= 1 && newcomers >= 1 && promotion >= 1 && halfLife >= 1)
        }

        /** The most links the context keeps. */
        val most: Int get() = established + newcomers
    }

    /** Context observations after which a link's aged score is halved. */
    const val CONTEXT_HALF_LIFE = 100

    /** How many pairs, and how many triples, one language keeps. */
    const val LANGUAGE_CAP = 10_000

    /** The share of [LANGUAGE_CAP] a language past it is pruned to. */
    const val LANGUAGE_KEEP_SHARE = 0.9

    /** How many pairs or triples a language past [LANGUAGE_CAP] is pruned to. */
    val LANGUAGE_KEEP: Int = (LANGUAGE_CAP * LANGUAGE_KEEP_SHARE).toInt()

    /** Links recorded in a language after which a link's aged score is halved there: one per link it keeps. */
    const val LANGUAGE_HALF_LIFE = LANGUAGE_CAP

    /** Established pairs a previous word keeps, and the newcomers it keeps beside them. */
    const val PAIR_ESTABLISHED = 20
    const val PAIR_NEWCOMERS = 4

    /**
     * The most uses a pair or triple may have and still go at the clean-up for holding a word that
     * is not learnable.
     */
    const val PURGE_MOST_USES = 2

    /** Established triples two previous words keep, and the newcomers they keep beside them. */
    const val TRIPLE_ESTABLISHED = 10
    const val TRIPLE_NEWCOMERS = 3

    /** A previous word's pairs. */
    val PAIRS = Limits(
        established = PAIR_ESTABLISHED,
        newcomers = PAIR_NEWCOMERS,
        promotion = KeyboardPreferences.DEFAULT_LEARN_AFTER_USES,
        halfLife = CONTEXT_HALF_LIFE,
    )

    /** Two previous words' triples. */
    val TRIPLES = Limits(
        established = TRIPLE_ESTABLISHED,
        newcomers = TRIPLE_NEWCOMERS,
        promotion = KeyboardPreferences.DEFAULT_LEARN_AFTER_USES,
        halfLife = CONTEXT_HALF_LIFE,
    )

    /** [count] halved for every [halfLife] of [staleness]; a negative staleness is none. */
    fun agedScore(count: Int, staleness: Long, halfLife: Int): Double =
        count * PersonalWordDecay.remaining(staleness.coerceAtLeast(0L).toDouble() / halfLife)

    /**
     * The links of one context that go so that it keeps at most [Limits.most], in the order they
     * go. [kept] is the link recorded in this call, compared by identity, or null; [contextTotal]
     * is how many times the context has been seen; [seenOf] is that total when a link was last
     * seen.
     */
    fun <T> overflow(
        links: List<T>,
        kept: T?,
        contextTotal: Long,
        limits: Limits,
        countOf: (T) -> Int,
        seenOf: (T) -> Long,
    ): List<T> {
        val remaining = links.toMutableList()
        val gone = ArrayList<T>()
        while (remaining.size > limits.most) {
            val at = victimIn(remaining, kept, contextTotal, limits, countOf, seenOf)
            if (at < 0) {
                break
            }
            gone += remaining.removeAt(at)
        }
        return gone
    }

    private fun <T> victimIn(
        links: List<T>,
        kept: T?,
        contextTotal: Long,
        limits: Limits,
        countOf: (T) -> Int,
        seenOf: (T) -> Long,
    ): Int {
        val imported = kept == null
        val newcomers = links.count { countOf(it) < limits.promotion }
        val newcomerGoes = newcomers > limits.newcomers
        var best = -1
        var bestScore = Double.MAX_VALUE
        var bestSeen = Long.MAX_VALUE
        for ((index, link) in links.withIndex()) {
            if (link === kept) {
                continue
            }
            val newcomer = countOf(link) < limits.promotion
            if (!imported && newcomer != newcomerGoes) {
                continue
            }
            // Newcomers compete on staleness alone.
            val score = if (!imported && newcomerGoes) {
                0.0
            } else {
                agedScore(countOf(link), contextTotal - seenOf(link), limits.halfLife)
            }
            val seen = seenOf(link)
            if (score < bestScore || (score == bestScore && seen < bestSeen)) {
                best = index
                bestScore = score
                bestSeen = seen
            }
        }
        return best
    }

    /** How many links a language holding [held] gives up: none up to [LANGUAGE_CAP], else down to [LANGUAGE_KEEP]. */
    fun languageExcess(held: Int): Int = if (held > LANGUAGE_CAP) held - LANGUAGE_KEEP else 0

    /**
     * The [count] links of a language that its prune removes, in the order they go: the lowest
     * [agedScore] on the language's [clock] first, then the one seen longest ago, then the lowest
     * [tieBreak]; never one [isKept] holds. [seenOf] is the clock when a link was last seen.
     */
    fun <T> languageOverflow(
        links: Sequence<T>,
        count: Int,
        clock: Long,
        isKept: (T) -> Boolean,
        countOf: (T) -> Int,
        seenOf: (T) -> Long,
        tieBreak: (T) -> Double,
    ): List<T> {
        if (count <= 0) {
            return emptyList()
        }
        return links
            .filterNot(isKept)
            .map { Scored(it, agedScore(countOf(it), clock - seenOf(it), LANGUAGE_HALF_LIFE), seenOf(it), tieBreak(it)) }
            .sortedWith(compareBy<Scored<T>>({ it.score }, { it.seen }, { it.tie }))
            .take(count)
            .map { it.link }
            .toList()
    }

    private class Scored<T>(val link: T, val score: Double, val seen: Long, val tie: Double)
}
