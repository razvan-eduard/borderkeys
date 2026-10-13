// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext

/**
 * The pairs and triples of a version 10 database as version 11 keeps them
 * ([BorderKeysDatabase.migration10To11]). Each link takes the language its last word was learned
 * under, or the one most words were, else the fallback, and its words in lower case; spellings
 * that meet add up.
 * A context's total and a language's clock are the sums of their links' counts, and every link
 * is stamped as seen at both. Then every context and language keeps what [PhraseBudget] allows
 * an import.
 */
internal object PhraseMigration {

    /** The fields at the head of every key before its words: the language. */
    private const val LANGUAGE_FIELDS = 1

    /** The words of a pair's context, and of a triple's. */
    private const val PAIR_CONTEXT_WORDS = 1
    private const val TRIPLE_CONTEXT_WORDS = 2

    /** A version 10 pair or triple: its words in order, its count, when it was last used. */
    data class StoredLink(val words: List<String>, val count: Int, val lastUsedAt: Long)

    class Rows(
        val pairs: List<UserBigram>,
        val pairContexts: List<UserPairContext>,
        val triples: List<UserTrigram>,
        val tripleContexts: List<UserTripleContext>,
        val clocks: List<UserPhraseClock>,
    )

    /**
     * Rewrites the pair and triple tables of a version 10 [db] as version 11 keeps them, a link no
     * word's locale names a language for taking [fallbackLanguage].
     */
    fun migrate(db: SupportSQLiteDatabase, fallbackLanguage: String) {
        val locales = HashMap<String, String>()
        db.query("SELECT `word`, `locale` FROM `user_words`").use { rows ->
            while (rows.moveToNext()) {
                locales[rows.getString(0)] = rows.getString(1)
            }
        }
        val pairs = ArrayList<StoredLink>()
        db.query("SELECT `previousWord`, `word`, `count`, `lastUsedAt` FROM `user_bigrams`").use { rows ->
            while (rows.moveToNext()) {
                pairs += StoredLink(listOf(rows.getString(0), rows.getString(1)), rows.getInt(2), rows.getLong(3))
            }
        }
        val triples = ArrayList<StoredLink>()
        db.query(
            "SELECT `previousWord2`, `previousWord1`, `word`, `count`, `lastUsedAt` FROM `user_trigrams`",
        ).use { rows ->
            while (rows.moveToNext()) {
                triples += StoredLink(
                    listOf(rows.getString(0), rows.getString(1), rows.getString(2)), rows.getInt(3), rows.getLong(4),
                )
            }
        }
        val converted = convert(locales, pairs, triples, fallbackLanguage)

        db.execSQL("DROP TABLE `user_bigrams`")
        db.execSQL("DROP TABLE `user_trigrams`")
        CREATE_STATEMENTS.forEach(db::execSQL)
        for (pair in converted.pairs) {
            insert(db, "user_bigrams") {
                put("language", pair.language)
                put("previousWord", pair.previousWord)
                put("word", pair.word)
                put("count", pair.count)
                put("lastUsedAt", pair.lastUsedAt)
                put("contextSeen", pair.contextSeen)
                put("languageSeen", pair.languageSeen)
            }
        }
        for (context in converted.pairContexts) {
            insert(db, "user_pair_contexts") {
                put("language", context.language)
                put("previousWord", context.previousWord)
                put("total", context.total)
                put("lastUsedAt", context.lastUsedAt)
                put("seen", context.seen)
            }
        }
        for (triple in converted.triples) {
            insert(db, "user_trigrams") {
                put("language", triple.language)
                put("previousWord2", triple.previousWord2)
                put("previousWord1", triple.previousWord1)
                put("word", triple.word)
                put("count", triple.count)
                put("lastUsedAt", triple.lastUsedAt)
                put("contextSeen", triple.contextSeen)
                put("languageSeen", triple.languageSeen)
            }
        }
        for (context in converted.tripleContexts) {
            insert(db, "user_triple_contexts") {
                put("language", context.language)
                put("previousWord2", context.previousWord2)
                put("previousWord1", context.previousWord1)
                put("total", context.total)
                put("lastUsedAt", context.lastUsedAt)
                put("seen", context.seen)
            }
        }
        for (clock in converted.clocks) {
            insert(db, "user_phrase_clocks") {
                put("language", clock.language)
                put("pairs", clock.pairs)
                put("triples", clock.triples)
            }
        }
    }

    private inline fun insert(db: SupportSQLiteDatabase, table: String, values: ContentValues.() -> Unit) {
        db.insert(table, SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply(values))
    }

    /** Version 11's pair and triple tables, as Room creates them. */
    private val CREATE_STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS `user_bigrams` (`language` TEXT NOT NULL, `previousWord` TEXT NOT NULL, " +
            "`word` TEXT NOT NULL, `count` INTEGER NOT NULL, `lastUsedAt` INTEGER NOT NULL, " +
            "`contextSeen` INTEGER NOT NULL, `languageSeen` INTEGER NOT NULL, " +
            "PRIMARY KEY(`language`, `previousWord`, `word`))",
        "CREATE INDEX IF NOT EXISTS `index_user_bigrams_count` ON `user_bigrams` (`count` DESC)",
        "CREATE INDEX IF NOT EXISTS `index_user_bigrams_word` ON `user_bigrams` (`word`)",
        "CREATE INDEX IF NOT EXISTS `index_user_bigrams_previousWord` ON `user_bigrams` (`previousWord`)",
        "CREATE TABLE IF NOT EXISTS `user_trigrams` (`language` TEXT NOT NULL, `previousWord2` TEXT NOT NULL, " +
            "`previousWord1` TEXT NOT NULL, `word` TEXT NOT NULL, `count` INTEGER NOT NULL, " +
            "`lastUsedAt` INTEGER NOT NULL, `contextSeen` INTEGER NOT NULL, `languageSeen` INTEGER NOT NULL, " +
            "PRIMARY KEY(`language`, `previousWord2`, `previousWord1`, `word`))",
        "CREATE INDEX IF NOT EXISTS `index_user_trigrams_count` ON `user_trigrams` (`count` DESC)",
        "CREATE INDEX IF NOT EXISTS `index_user_trigrams_word` ON `user_trigrams` (`word`)",
        "CREATE INDEX IF NOT EXISTS `index_user_trigrams_previousWord1` ON `user_trigrams` (`previousWord1`)",
        "CREATE INDEX IF NOT EXISTS `index_user_trigrams_previousWord2` ON `user_trigrams` (`previousWord2`)",
        "CREATE TABLE IF NOT EXISTS `user_pair_contexts` (`language` TEXT NOT NULL, `previousWord` TEXT NOT NULL, " +
            "`total` INTEGER NOT NULL, `lastUsedAt` INTEGER NOT NULL, `seen` INTEGER NOT NULL, " +
            "PRIMARY KEY(`language`, `previousWord`))",
        "CREATE INDEX IF NOT EXISTS `index_user_pair_contexts_previousWord` ON `user_pair_contexts` (`previousWord`)",
        "CREATE TABLE IF NOT EXISTS `user_triple_contexts` (`language` TEXT NOT NULL, " +
            "`previousWord2` TEXT NOT NULL, `previousWord1` TEXT NOT NULL, `total` INTEGER NOT NULL, " +
            "`lastUsedAt` INTEGER NOT NULL, `seen` INTEGER NOT NULL, " +
            "PRIMARY KEY(`language`, `previousWord2`, `previousWord1`))",
        "CREATE INDEX IF NOT EXISTS `index_user_triple_contexts_previousWord1` ON `user_triple_contexts` " +
            "(`previousWord1`)",
        "CREATE INDEX IF NOT EXISTS `index_user_triple_contexts_previousWord2` ON `user_triple_contexts` " +
            "(`previousWord2`)",
        "CREATE TABLE IF NOT EXISTS `user_phrase_clocks` (`language` TEXT NOT NULL, `pairs` INTEGER NOT NULL, " +
            "`triples` INTEGER NOT NULL, PRIMARY KEY(`language`))",
    )

    /**
     * Converts [pairs] and [triples], [localeOf] giving each learned word's recorded locale and
     * [fallbackLanguage] the language of a link none names one for.
     */
    fun convert(
        localeOf: Map<String, String>,
        pairs: List<StoredLink>,
        triples: List<StoredLink>,
        fallbackLanguage: String,
    ): Rows {
        val languageOfWord = PhraseKey.languageOfWord(localeOf, fallbackLanguage)
        val languageOf = { link: StoredLink -> languageOfWord(link.words.last()) }
        val pairRows = kindRows(pairs, PAIR_CONTEXT_WORDS, PhraseBudget.PAIRS, languageOf)
        val tripleRows = kindRows(triples, TRIPLE_CONTEXT_WORDS, PhraseBudget.TRIPLES, languageOf)
        val languages = (pairRows.clocks.keys + tripleRows.clocks.keys).sorted()
        return Rows(
            pairs = pairRows.links.map { it.toBigram() },
            pairContexts = pairRows.contexts.map { it.toPairContext() },
            triples = tripleRows.links.map { it.toTrigram() },
            tripleContexts = tripleRows.contexts.map { it.toTripleContext() },
            clocks = languages.map { UserPhraseClock(it, pairRows.clocks[it] ?: 0L, tripleRows.clocks[it] ?: 0L) },
        )
    }

    /** One kind's rows: its links, the contexts they follow, and each language's clock. */
    private class KindRows(val links: List<LinkRow>, val contexts: List<ContextRow>, val clocks: Map<String, Long>)

    /**
     * The [stored] links of one kind, [contextWords] words before their last, as version 11 keeps
     * them: merged, stamped as seen at their context's total and their language's clock, then each
     * context cut to [limits] and each language to its cap, as on an import.
     */
    private fun kindRows(
        stored: List<StoredLink>,
        contextWords: Int,
        limits: PhraseBudget.Limits,
        languageOf: (StoredLink) -> String,
    ): KindRows {
        val merged = merge(stored, languageOf)
        val contexts = contexts(merged, contextWords)
        val clocks = clocks(merged)
        var links = merged.map { (key, link) ->
            val context = contexts.getValue(contextOf(key, contextWords))
            LinkRow(
                context.language, context.context, key.last(), link.count, link.lastUsedAt,
                contextSeen = context.seen, languageSeen = clocks.getValue(context.language),
            )
        }
        links = links - links.groupBy { listOf(it.language) + it.context }.flatMap { (key, inContext) ->
            PhraseBudget.overflow(inContext, null, contexts.getValue(key).seen, limits, { it.count }, { it.contextSeen })
        }.toSet()
        links = links - links.groupBy { it.language }.flatMap { (language, inLanguage) ->
            languageGone(
                inLanguage,
                contexts.values.filter { it.language == language }.associate { it.context to it.total },
                clocks.getValue(language),
                kept = null,
            )
        }.toSet()
        val held = links.map { listOf(it.language) + it.context }.toSet()
        return KindRows(links, contexts.filterKeys { it in held }.values.toList(), clocks)
    }

    /**
     * [links] keyed by their language and lower-cased words, those that meet added together; a link
     * whose last two words are one key is left out.
     */
    private fun merge(links: List<StoredLink>, languageOf: (StoredLink) -> String): Map<List<String>, StoredLink> {
        val merged = LinkedHashMap<List<String>, StoredLink>()
        for (link in links) {
            if (link.count <= 0) {
                continue
            }
            val words = link.words.map(PhraseKey::word)
            val (previous, word) = words.takeLast(UserPhrase.PAIR_WORDS)
            if (previous == word) {
                continue
            }
            val key = listOf(languageOf(link)) + words
            val held = merged[key]
            merged[key] = if (held == null) {
                link
            } else {
                StoredLink(held.words, held.count + link.count, maxOf(held.lastUsedAt, link.lastUsedAt))
            }
        }
        return merged
    }

    /**
     * Each context of [links], keyed by the language and the first [contextWords] words: its total
     * and observations its links' counts together, its last use their latest.
     */
    private fun contexts(links: Map<List<String>, StoredLink>, contextWords: Int): Map<List<String>, ContextRow> {
        val contexts = LinkedHashMap<List<String>, ContextRow>()
        for ((key, link) in links) {
            val context = contextOf(key, contextWords)
            val held = contexts[context]
            contexts[context] = if (held == null) {
                ContextRow(
                    key.first(), context.drop(LANGUAGE_FIELDS), link.count.toLong(), link.lastUsedAt, link.count.toLong(),
                )
            } else {
                held.copy(
                    total = held.total + link.count,
                    lastUsedAt = maxOf(held.lastUsedAt, link.lastUsedAt),
                    seen = held.seen + link.count,
                )
            }
        }
        return contexts
    }

    /** The language and the first [contextWords] words of [key]. */
    private fun contextOf(key: List<String>, contextWords: Int): List<String> = key.take(LANGUAGE_FIELDS + contextWords)

    /** Each language's clock: the sum of its links' counts. */
    private fun clocks(links: Map<List<String>, StoredLink>): Map<String, Long> =
        links.entries.groupBy({ it.key.first() }, { it.value.count.toLong() }).mapValues { (_, counts) -> counts.sum() }
}
