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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Rows keyed as Room keys them, indexed by context and counted by language. */
private class IndexedRows<T>(private val keyOf: (T) -> List<String>, private val contextOf: (T) -> List<String>) {
    val rows = LinkedHashMap<List<String>, T>()
    private val byContext = HashMap<List<String>, LinkedHashSet<List<String>>>()
    private val byLanguage = HashMap<String, Int>()

    fun put(row: T) {
        val key = keyOf(row)
        if (rows.put(key, row) == null) {
            byContext.getOrPut(contextOf(row)) { LinkedHashSet() } += key
            byLanguage[key[0]] = (byLanguage[key[0]] ?: 0) + 1
        }
    }

    fun remove(row: T) {
        val key = keyOf(row)
        if (rows.remove(key) != null) {
            byContext[contextOf(row)]?.remove(key)
            byLanguage[key[0]] = (byLanguage[key[0]] ?: 1) - 1
        }
    }

    fun inContext(context: List<String>): List<T> = byContext[context].orEmpty().mapNotNull { rows[it] }

    fun countIn(language: String) = byLanguage[language] ?: 0

    fun clear() {
        rows.clear()
        byContext.clear()
        byLanguage.clear()
    }
}

/** The pair tables in memory. */
class MemoryPairs : UserBigramDao {
    private val table = IndexedRows<UserBigram>(
        { listOf(it.language, it.previousWord, it.word) }, { listOf(it.language, it.previousWord) },
    )
    val rows: Map<List<String>, UserBigram> get() = table.rows
    val contexts = LinkedHashMap<List<String>, UserPairContext>()

    override suspend fun all() = table.rows.values.toList()
    override suspend fun inContext(language: String, previousWord: String) = table.inContext(listOf(language, previousWord))
    override suspend fun inLanguage(language: String) = table.rows.values.filter { it.language == language }
    override suspend fun countIn(language: String) = table.countIn(language)
    override suspend fun usedAtMost(uses: Int) = table.rows.values.filter { it.count <= uses }
    override suspend fun involving(word: String) =
        table.rows.values.filter { it.previousWord == word || it.word == word }
    override suspend fun upsert(pair: UserBigram) = table.put(pair)
    override suspend fun delete(pairs: List<UserBigram>) = pairs.forEach(table::remove)
    override suspend fun decayStale(cutoff: Long, now: Long) {
        table.rows.values.filter { it.lastUsedAt < cutoff && it.count > PersonalWordDecay.STALE_FLOOR }
            .forEach {
                table.put(
                    it.copy(count = maxOf(PersonalWordDecay.STALE_FLOOR, it.count / PersonalWordDecay.HALVING), lastUsedAt = now),
                )
            }
    }
    override suspend fun deleteAll() = table.clear()
    override fun observeAll(): Flow<List<UserBigram>> = flowOf(table.rows.values.toList())
    override fun observeCount(): Flow<Int> = flowOf(table.rows.size)
    override suspend fun count() = table.rows.size
    override suspend fun context(language: String, previousWord: String) = contexts[listOf(language, previousWord)]
    override suspend fun contextsIn(language: String) = contexts.values.filter { it.language == language }
    override suspend fun allContexts() = contexts.values.toList()
    override suspend fun upsertContext(context: UserPairContext) {
        contexts[listOf(context.language, context.previousWord)] = context
    }
    override suspend fun deleteContextsOf(word: String) {
        contexts.values.removeAll { it.previousWord == word }
    }
    override suspend fun decayStaleContexts(cutoff: Long, now: Long) {
        contexts.replaceAll { _, it ->
            if (it.lastUsedAt < cutoff && it.total > PersonalWordDecay.STALE_FLOOR) {
                it.copy(total = maxOf(PersonalWordDecay.STALE_FLOOR.toLong(), it.total / PersonalWordDecay.HALVING), lastUsedAt = now)
            } else {
                it
            }
        }
    }
    override suspend fun deleteAllContexts() = contexts.clear()
    override suspend fun deleteEmptyContexts() {
        contexts.keys.removeAll { table.inContext(it).isEmpty() }
    }
}

/** The triple tables in memory. */
class MemoryTriples : UserTrigramDao {
    private val table = IndexedRows<UserTrigram>(
        { listOf(it.language, it.previousWord2, it.previousWord1, it.word) },
        { listOf(it.language, it.previousWord2, it.previousWord1) },
    )
    val rows: Map<List<String>, UserTrigram> get() = table.rows
    val contexts = LinkedHashMap<List<String>, UserTripleContext>()

    override suspend fun all() = table.rows.values.toList()
    override suspend fun inContext(language: String, previousWord2: String, previousWord1: String) =
        table.inContext(listOf(language, previousWord2, previousWord1))
    override suspend fun inLanguage(language: String) = table.rows.values.filter { it.language == language }
    override suspend fun countIn(language: String) = table.countIn(language)
    override suspend fun usedAtMost(uses: Int) = table.rows.values.filter { it.count <= uses }
    override suspend fun involving(word: String) =
        table.rows.values.filter { it.previousWord2 == word || it.previousWord1 == word || it.word == word }
    override suspend fun containingPair(language: String, first: String, second: String) =
        table.rows.values.filter {
            it.language == language &&
                ((it.previousWord2 == first && it.previousWord1 == second) || (it.previousWord1 == first && it.word == second))
        }
    override suspend fun upsert(triple: UserTrigram) = table.put(triple)
    override suspend fun delete(triples: List<UserTrigram>) = triples.forEach(table::remove)
    override suspend fun decayStale(cutoff: Long, now: Long) {
        table.rows.values.filter { it.lastUsedAt < cutoff && it.count > PersonalWordDecay.STALE_FLOOR }
            .forEach {
                table.put(
                    it.copy(count = maxOf(PersonalWordDecay.STALE_FLOOR, it.count / PersonalWordDecay.HALVING), lastUsedAt = now),
                )
            }
    }
    override suspend fun deleteAll() = table.clear()
    override fun observeAll(): Flow<List<UserTrigram>> = flowOf(table.rows.values.toList())
    override fun observeCount(): Flow<Int> = flowOf(table.rows.size)
    override suspend fun count() = table.rows.size
    override suspend fun context(language: String, previousWord2: String, previousWord1: String) =
        contexts[listOf(language, previousWord2, previousWord1)]
    override suspend fun contextsIn(language: String) = contexts.values.filter { it.language == language }
    override suspend fun allContexts() = contexts.values.toList()
    override suspend fun upsertContext(context: UserTripleContext) {
        contexts[listOf(context.language, context.previousWord2, context.previousWord1)] = context
    }
    override suspend fun deleteContextsOf(word: String) {
        contexts.values.removeAll { it.previousWord2 == word || it.previousWord1 == word }
    }
    override suspend fun decayStaleContexts(cutoff: Long, now: Long) {
        contexts.replaceAll { _, it ->
            if (it.lastUsedAt < cutoff && it.total > PersonalWordDecay.STALE_FLOOR) {
                it.copy(total = maxOf(PersonalWordDecay.STALE_FLOOR.toLong(), it.total / PersonalWordDecay.HALVING), lastUsedAt = now)
            } else {
                it
            }
        }
    }
    override suspend fun deleteAllContexts() = contexts.clear()
    override suspend fun deleteEmptyContexts() {
        contexts.keys.removeAll { table.inContext(it).isEmpty() }
    }
}

/** The clock table in memory. */
class MemoryClocks : UserPhraseClockDao {
    val rows = LinkedHashMap<String, UserPhraseClock>()

    override suspend fun clock(language: String) = rows[language]
    override suspend fun all() = rows.values.toList()
    override suspend fun upsert(clock: UserPhraseClock) {
        rows[clock.language] = clock
    }
    override suspend fun deleteAll() = rows.clear()
}
