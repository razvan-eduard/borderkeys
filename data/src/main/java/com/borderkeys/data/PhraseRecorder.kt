// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.dao.UserBigramDao
import com.borderkeys.data.dao.UserPhraseClockDao
import com.borderkeys.data.dao.UserTrigramDao
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext
import com.borderkeys.data.personal.LinkWrites

/**
 * Writes learned pairs and triples, their words as [PhraseKey.word] keys them: the rows a learning
 * flush carries as they hold ([write]), the phrases added by hand, each use adding to the link's
 * count, to its context's total and observations and to its language's clock, and stamping the
 * link with the observations and the clock, and a restore's rows merged ([restore]). Nothing is
 * given up there: [PhraseBudget] runs in the lookup, at a commit, and the clean-up prunes a
 * language left past its cap ([pruneLanguages]). The caller holds the transaction.
 */
class PhraseRecorder(
    private val pairs: UserBigramDao,
    private val triples: UserTrigramDao,
    private val clocks: UserPhraseClockDao,
) {

    private val pairTable = PairTable(pairs)
    private val tripleTable = TripleTable(triples)

    /** Links taken out of the store, so the personal model can drop them as well. */
    data class Dropped(val pairs: List<UserBigram> = emptyList(), val triples: List<UserTrigram> = emptyList())

    /**
     * Writes [writes] as they hold: each pair, triple, context and clock at its value, a link's
     * choices and adds never below the ones the tables hold, each link given up deleted but one the
     * tables hold chosen on purpose, and then a context no link follows any more. A phrase added by
     * hand or a restore may have marked a link here since the personal model was loaded.
     */
    suspend fun write(writes: LinkWrites) {
        writes.pairs.forEach { pairs.upsert(marked(pairTable, it.row()).toBigram()) }
        writes.triples.forEach { triples.upsert(marked(tripleTable, it.row()).toTrigram()) }
        writes.pairContexts.forEach { pairs.upsertContext(it) }
        writes.tripleContexts.forEach { triples.upsertContext(it) }
        writes.clocks.forEach { clocks.upsert(it) }
        if (writes.gonePairs.isNotEmpty() || writes.goneTriples.isNotEmpty()) {
            pairTable.delete(unchosen(pairTable, writes.gonePairs.map { it.row() }))
            tripleTable.delete(unchosen(tripleTable, writes.goneTriples.map { it.row() }))
            pairs.deleteEmptyContexts()
            triples.deleteEmptyContexts()
        }
    }

    /** [link] with its choices and adds raised to the ones [table] holds for it. */
    private suspend fun marked(table: LinkTable, link: LinkRow): LinkRow {
        val held = table.inContext(link.language, link.context).firstOrNull { it.word == link.word } ?: return link
        return link.copy(asserted = maxOf(link.asserted, held.asserted), added = maxOf(link.added, held.added))
    }

    /** The links of [gone] that [table] does not hold as chosen on purpose. */
    private suspend fun unchosen(table: LinkTable, gone: List<LinkRow>): List<LinkRow> =
        gone.filter { link ->
            table.inContext(link.language, link.context).none { it.word == link.word && it.asserted > 0 }
        }

    /**
     * Merges [restoredPairs] and [restoredTriples], read from a backup, with the contexts of
     * [restoredPairContexts] and [restoredTripleContexts] they follow: each link's count, last use,
     * observations stamp and marks the larger of the tables' and the restored, stamped at its
     * language's clock without moving it, and at its context's observations when it carries none;
     * each context's total and observations the largest of the tables', the restored and its links'
     * counts together, its last use the latest. A restored context no restored link follows is left
     * out. Restoring the same rows again changes nothing.
     */
    suspend fun restore(
        restoredPairs: List<UserBigram>,
        restoredPairContexts: List<UserPairContext>,
        restoredTriples: List<UserTrigram>,
        restoredTripleContexts: List<UserTripleContext>,
    ) {
        restore(pairTable, restoredPairs.map { it.row() }, restoredPairContexts.map { it.row() })
        restore(tripleTable, restoredTriples.map { it.row() }, restoredTripleContexts.map { it.row() })
    }

    /** [restore], for the [links] of one [table] and the [restoredContexts] of its kind. */
    private suspend fun restore(table: LinkTable, links: List<LinkRow>, restoredContexts: List<ContextRow>) {
        val carried = restoredContexts.associateBy { it.language to it.context }
        for ((key, restored) in links.groupBy { it.language to it.context }) {
            val (language, context) = key
            val held = table.inContext(language, context).associateBy { it.word }
            val merged = restored.map { link -> held[link.word]?.let { larger(it, link) } ?: link }
            val mergedWords = merged.mapTo(HashSet()) { it.word }
            val uses = (held.values.filter { it.word !in mergedWords } + merged).sumOf { it.count.toLong() }
            val heldContext = table.context(language, context)
            val file = carried[key]
            val seen = maxOf(heldContext?.seen ?: 0L, file?.seen ?: 0L, uses)
            table.upsertContext(
                ContextRow(
                    language, context,
                    total = maxOf(heldContext?.total ?: 0L, file?.total ?: 0L, uses),
                    lastUsedAt = maxOf(
                        heldContext?.lastUsedAt ?: 0L, file?.lastUsedAt ?: 0L, merged.maxOf { it.lastUsedAt },
                    ),
                    seen = seen,
                ),
            )
            val clock = clocks.clock(language)?.let(table::clockIn) ?: 0L
            for (link in merged) {
                val contextSeen = if (link.contextSeen > 0L) link.contextSeen else seen
                table.upsert(link.copy(contextSeen = contextSeen, languageSeen = clock))
            }
        }
    }

    /** [held] and [restored], one link, as a restore merges them: each count and stamp the larger. */
    private fun larger(held: LinkRow, restored: LinkRow) = held.copy(
        count = maxOf(held.count, restored.count),
        lastUsedAt = maxOf(held.lastUsedAt, restored.lastUsedAt),
        contextSeen = maxOf(held.contextSeen, restored.contextSeen),
        asserted = maxOf(held.asserted, restored.asserted),
        added = maxOf(held.added, restored.added),
    )

    /**
     * Takes every pair and triple [word] takes part in out of every language, and their uses out
     * of the contexts they followed; the contexts [word] starts go with them, and so does a context
     * left with no link.
     */
    suspend fun forget(word: String) {
        val key = PhraseKey.word(word)
        forgetWord(pairTable, key)
        forgetWord(tripleTable, key)
    }

    /**
     * Takes [pair] out, with the triples that run through it, and their uses out of their
     * contexts; a context left with no link goes.
     */
    suspend fun forgetPair(pair: UserBigram) {
        takeFromContext(pairTable, pair.row())
        pairs.delete(listOf(pair))
        val through = triples.containingPair(pair.language, pair.previousWord, pair.word)
        through.forEach { takeFromContext(tripleTable, it.row()) }
        triples.delete(through)
        pairs.deleteEmptyContexts()
        triples.deleteEmptyContexts()
    }

    /** Takes [triple] out, and its uses out of its context; a context left with no link goes. */
    suspend fun forgetTriple(triple: UserTrigram) {
        takeFromContext(tripleTable, triple.row())
        triples.delete(listOf(triple))
        triples.deleteEmptyContexts()
    }

    /**
     * Deletes [gonePairs] and [goneTriples], which the clean-up purges; their uses stay in their
     * contexts' totals, and a context left with no link goes.
     */
    suspend fun purge(gonePairs: List<UserBigram>, goneTriples: List<UserTrigram>) {
        pairs.delete(gonePairs)
        triples.delete(goneTriples)
        pairs.deleteEmptyContexts()
        triples.deleteEmptyContexts()
    }

    /**
     * Prunes each language holding more than [PhraseBudget.LANGUAGE_CAP] pairs, or triples, as the
     * lookup prunes one at a commit ([languageGone]): what a restore or a phrase added with no
     * keyboard running left past the cap. A context left with no link goes.
     */
    suspend fun pruneLanguages() {
        prune(pairTable, pairs.all().mapTo(HashSet()) { it.language })
        prune(tripleTable, triples.all().mapTo(HashSet()) { it.language })
    }

    /** [pruneLanguages], for [languages] in [table]. */
    private suspend fun prune(table: LinkTable, languages: Set<String>) {
        var pruned = false
        for (language in languages) {
            if (PhraseBudget.languageExcess(table.countIn(language)) == 0) {
                continue
            }
            val clock = clocks.clock(language)?.let(table::clockIn) ?: 0L
            val totals = table.contextsIn(language).associate { it.context to it.total }
            val gone = languageGone(table.inLanguage(language), totals, clock, null)
            table.delete(gone)
            pruned = pruned || gone.isNotEmpty()
        }
        if (pruned) {
            table.deleteEmptyContexts()
        }
    }

    /** Forgets every pair and triple, with their contexts and clocks. */
    suspend fun forgetAll() {
        pairs.deleteAll()
        pairs.deleteAllContexts()
        triples.deleteAll()
        triples.deleteAllContexts()
        clocks.deleteAll()
    }

    /** Whether [update]'s last two words are one key. */
    private fun followsItself(update: LinkUpdate): Boolean =
        PhraseKey.word(update.context.last()) == PhraseKey.word(update.word)

    /**
     * Takes [key]'s links out of [table], and the uses of those it ends out of their contexts; the
     * contexts [key] is part of go, and so does a context left with no link.
     */
    private suspend fun forgetWord(table: LinkTable, key: String) {
        val gone = table.involving(key)
        gone.filter { key !in it.context }.forEach { takeFromContext(table, it) }
        table.delete(gone)
        table.deleteContextsOf(key)
        table.deleteEmptyContexts()
    }

    /**
     * Adds [learned]'s uses to [table], the language's clock of its kind moving with them. The
     * link's and its context's last use never move back.
     */
    private suspend fun add(table: LinkTable, learned: LinkUpdate): LinkRow {
        val update = learned.copy(context = learned.context.map(PhraseKey::word), word = PhraseKey.word(learned.word))
        val clock = clocks.clock(update.language) ?: UserPhraseClock(update.language, 0L, 0L)
        val tick = table.clockIn(clock) + update.delta
        clocks.upsert(table.withClock(clock, tick))
        val context = table.context(update.language, update.context)
        val seen = (context?.seen ?: 0L) + update.delta
        table.upsertContext(
            ContextRow(
                update.language, update.context, (context?.total ?: 0L) + update.delta,
                maxOf(context?.lastUsedAt ?: 0L, update.lastUsedAt), seen,
            ),
        )
        val held = table.inContext(update.language, update.context).firstOrNull { it.word == update.word }
        val recorded = LinkRow(
            update.language, update.context, update.word, (held?.count ?: 0) + update.delta,
            maxOf(held?.lastUsedAt ?: 0L, update.lastUsedAt), contextSeen = seen, languageSeen = tick,
            asserted = (held?.asserted ?: 0) + update.asserted, added = (held?.added ?: 0) + update.added,
        )
        table.upsert(recorded)
        return recorded
    }

    /**
     * Records [words] as a phrase added by hand in [language] at [now]: the pair of two words, or
     * the two pairs and the triple of three, each one use chosen on purpose and added by hand; a
     * pair of one key twice is left out.
     */
    suspend fun addPhrase(language: String, words: List<String>, now: Long) {
        val pairs = words.zipWithNext { previous, word -> addedUpdate(language, listOf(previous), word, now) }
        val triples = if (words.size == UserPhrase.TRIPLE_WORDS) {
            listOf(addedUpdate(language, words.take(UserPhrase.PAIR_WORDS), words.last(), now))
        } else {
            emptyList()
        }
        recordAdded(pairTable, pairs)
        recordAdded(tripleTable, triples)
    }

    /** One use of [word] after [context] in [language] at [now], chosen on purpose and added by hand. */
    private fun addedUpdate(language: String, context: List<String>, word: String, now: Long) =
        LinkUpdate(language, context, word, delta = 1, lastUsedAt = now, asserted = 1, added = 1)

    /** Records [updates], added by hand, in [table] one at a time, each moving the language's clock. */
    private suspend fun recordAdded(table: LinkTable, updates: List<LinkUpdate>) {
        updates.filterNot(::followsItself).forEach { add(table, it) }
    }

    /** Takes [link]'s uses off its context's total and observations in [table]. */
    private suspend fun takeFromContext(table: LinkTable, link: LinkRow) {
        val context = table.context(link.language, link.context) ?: return
        table.upsertContext(
            context.copy(
                total = (context.total - link.count).coerceAtLeast(0L),
                seen = (context.seen - link.count).coerceAtLeast(0L),
            ),
        )
    }
}
