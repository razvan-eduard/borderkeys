// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.backup

import com.borderkeys.data.DictionaryRows
import com.borderkeys.data.PhraseKey
import com.borderkeys.data.WordMigration
import com.borderkeys.data.WordShape
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserCorrection
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext
import com.borderkeys.data.entity.UserWord
import com.borderkeys.language.LanguageTags

/**
 * A backup's personal dictionary as the rows a restore merges ([DictionaryRows]), each entry read
 * under the rules of the file's format and checked before any is written.
 */
internal object BackupDictionary {

    /** The rows [payload] carries that can be read, and how many entries could not be. */
    class Read(val rows: DictionaryRows, val unreadable: Int)

    /** The fewest uses an entry restored holds. */
    private const val LEAST_USES = 1

    /** A stamp the file does not carry, and a link's language stamp, which the restore sets. */
    private const val NO_STAMP = 0L

    /**
     * [payload]'s words, links, contexts, corrections and blocked words as rows, at [now]: a stamp
     * the file does not carry, or one after [now], read as [now]. An entry that cannot be read (a
     * word not of a word's length, a link's word empty or too long, a count below one use, a
     * negative stamp, mark, total or observations, a language that is no primary subtag, a link
     * whose last word follows itself) is left out and counted. A file before
     * [BackupFile.LANGUAGE_FORMAT] reads under its own rules: each word as a word kept from before
     * the language was ([WordMigration.upgraded]), a link that names no language in the one its last
     * word was learned under ([PhraseKey.languageOfWord], else [fallbackLanguage]).
     */
    fun read(payload: BackupPayload, fallbackLanguage: String, now: Long): Read {
        var unreadable = 0
        fun <T, R : Any> kept(entries: List<T>, read: (T) -> R?): List<R> = entries.mapNotNull { entry ->
            read(entry).also { if (it == null) unreadable++ }
        }
        val upgrading = payload.format < BackupFile.LANGUAGE_FORMAT
        val languageOf =
            PhraseKey.languageOfWord(payload.words.associate { it.word to it.language }, fallbackLanguage)
        fun stamp(written: Long): Long? = when {
            written < NO_STAMP -> null
            written == NO_STAMP || written > now -> now
            else -> written
        }
        fun linkLanguage(language: String, lastWord: String): String? = when {
            language.isNotEmpty() -> language.takeIf(::isLanguage)
            upgrading -> languageOf(lastWord)
            else -> null
        }

        val words = kept(payload.words) { word ->
            val usedAt = stamp(word.lastUsedAt)
            when {
                !WordShape.isWordLength(word.word) || word.count < LEAST_USES || usedAt == null ||
                    negative(word.deliberateCapitals, word.asserted, word.added) -> null
                upgrading -> WordMigration.upgraded(word.word, word.count, usedAt, word.deliberateCapitals, word.added)
                word.language != UserWord.UNPLACED && !isLanguage(word.language) -> null
                else -> UserWord(
                    word.word, word.language, word.count, usedAt, word.deliberateCapitals, word.asserted, word.added,
                )
            }
        }
        val pairs = kept(payload.bigrams) { pair ->
            val usedAt = stamp(pair.lastUsedAt)
            val language = linkLanguage(pair.language, pair.word)
            val previous = PhraseKey.word(pair.previous)
            val word = PhraseKey.word(pair.word)
            if (language == null || usedAt == null || !linkable(listOf(previous), word, pair.count) ||
                negative(pair.contextSeen, pair.asserted, pair.added)
            ) {
                null
            } else {
                UserBigram(
                    language, previous, word, pair.count, usedAt, pair.contextSeen, NO_STAMP, pair.asserted, pair.added,
                )
            }
        }
        val triples = kept(payload.trigrams) { triple ->
            val usedAt = stamp(triple.lastUsedAt)
            val language = linkLanguage(triple.language, triple.word)
            val previous2 = PhraseKey.word(triple.previous2)
            val previous1 = PhraseKey.word(triple.previous1)
            val word = PhraseKey.word(triple.word)
            if (language == null || usedAt == null || !linkable(listOf(previous2, previous1), word, triple.count) ||
                negative(triple.contextSeen, triple.asserted, triple.added)
            ) {
                null
            } else {
                UserTrigram(
                    language, previous2, previous1, word, triple.count, usedAt, triple.contextSeen, NO_STAMP,
                    triple.asserted, triple.added,
                )
            }
        }
        val pairContexts = kept(payload.pairContexts) { context ->
            val usedAt = stamp(context.lastUsedAt)
            val previous = PhraseKey.word(context.previous)
            if (!isLanguage(context.language) || usedAt == null || !linkWord(previous) ||
                negative(context.total, context.seen)
            ) {
                null
            } else {
                UserPairContext(context.language, previous, context.total, usedAt, context.seen)
            }
        }
        val tripleContexts = kept(payload.tripleContexts) { context ->
            val usedAt = stamp(context.lastUsedAt)
            val previous2 = PhraseKey.word(context.previous2)
            val previous1 = PhraseKey.word(context.previous1)
            if (!isLanguage(context.language) || usedAt == null || !linkWord(previous2) || !linkWord(previous1) ||
                negative(context.total, context.seen)
            ) {
                null
            } else {
                UserTripleContext(context.language, previous2, previous1, context.total, usedAt, context.seen)
            }
        }
        val corrections = kept(payload.corrections) { correction ->
            val usedAt = stamp(correction.lastUsedAt)
            val key = PhraseKey.word(correction.key)
            val previous = PhraseKey.word(correction.previous)
            val word = PhraseKey.word(correction.word)
            if (!isLanguage(correction.language) || usedAt == null || correction.count < LEAST_USES ||
                !WordShape.isWordLength(key) || !linkWord(previous) || !WordShape.isWordLength(word)
            ) {
                null
            } else {
                UserCorrection(correction.language, key, previous, word, correction.count, usedAt)
            }
        }
        val blocked = kept(payload.blocked) { word -> word.takeIf(WordShape::isWordLength) }
        val rows = DictionaryRows(words, pairs, pairContexts, triples, tripleContexts, corrections, blocked)
        return Read(rows, unreadable)
    }

    /** Whether [language] is a primary language subtag, as an entry is kept under. */
    private fun isLanguage(language: String): Boolean = language == LanguageTags.primary(language)

    /** Whether any of [values] is below zero. */
    private fun negative(vararg values: Number): Boolean = values.any { it.toLong() < 0L }

    /** Whether [key] can be a word of a link, as a link takes one: not empty, at most [WordShape.MAX_LENGTH] long. */
    private fun linkWord(key: String): Boolean = key.isNotEmpty() && key.length <= WordShape.MAX_LENGTH

    /** Whether a link of [count] uses of [word] after [context] can be kept: each a link's word, not repeated. */
    private fun linkable(context: List<String>, word: String, count: Int): Boolean =
        count >= LEAST_USES && context.all(::linkWord) && linkWord(word) && context.last() != word
}
