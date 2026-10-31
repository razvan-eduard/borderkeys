// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.dao.BlockedWordDao
import com.borderkeys.data.dao.KeyTouchDao
import com.borderkeys.data.dao.UserCorrectionDao
import com.borderkeys.data.dao.UserWordDao
import com.borderkeys.data.entity.BlockedWord
import com.borderkeys.data.entity.KeyTouch
import com.borderkeys.data.entity.UserCorrection
import com.borderkeys.data.entity.UserWord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** The writes to one table in memory, counted, so a table's flows read it afresh after each. */
internal class Changes {
    private val count = MutableStateFlow(0)

    fun <T> observe(read: () -> T): Flow<T> = count.map { read() }

    fun changed() {
        count.value++
    }
}

/** The words table in memory, rows by word and language kept in the order first written, as Room's are. */
class MemoryWords : UserWordDao {
    private val rows = LinkedHashMap<Pair<String, String>, UserWord>()
    private val changes = Changes()

    /** Every row by count, then by recency, rows that tie in the order they were first written. */
    private fun byCount() = rows.values.sortedWith(compareByDescending<UserWord> { it.count }.thenByDescending { it.lastUsedAt })

    private fun write(update: () -> Unit) {
        update()
        changes.changed()
    }

    override fun observeAll(): Flow<List<UserWord>> = changes.observe(::byCount)

    /** The words holding [query], as SQLite's `LIKE` reads it: ASCII letters in either case. */
    override fun observeMatching(query: String): Flow<List<UserWord>> =
        changes.observe { byCount().filter { asciiLower(it.word).contains(asciiLower(query)) } }

    override suspend fun topWords(limit: Int) = byCount().take(limit)
    override suspend fun addedWords() = byCount().filter { it.added > 0 }
    override suspend fun topWordsNotAdded(limit: Int) = byCount().filter { it.added == 0 }.take(limit)
    override suspend fun rows(word: String) = rows.values.filter { it.word == word }
    override suspend fun allWords() = rows.keys.map { it.first }.distinct()

    override suspend fun increment(
        word: String,
        language: String,
        delta: Int,
        lastUsedAt: Long,
        deliberateCapitalDelta: Int,
        assertedDelta: Int,
        addedDelta: Int,
    ) = write {
        val held = rows[word to language]
        rows[word to language] = if (held == null) {
            UserWord(word, language, delta, lastUsedAt, deliberateCapitalDelta, assertedDelta, addedDelta)
        } else {
            held.copy(
                count = held.count + delta,
                lastUsedAt = lastUsedAt,
                deliberateCapitals = held.deliberateCapitals + deliberateCapitalDelta,
                asserted = held.asserted + assertedDelta,
                added = held.added + addedDelta,
            )
        }
    }

    override suspend fun merge(
        word: String,
        language: String,
        count: Int,
        lastUsedAt: Long,
        deliberateCapitals: Int,
        asserted: Int,
        added: Int,
    ) = write {
        val held = rows[word to language]
        rows[word to language] = if (held == null) {
            UserWord(word, language, count, lastUsedAt, deliberateCapitals, asserted, added)
        } else {
            held.copy(
                count = maxOf(held.count, count),
                lastUsedAt = maxOf(held.lastUsedAt, lastUsedAt),
                deliberateCapitals = maxOf(held.deliberateCapitals, deliberateCapitals),
                asserted = maxOf(held.asserted, asserted),
                added = maxOf(held.added, added),
            )
        }
    }

    override suspend fun decayStale(cutoff: Long, now: Long) = write {
        rows.replaceAll { _, it ->
            if (it.lastUsedAt < cutoff && it.count > PersonalWordDecay.STALE_FLOOR) {
                it.copy(count = maxOf(PersonalWordDecay.STALE_FLOOR, it.count / PersonalWordDecay.HALVING), lastUsedAt = now)
            } else {
                it
            }
        }
    }

    /** [UserWordDao.SINGLE_USE_NEVER_CHOSEN] for [cutoff]. */
    private fun unconfirmed(row: UserWord, cutoff: Long) = row.count <= 1 && row.asserted == 0 && row.lastUsedAt < cutoff

    override suspend fun deleteUnconfirmedBefore(cutoff: Long): Int {
        val gone = rows.values.count { unconfirmed(it, cutoff) }
        write { rows.values.removeAll { unconfirmed(it, cutoff) } }
        return gone
    }

    override suspend fun unconfirmedBefore(cutoff: Long) = rows.values.filter { unconfirmed(it, cutoff) }.map { it.word }
    override suspend fun rowsBeyond(keep: Int) = byCount().filter { it.added == 0 }.drop(keep)
    override suspend fun delete(word: String) = write { rows.keys.removeAll { it.first == word } }
    override suspend fun delete(word: String, language: String) = write { rows.remove(word to language) }
    override suspend fun deleteAll() = write { rows.clear() }
    override suspend fun count() = rows.size
    override fun observeCount(): Flow<Int> = changes.observe { rows.size }
    override fun observeCountNotAdded(): Flow<Int> = changes.observe { rows.values.count { it.added == 0 } }

    private companion object {
        /** [text] with its ASCII capitals lowered, as SQLite's `LIKE` compares. */
        fun asciiLower(text: String) = buildString(text.length) {
            for (char in text) append(if (char in 'A'..'Z') char + ('a' - 'A') else char)
        }
    }
}

/** The learned corrections in memory, rows kept in the order they were first written. */
class MemoryCorrections : UserCorrectionDao {
    private val rows = LinkedHashMap<List<String>, UserCorrection>()

    override suspend fun all() = rows.values.toList()

    override suspend fun merge(language: String, key: String, previousWord: String, word: String, count: Int, lastUsedAt: Long) {
        val id = listOf(language, key, previousWord, word)
        val held = rows[id]
        rows[id] = held?.copy(count = maxOf(held.count, count), lastUsedAt = maxOf(held.lastUsedAt, lastUsedAt))
            ?: UserCorrection(language, key, previousWord, word, count, lastUsedAt)
    }

    override suspend fun deleteWord(word: String) {
        rows.values.removeAll { it.word == word || it.previousWord == word }
    }

    override suspend fun deleteAll() = rows.clear()
}

/** The blocked words in memory. */
class MemoryBlocked : BlockedWordDao {
    private val words = LinkedHashSet<String>()
    private val changes = Changes()

    override fun observeAll(): Flow<List<BlockedWord>> = changes.observe { words.sorted().map(::BlockedWord) }
    override suspend fun allWords() = words.toList()
    override suspend fun insert(word: BlockedWord) {
        words += word.word
        changes.changed()
    }
    override suspend fun delete(word: String) {
        words -= word
        changes.changed()
    }
    override suspend fun isBlocked(word: String) = word in words
}

/** The heatmap's totals in memory. */
class MemoryTouches : KeyTouchDao {
    private val rows = LinkedHashMap<Pair<String, Int>, KeyTouch>()
    private val changes = Changes()

    override fun observeTaps(): Flow<Double> = changes.observe { rows.values.sumOf { it.taps } }
    override fun observeAll(): Flow<List<KeyTouch>> = changes.observe { rows.values.toList() }
    override suspend fun inBucket(bucket: String) = rows.values.filter { it.bucket == bucket }
    override suspend fun find(bucket: String, code: Int) = rows[bucket to code]
    override suspend fun upsert(touch: KeyTouch) {
        rows[touch.bucket to touch.code] = touch
        changes.changed()
    }
    override suspend fun deleteAll() {
        rows.clear()
        changes.changed()
    }
}

/** A [Transactor] that runs each block as it comes: a block that throws keeps what it wrote. */
object MemoryTransactor : Transactor {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
}

/**
 * A [DictionaryRepository] on tables of its own in memory, with no database, its reads and writes
 * run on [thread]: at once by default.
 */
class MemoryDictionary(thread: WorkerThread = InlineThread) {
    val worker = testWorker(thread)
    val words = MemoryWords()
    val blocked = MemoryBlocked()
    val pairs = MemoryPairs()
    val triples = MemoryTriples()
    val touches = MemoryTouches()
    val clocks = MemoryClocks()
    val corrections = MemoryCorrections()
    val repository = DictionaryRepository(worker, MemoryTransactor, words, blocked, pairs, triples, touches, clocks, corrections)
}
