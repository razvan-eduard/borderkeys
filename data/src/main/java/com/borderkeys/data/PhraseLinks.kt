// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedTrigram
import com.borderkeys.data.dao.PairStep
import com.borderkeys.data.dao.TripleStep
import com.borderkeys.data.dao.UserBigramDao
import com.borderkeys.data.dao.UserTrigramDao
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext

/**
 * A learned pair or triple, either kind: its language, the words before its last ([context]), its
 * last word, its count and last use, and when its context and its language last saw it.
 */
data class LinkRow(
    val language: String,
    val context: List<String>,
    val word: String,
    val count: Int,
    val lastUsedAt: Long,
    val contextSeen: Long,
    val languageSeen: Long,
)

/** The words a pair or triple follows: its language, the words, their total, last use and observations. */
internal data class ContextRow(
    val language: String,
    val context: List<String>,
    val total: Long,
    val lastUsedAt: Long,
    val seen: Long,
)

/** Uses of a pair or triple to record: its language, the words before its last, its last word, how many, when. */
internal data class LinkUpdate(
    val language: String,
    val context: List<String>,
    val word: String,
    val delta: Int,
    val lastUsedAt: Long,
)

internal fun UserBigram.row() =
    LinkRow(language, listOf(previousWord), word, count, lastUsedAt, contextSeen, languageSeen)

fun LinkRow.toBigram() =
    UserBigram(language, context.single(), word, count, lastUsedAt, contextSeen, languageSeen)

internal fun UserTrigram.row() =
    LinkRow(language, listOf(previousWord2, previousWord1), word, count, lastUsedAt, contextSeen, languageSeen)

fun LinkRow.toTrigram(): UserTrigram {
    val (previousWord2, previousWord1) = context
    return UserTrigram(language, previousWord2, previousWord1, word, count, lastUsedAt, contextSeen, languageSeen)
}

internal fun UserPairContext.row() = ContextRow(language, listOf(previousWord), total, lastUsedAt, seen)

internal fun ContextRow.toPairContext() = UserPairContext(language, context.single(), total, lastUsedAt, seen)

internal fun UserTripleContext.row() =
    ContextRow(language, listOf(previousWord2, previousWord1), total, lastUsedAt, seen)

internal fun ContextRow.toTripleContext(): UserTripleContext {
    val (previousWord2, previousWord1) = context
    return UserTripleContext(language, previousWord2, previousWord1, total, lastUsedAt, seen)
}

internal fun LearnedBigram.update() = LinkUpdate(language, listOf(previousWord), word, delta, lastUsedAt)

internal fun LearnedTrigram.update() =
    LinkUpdate(language, listOf(previousWord2, previousWord1), word, delta, lastUsedAt)

/** A use the engine recorded, or none, and the links it dropped right after, either kind. */
internal class LinkStep(val use: LinkUpdate?, val dropped: List<LinkRow>)

internal fun PairStep.step() = LinkStep(use?.update(), dropped.map { it.row() })

internal fun TripleStep.step() = LinkStep(use?.update(), dropped.map { it.row() })

/** One kind's tables, pairs or triples, read and written as rows. */
internal interface LinkTable {

    /** What one context of this kind keeps. */
    val limits: PhraseBudget.Limits

    /** This kind's clock in [clock]. */
    fun clockIn(clock: UserPhraseClock): Long

    /** [clock] with this kind's clock at [tick]. */
    fun withClock(clock: UserPhraseClock, tick: Long): UserPhraseClock

    suspend fun context(language: String, context: List<String>): ContextRow?

    suspend fun upsertContext(context: ContextRow)

    /** The links after [context] in [language]. */
    suspend fun inContext(language: String, context: List<String>): List<LinkRow>

    suspend fun upsert(link: LinkRow)

    suspend fun delete(links: List<LinkRow>)

    suspend fun countIn(language: String): Int

    suspend fun inLanguage(language: String): List<LinkRow>

    suspend fun contextsIn(language: String): List<ContextRow>

    /** The links [word] takes part in, anywhere, in every language. */
    suspend fun involving(word: String): List<LinkRow>

    /** Forgets the contexts [word] is part of, in every language. */
    suspend fun deleteContextsOf(word: String)

    /** Deletes the contexts no link follows any more. */
    suspend fun deleteEmptyContexts()
}

/** The pair tables. */
internal class PairTable(private val dao: UserBigramDao) : LinkTable {
    override val limits = PhraseBudget.PAIRS

    override fun clockIn(clock: UserPhraseClock) = clock.pairs

    override fun withClock(clock: UserPhraseClock, tick: Long) = clock.copy(pairs = tick)

    override suspend fun context(language: String, context: List<String>) =
        dao.context(language, context.single())?.row()

    override suspend fun upsertContext(context: ContextRow) = dao.upsertContext(context.toPairContext())

    override suspend fun inContext(language: String, context: List<String>) =
        dao.inContext(language, context.single()).map { it.row() }

    override suspend fun upsert(link: LinkRow) = dao.upsert(link.toBigram())

    override suspend fun delete(links: List<LinkRow>) = dao.delete(links.map { it.toBigram() })

    override suspend fun countIn(language: String) = dao.countIn(language)

    override suspend fun inLanguage(language: String) = dao.inLanguage(language).map { it.row() }

    override suspend fun contextsIn(language: String) = dao.contextsIn(language).map { it.row() }

    override suspend fun involving(word: String) = dao.involving(word).map { it.row() }

    override suspend fun deleteContextsOf(word: String) = dao.deleteContextsOf(word)

    override suspend fun deleteEmptyContexts() = dao.deleteEmptyContexts()
}

/** The triple tables. */
internal class TripleTable(private val dao: UserTrigramDao) : LinkTable {
    override val limits = PhraseBudget.TRIPLES

    override fun clockIn(clock: UserPhraseClock) = clock.triples

    override fun withClock(clock: UserPhraseClock, tick: Long) = clock.copy(triples = tick)

    override suspend fun context(language: String, context: List<String>): ContextRow? {
        val (previousWord2, previousWord1) = context
        return dao.context(language, previousWord2, previousWord1)?.row()
    }

    override suspend fun upsertContext(context: ContextRow) = dao.upsertContext(context.toTripleContext())

    override suspend fun inContext(language: String, context: List<String>): List<LinkRow> {
        val (previousWord2, previousWord1) = context
        return dao.inContext(language, previousWord2, previousWord1).map { it.row() }
    }

    override suspend fun upsert(link: LinkRow) = dao.upsert(link.toTrigram())

    override suspend fun delete(links: List<LinkRow>) = dao.delete(links.map { it.toTrigram() })

    override suspend fun countIn(language: String) = dao.countIn(language)

    override suspend fun inLanguage(language: String) = dao.inLanguage(language).map { it.row() }

    override suspend fun contextsIn(language: String) = dao.contextsIn(language).map { it.row() }

    override suspend fun involving(word: String) = dao.involving(word).map { it.row() }

    override suspend fun deleteContextsOf(word: String) = dao.deleteContextsOf(word)

    override suspend fun deleteEmptyContexts() = dao.deleteEmptyContexts()
}

/**
 * The [links] of one language its prune removes, in the order they go: as many as
 * [PhraseBudget.languageExcess] gives for them, by [PhraseBudget.languageOverflow] on the language's
 * [clock], ties going to the smaller share of the context's total in [contextTotals] (keyed by the
 * context's words, and held at no less than its links' counts together); never [kept].
 */
fun languageGone(
    links: List<LinkRow>,
    contextTotals: Map<List<String>, Long>,
    clock: Long,
    kept: LinkRow?,
): List<LinkRow> {
    val sums = links.groupingBy { it.context }.fold(0L) { sum, link -> sum + link.count }
    val totals = sums.mapValues { (context, sum) -> maxOf(contextTotals[context] ?: 0L, sum) }
    return PhraseBudget.languageOverflow(
        links.asSequence(), PhraseBudget.languageExcess(links.size), clock,
        isKept = { kept != null && it.context == kept.context && it.word == kept.word },
        countOf = { it.count },
        seenOf = { it.languageSeen },
        tieBreak = { share(it.count, totals[it.context]) },
    )
}

/** A link's share of its context's total, or none without one. */
private fun share(count: Int, total: Long?): Double =
    if (total == null || total <= 0L) 0.0 else count.toDouble() / total
