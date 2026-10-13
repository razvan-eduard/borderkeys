// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedTrigram
import com.borderkeys.data.dao.PairStep
import com.borderkeys.data.dao.TripleStep
import com.borderkeys.data.dao.UserBigramDao
import com.borderkeys.data.dao.UserPhraseClockDao
import com.borderkeys.data.dao.UserTrigramDao
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext

/**
 * Writes learned pairs and triples under [PhraseBudget], their words as [PhraseKey.word] keys
 * them. Each use adds to the link's count, to its context's total and observations and to its
 * language's clock, and stamps the link with the observations and the clock; an import stamps at
 * the clock without moving it. Then the links the engine dropped go, the context and the language
 * give up what no longer fits, and a context no link follows any more goes. The caller holds the
 * transaction.
 */
class PhraseRecorder(
    private val pairs: UserBigramDao,
    private val triples: UserTrigramDao,
    private val clocks: UserPhraseClockDao,
) {

    private val pairTable = PairTable(pairs)
    private val tripleTable = TripleTable(triples)

    /** Links taken out of the store, so the engine can drop them as well. */
    data class Dropped(val pairs: List<UserBigram> = emptyList(), val triples: List<UserTrigram> = emptyList()) {
        operator fun plus(other: Dropped) = Dropped(pairs + other.pairs, triples + other.triples)
    }

    /**
     * Replays [steps] as the engine took them: each use is recorded, the pairs the engine dropped
     * right after it go, and the budget then runs on what the store holds, the pair just recorded
     * never giving up its place. Returns the pairs the budget gave up beyond the engine's.
     */
    suspend fun recordPairs(steps: List<PairStep>): List<UserBigram> =
        record(pairTable, steps.map { it.step() }).map { it.toBigram() }

    /** [recordPairs], for triples. */
    suspend fun recordTriples(steps: List<TripleStep>): List<UserTrigram> =
        record(tripleTable, steps.map { it.step() }).map { it.toTrigram() }

    /**
     * Adds [pairUpdates] and [tripleUpdates] as an import does: nothing was just recorded, so
     * once all are in, every context and language they touched keeps its links by aged score. A
     * pair whose two words are one key, and a triple whose last two are, are left out. A context of
     * [carriedPairContexts] or [carriedTripleContexts] that an imported link follows first gains the
     * uses its total and observations hold beyond those links, so it gains its carried total.
     */
    suspend fun importLinks(
        pairUpdates: List<LearnedBigram>,
        tripleUpdates: List<LearnedTrigram>,
        carriedPairContexts: List<UserPairContext> = emptyList(),
        carriedTripleContexts: List<UserTripleContext> = emptyList(),
    ): Dropped {
        val droppedPairs = import(pairTable, pairUpdates.map { it.update() }, carriedPairContexts.map { it.row() })
        val droppedTriples =
            import(tripleTable, tripleUpdates.map { it.update() }, carriedTripleContexts.map { it.row() })
        return Dropped(droppedPairs.map { it.toBigram() }, droppedTriples.map { it.toTrigram() })
    }

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

    /** Forgets every pair and triple, with their contexts and clocks. */
    suspend fun forgetAll() {
        pairs.deleteAll()
        pairs.deleteAllContexts()
        triples.deleteAll()
        triples.deleteAllContexts()
        clocks.deleteAll()
    }

    /** [recordPairs], for the steps of one [table]. */
    private suspend fun record(table: LinkTable, steps: List<LinkStep>): List<LinkRow> {
        val dropped = ArrayList<LinkRow>()
        var engineDropped = false
        for (step in steps) {
            val recorded = step.use?.let { add(table, it, advancesClock = true) }
            if (step.dropped.isNotEmpty()) {
                table.delete(step.dropped)
                engineDropped = true
            }
            if (recorded == null) {
                continue
            }
            val context = table.inContext(recorded.language, recorded.context)
            val gone = PhraseBudget.overflow(
                context.map { if (it.word == recorded.word) recorded else it }, recorded,
                recorded.contextSeen, table.limits, { it.count }, { it.contextSeen },
            )
            table.delete(gone)
            dropped += gone
            dropped += pruneLanguage(table, recorded.language, recorded)
        }
        if (dropped.isNotEmpty() || engineDropped) {
            table.deleteEmptyContexts()
        }
        return dropped
    }

    /** [importLinks], for the links of one [table] and the [carried] contexts of its kind. */
    private suspend fun import(table: LinkTable, updates: List<LinkUpdate>, carried: List<ContextRow>): List<LinkRow> {
        val imported = updates.filterNot(::followsItself)
        val importedPerContext = imported
            .groupingBy { it.language to it.context.map(PhraseKey::word) }
            .fold(0L) { sum, update -> sum + update.delta }
        for (context in carried) {
            val uses = importedPerContext[context.language to context.context] ?: continue
            val held = table.context(context.language, context.context)
            table.upsertContext(
                ContextRow(
                    context.language, context.context,
                    (held?.total ?: 0L) + beyondLinks(context.total, uses),
                    maxOf(held?.lastUsedAt ?: 0L, context.lastUsedAt),
                    (held?.seen ?: 0L) + beyondLinks(context.seen, uses),
                ),
            )
        }
        val contexts = LinkedHashSet<Pair<String, List<String>>>()
        for (update in imported) {
            val recorded = add(table, update, advancesClock = false)
            contexts += recorded.language to recorded.context
        }
        val dropped = ArrayList<LinkRow>()
        for ((language, context) in contexts) {
            val seen = table.context(language, context)?.seen ?: 0L
            val gone = PhraseBudget.overflow(
                table.inContext(language, context), null, seen, table.limits, { it.count }, { it.contextSeen },
            )
            table.delete(gone)
            dropped += gone
        }
        for (language in contexts.map { it.first }.distinct()) {
            dropped += pruneLanguage(table, language, null)
        }
        if (dropped.isNotEmpty()) {
            table.deleteEmptyContexts()
        }
        return dropped
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
     * Adds [learned]'s uses to [table]; the language's clock of its kind moves with them only when
     * [advancesClock]. The link's and its context's last use never move back.
     */
    private suspend fun add(table: LinkTable, learned: LinkUpdate, advancesClock: Boolean): LinkRow {
        val update = learned.copy(context = learned.context.map(PhraseKey::word), word = PhraseKey.word(learned.word))
        val clock = clocks.clock(update.language) ?: UserPhraseClock(update.language, 0L, 0L)
        val tick = if (advancesClock) table.clockIn(clock) + update.delta else table.clockIn(clock)
        if (advancesClock) {
            clocks.upsert(table.withClock(clock, tick))
        }
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
        )
        table.upsert(recorded)
        return recorded
    }

    /** The uses a context's [carried] count holds beyond the [imported] its links bring, never below none. */
    private fun beyondLinks(carried: Long, imported: Long): Long = maxOf(0L, carried - imported)

    /** Prunes [language]'s links in [table] when it holds more than [PhraseBudget.LANGUAGE_CAP], never [kept]. */
    private suspend fun pruneLanguage(table: LinkTable, language: String, kept: LinkRow?): List<LinkRow> {
        if (PhraseBudget.languageExcess(table.countIn(language)) == 0) {
            return emptyList()
        }
        val clock = clocks.clock(language)?.let(table::clockIn) ?: 0L
        val gone = languageGone(
            table.inLanguage(language), table.contextsIn(language).associate { it.context to it.total }, clock, kept,
        )
        table.delete(gone)
        return gone
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
