// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.LinkRow
import com.borderkeys.data.PhraseBudget
import com.borderkeys.data.PhraseKey
import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedTrigram
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.dao.PairStep
import com.borderkeys.data.dao.TripleStep
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext
import com.borderkeys.data.languageGone
import com.borderkeys.data.toBigram
import com.borderkeys.data.toTrigram
import com.borderkeys.typing.LearnOutcome

/** The learned pairs and triples, handed to the engine at a handle as its natives take them. */
internal object PersonalLinks {

    /**
     * Replaces the engine's pairs and triples with [pairs] and [triples], each in its language's
     * store with its stamps, then sets their contexts' totals and observations from [pairContexts]
     * and [tripleContexts], and each language's clocks from [clocks].
     */
    fun load(
        handle: Long,
        pairs: List<UserBigram>,
        pairContexts: List<UserPairContext>,
        triples: List<UserTrigram>,
        tripleContexts: List<UserTripleContext>,
        clocks: List<UserPhraseClock>,
    ) {
        NativePredictor.nativeLoadUserBigrams(
            handle,
            Array(pairs.size) { pairs[it].language },
            Array(pairs.size) { pairs[it].previousWord },
            Array(pairs.size) { pairs[it].word },
            IntArray(pairs.size) { pairs[it].count },
            LongArray(pairs.size) { pairs[it].contextSeen },
            LongArray(pairs.size) { pairs[it].languageSeen },
        )
        if (pairContexts.isNotEmpty()) {
            NativePredictor.nativeLoadUserPairTotals(
                handle,
                Array(pairContexts.size) { pairContexts[it].language },
                Array(pairContexts.size) { pairContexts[it].previousWord },
                LongArray(pairContexts.size) { pairContexts[it].total },
                LongArray(pairContexts.size) { pairContexts[it].seen },
            )
        }
        NativePredictor.nativeLoadUserTrigrams(
            handle,
            Array(triples.size) { triples[it].language },
            Array(triples.size) { triples[it].previousWord2 },
            Array(triples.size) { triples[it].previousWord1 },
            Array(triples.size) { triples[it].word },
            IntArray(triples.size) { triples[it].count },
            LongArray(triples.size) { triples[it].contextSeen },
            LongArray(triples.size) { triples[it].languageSeen },
        )
        if (tripleContexts.isNotEmpty()) {
            NativePredictor.nativeLoadUserTripleTotals(
                handle,
                Array(tripleContexts.size) { tripleContexts[it].language },
                Array(tripleContexts.size) { tripleContexts[it].previousWord2 },
                Array(tripleContexts.size) { tripleContexts[it].previousWord1 },
                LongArray(tripleContexts.size) { tripleContexts[it].total },
                LongArray(tripleContexts.size) { tripleContexts[it].seen },
            )
        }
        NativePredictor.nativeLoadUserClocks(
            handle,
            Array(clocks.size) { clocks[it].language },
            LongArray(clocks.size) { clocks[it].pairs },
            LongArray(clocks.size) { clocks[it].triples },
        )
    }

    /** Drops [pairs] and [triples] from their languages' stores; their contexts keep their uses. */
    fun drop(handle: Long, pairs: List<UserBigram>, triples: List<UserTrigram>) {
        if (pairs.isNotEmpty()) {
            NativePredictor.nativeDropUserPairs(
                handle,
                Array(pairs.size) { pairs[it].language },
                Array(pairs.size) { pairs[it].previousWord },
                Array(pairs.size) { pairs[it].word },
            )
        }
        if (triples.isNotEmpty()) {
            NativePredictor.nativeDropUserTriples(
                handle,
                Array(triples.size) { triples[it].language },
                Array(triples.size) { triples[it].previousWord2 },
                Array(triples.size) { triples[it].previousWord1 },
                Array(triples.size) { triples[it].word },
            )
        }
    }

    /**
     * Learns [update] after [previous1] and [previous2] in [language]'s store, then runs
     * [PhraseBudget] on the pair and then the triple it recorded: each one's context keeps its
     * limits and its store is pruned past [PhraseBudget.LANGUAGE_CAP], the link just recorded
     * never going. An update of no uses is not counted; its pair and triple are learned. Returns
     * what was recorded and dropped.
     */
    fun learn(handle: Long, update: LearnedWord, previous1: String?, previous2: String?, language: String): LearnOutcome {
        val learned = NativePredictor.nativeLearn(
            handle, update.word, previous1, previous2, update.deliberateCapital, update.asserted, language,
            update.delta > 0,
        )
        val word = PhraseKey.word(update.word)
        val before1 = previous1?.let(PhraseKey::word) ?: return LearnOutcome(emptyList(), emptyList())
        val pair = if (learned and NativePredictor.LEARNED_PAIR != 0) {
            budget(handle, language, null, before1, word, PhraseBudget.PAIRS)
        } else {
            null
        }?.let { dropped ->
            PairStep(LearnedBigram(language, before1, word, 1, update.lastUsedAt), dropped.map { it.toBigram() })
        }
        val triple = previous2?.let(PhraseKey::word)?.let { before2 ->
            if (learned and NativePredictor.LEARNED_TRIPLE != 0) {
                budget(handle, language, before2, before1, word, PhraseBudget.TRIPLES)
            } else {
                null
            }?.let { dropped ->
                TripleStep(
                    LearnedTrigram(language, before2, before1, word, 1, update.lastUsedAt),
                    dropped.map { it.toTrigram() },
                )
            }
        }
        return LearnOutcome(listOfNotNull(pair), listOfNotNull(triple))
    }

    /**
     * Runs the budget on the link of [word] after [previous2] and [previous1] (pairs when
     * [previous2] is null) in [language]'s store, just recorded: its context keeps [limits], then
     * the store is pruned. Drops the links that go and returns them, in order; null when the store
     * holds no such link.
     */
    private fun budget(
        handle: Long,
        language: String,
        previous2: String?,
        previous1: String,
        word: String,
        limits: PhraseBudget.Limits,
    ): List<LinkRow>? {
        val context = listOfNotNull(previous2, previous1)
        val counted = LongArray(NativePredictor.CONTEXT_SLOTS)
        val held = NativePredictor.nativeContextLinks(
            handle, language, previous2, previous1, emptyArray(), IntArray(0), LongArray(0), counted,
        )
        val words = arrayOfNulls<String>(held)
        val counts = IntArray(held)
        val seen = LongArray(held)
        NativePredictor.nativeContextLinks(handle, language, previous2, previous1, words, counts, seen, counted)
        val links = (0 until held).mapNotNull { at ->
            words[at]?.let { LinkRow(language, context, it, counts[at], 0L, seen[at], 0L) }
        }
        val kept = links.firstOrNull { it.word == word } ?: return null
        val triples = previous2 != null
        val gone = PhraseBudget.overflow(
            links, kept, counted[NativePredictor.CONTEXT_SEEN], limits, { it.count }, { it.contextSeen },
        )
        drop(handle, gone, triples)
        val pruned = prune(handle, language, kept, triples)
        drop(handle, pruned, triples)
        return gone + pruned
    }

    /**
     * The links of [language]'s triples, or pairs, its prune removes once the store holds more
     * than [PhraseBudget.LANGUAGE_CAP] ([languageGone]), on the clock [kept] was just stamped with;
     * never [kept].
     */
    private fun prune(handle: Long, language: String, kept: LinkRow, triples: Boolean): List<LinkRow> {
        val held = NativePredictor.nativeLinksHeld(handle, language, triples)
        if (PhraseBudget.languageExcess(held) == 0) {
            return emptyList()
        }
        val previous2 = arrayOfNulls<String>(held)
        val previous1 = arrayOfNulls<String>(held)
        val words = arrayOfNulls<String>(held)
        val counts = IntArray(held)
        val languageSeen = LongArray(held)
        val totals = LongArray(held)
        val written = NativePredictor.nativeLanguageLinks(
            handle, language, triples, previous2, previous1, words, counts, languageSeen, totals,
        )
        val contextTotals = HashMap<List<String>, Long>()
        val links = (0 until written).mapNotNull { at ->
            val before1 = previous1[at] ?: return@mapNotNull null
            val word = words[at] ?: return@mapNotNull null
            val context = if (triples) listOf(previous2[at] ?: return@mapNotNull null, before1) else listOf(before1)
            contextTotals[context] = totals[at]
            LinkRow(language, context, word, counts[at], 0L, 0L, languageSeen[at])
        }
        val clock = links.firstOrNull { it.context == kept.context && it.word == kept.word }?.languageSeen ?: 0L
        return languageGone(links, contextTotals, clock, kept)
    }

    /** Drops [links], triples or pairs, from the engine. */
    private fun drop(handle: Long, links: List<LinkRow>, triples: Boolean) {
        if (triples) {
            drop(handle, emptyList(), links.map { it.toTrigram() })
        } else {
            drop(handle, links.map { it.toBigram() }, emptyList())
        }
    }
}
