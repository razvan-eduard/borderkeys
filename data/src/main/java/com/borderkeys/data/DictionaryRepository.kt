// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.dao.BlockedWordDao
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.dao.UserWordDao
import com.borderkeys.data.entity.BlockedWord
import com.borderkeys.data.entity.UserWord
import kotlinx.coroutines.flow.Flow

/**
 * The personal dictionary: what the keyboard has learned, and what it has been told to forget.
 */
class DictionaryRepository internal constructor(
    private val userWords: UserWordDao,
    private val blockedWords: BlockedWordDao,
) {
    val words: Flow<List<UserWord>> = userWords.observeAll()
    val blocked: Flow<List<BlockedWord>> = blockedWords.observeAll()

    fun search(query: String): Flow<List<UserWord>> = userWords.observeMatching(query)

    /** The set pushed into the native engine at service start. */
    suspend fun topWords(limit: Int = MAX_WORDS_IN_MEMORY): List<UserWord> =
        userWords.topWords(limit)

    suspend fun blockedWordSet(): Set<String> = blockedWords.allWords().toSet()

    /**
     * Applies a batch of learning updates in one transaction.
     *
     * Batched because this is the flush of an in-memory buffer, not a per-keystroke write. A
     * single INSERT on the path of a key press would put a disk write, an encryption pass and a
     * transaction inside a two-millisecond budget.
     */
    suspend fun applyLearned(updates: List<LearnedWord>) {
        if (updates.isEmpty()) {
            return
        }
        userWords.incrementAll(updates)
    }

    suspend fun forget(word: String) = userWords.delete(word)

    suspend fun forgetEverything() = userWords.deleteAll()

    /**
     * Refuses a word permanently and removes whatever was learned about it.
     *
     * Both halves matter. Blocking without deleting leaves a count the user asked to be rid of;
     * deleting without blocking means the word comes back from the language pack the next time
     * it is typed, which reads as the setting not having worked.
     */
    suspend fun block(word: String) {
        blockedWords.insert(BlockedWord(word))
        userWords.delete(word)
    }

    suspend fun unblock(word: String) = blockedWords.delete(word)

    /**
     * The personal dictionary as CSV.
     *
     * The only form of "sync" an application with no network can offer, and it is entirely the
     * user's: they export a file, they move it, they carry it, they import it. Nothing leaves
     * the device unless a person carries it. The format lives in [DictionaryCsv], where it can
     * be tested without a database.
     */
    suspend fun exportCsv(): String = DictionaryCsv.encode(userWords.topWords(Int.MAX_VALUE))

    /**
     * Imports an export, merging counts into whatever is already here. Returns the row count.
     */
    suspend fun importCsv(csv: String, now: Long = System.currentTimeMillis()): Int {
        val updates = DictionaryCsv.decode(csv, now)
        applyLearned(updates)
        return updates.size
    }

    private companion object {
        const val MAX_WORDS_IN_MEMORY = 20_000
    }
}
