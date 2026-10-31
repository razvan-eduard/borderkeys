// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.dao.BlockedWordDao
import com.borderkeys.data.dao.KeyTouchDao
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.dao.UserBigramDao
import com.borderkeys.data.dao.UserCorrectionDao
import com.borderkeys.data.dao.UserPhraseClockDao
import com.borderkeys.data.dao.UserTrigramDao
import com.borderkeys.data.dao.UserWordDao
import com.borderkeys.data.entity.BlockedWord
import com.borderkeys.data.entity.KeyTouch
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext
import com.borderkeys.data.entity.UserWord
import com.borderkeys.data.personal.LinkWrites
import com.borderkeys.data.personal.PersonalLookup
import com.borderkeys.data.personal.PersonalModelHost
import com.borderkeys.data.personal.WorkingCopy
import com.borderkeys.language.LanguageTags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * The personal dictionary: what the keyboard has learned, and what it has been told to forget.
 * Each write, and each read but the flows, runs on [worker] in the order it is asked for, so a
 * read sees every write asked for before it. An edit reaches the personal model of the keyboard
 * running in this process ([attach]): a forget or a restore reads it again, every other edit
 * changes it in place.
 */
class DictionaryRepository internal constructor(
    private val worker: OrderedWorker,
    private val transactor: Transactor,
    private val userWords: UserWordDao,
    private val blockedWords: BlockedWordDao,
    private val userBigrams: UserBigramDao,
    private val userTrigrams: UserTrigramDao,
    private val keyTouches: KeyTouchDao,
    private val userPhraseClocks: UserPhraseClockDao,
    private val userCorrections: UserCorrectionDao,
) {
    /** The repository on [database]'s own tables, run on [worker]. */
    internal constructor(database: BorderKeysDatabase, worker: OrderedWorker) : this(
        worker,
        RoomTransactor(database),
        database.userWordDao(),
        database.blockedWordDao(),
        database.userBigramDao(),
        database.userTrigramDao(),
        database.keyTouchDao(),
        database.userPhraseClockDao(),
        database.userCorrectionDao(),
    )

    private val phraseRecorder = PhraseRecorder(userBigrams, userTrigrams, userPhraseClocks)

    /** The personal model of the keyboard running in this process, which every edit reaches; null for none. */
    @Volatile
    private var host: PersonalModelHost? = null

    /** The keyboard running in this process holds its personal model in [host]. */
    fun attach(host: PersonalModelHost) {
        this.host = host
    }

    /** The keyboard that attached [host] stopped. */
    fun detach(host: PersonalModelHost) {
        if (this.host === host) {
            this.host = null
        }
    }

    val words: Flow<List<UserWord>> = userWords.observeAll()
    val wordCount: Flow<Int> = userWords.observeCount()

    /** How many word rows, one per spelling and language, the *Words kept* limit can take: those not added by hand. */
    val cappedWordCount: Flow<Int> = userWords.observeCountNotAdded()
    val blocked: Flow<List<BlockedWord>> = blockedWords.observeAll()

    /** Every learned pair and triple, most used first. */
    val phrases: Flow<List<UserPhrase>> =
        combine(userBigrams.observeAll(), userTrigrams.observeAll()) { pairs, triples ->
            UserPhrase.merged(pairs, triples)
        }

    val pairCount: Flow<Int> = userBigrams.observeCount()
    val tripleCount: Flow<Int> = userTrigrams.observeCount()

    /** The heatmap's totals in every bucket, as stored. */
    val touches: Flow<List<KeyTouch>> = keyTouches.observeAll()

    /** How many taps the heatmap rests on, each weighing less as it ages, rounded. */
    val touchTaps: Flow<Int> = keyTouches.observeTaps().map { it.roundToInt() }

    fun search(query: String): Flow<List<UserWord>> = userWords.observeMatching(query)

    /** The [limit] word rows used most, by count and then by recency. */
    suspend fun topWords(limit: Int): List<UserWord> = worker.run { userWords.topWords(limit) }

    suspend fun blockedWordSet(): Set<String> = worker.run { blockedWords.allWords().toSet() }

    /**
     * The pairs the personal model loads at service start: each language's links chosen on
     * purpose, then its [MAX_BIGRAMS_IN_MEMORY] most used others, in the order each was first
     * recorded.
     */
    suspend fun pairsToLoad(): List<UserBigram> = worker.run { loadedPairs(userBigrams.all()) }

    /** [pairsToLoad], for the triples and [MAX_TRIGRAMS_IN_MEMORY]. */
    suspend fun triplesToLoad(): List<UserTrigram> = worker.run { loadedTriples(userTrigrams.all()) }

    /** How many times each previous word of a pair has been seen, per language. */
    suspend fun pairContexts(): List<UserPairContext> = worker.run { userBigrams.allContexts() }

    /** How many times each two-word start of a triple has been seen, per language. */
    suspend fun tripleContexts(): List<UserTripleContext> = worker.run { userTrigrams.allContexts() }

    /** Each language's pair clock and triple clock. */
    suspend fun phraseClocks(): List<UserPhraseClock> = worker.run { userPhraseClocks.all() }

    /**
     * What the keyboard loads into the personal model: every word row added by hand and
     * then the [wordLimit] others used most, [pairsToLoad], [pairContexts], [triplesToLoad],
     * [tripleContexts] and [phraseClocks].
     */
    suspend fun personalRows(wordLimit: Int): PersonalRows = worker.run {
        PersonalRows(
            words = userWords.addedWords() + userWords.topWordsNotAdded(wordLimit),
            pairs = pairsToLoad(),
            pairContexts = pairContexts(),
            triples = triplesToLoad(),
            tripleContexts = tripleContexts(),
            clocks = phraseClocks(),
        )
    }

    /**
     * Writes what a learning flush carries, in one transaction: [updates] ([applyLearned]), then
     * [links], the link rows the commits changed, as they hold ([PhraseRecorder.write]), then
     * [touches] ([applyTouches]) under [touchHalfLifeMillis].
     */
    suspend fun applyLearning(
        updates: List<LearnedWord>,
        links: LinkWrites,
        touches: List<KeyTouch> = emptyList(),
        touchHalfLifeMillis: Long = 0L,
    ) = worker.run {
        transactor.inTransaction {
            writeLearned(updates)
            phraseRecorder.write(links)
            writeTouches(touches, touchHalfLifeMillis)
        }
    }

    /** Applies a batch of learning updates in one transaction. */
    suspend fun applyLearned(updates: List<LearnedWord>) = worker.run { writeLearned(updates) }

    private suspend fun writeLearned(updates: List<LearnedWord>) {
        if (updates.isEmpty()) {
            return
        }
        userWords.incrementAll(updates)
    }

    /**
     * Everything the personal dictionary holds that a backup carries, read in one transaction after
     * every write asked for before it.
     */
    suspend fun contents(): DictionaryRows = worker.run {
        transactor.inTransaction {
            DictionaryRows(
                words = userWords.topWords(Int.MAX_VALUE),
                pairs = userBigrams.all(),
                pairContexts = userBigrams.allContexts(),
                triples = userTrigrams.all(),
                tripleContexts = userTrigrams.allContexts(),
                corrections = userCorrections.all(),
                blocked = blockedWords.allWords(),
            )
        }
    }

    /**
     * Merges [rows], read from a backup, into the personal dictionary in one transaction, as a
     * reload of the keyboard's personal model: each of [DictionaryRows.blocked] blocked first, as
     * [block] blocks it; then each word ([UserWordDao.merge]), pair and triple with its context
     * ([PhraseRecorder.restore]) and correction ([UserCorrectionDao.merge]) merged by the larger of
     * each count and stamp, one naming a word blocked here in any case left out. Restoring the same
     * rows again changes nothing. Returns the rows it merged.
     */
    suspend fun restore(rows: DictionaryRows): DictionaryRows = reloading {
        worker.run {
            transactor.inTransaction {
                rows.blocked.forEach { blockInTables(it) }
                val blocked = blockedWords.allWords()
                fun allowed(vararg words: String) = words.all { casesOf(it, blocked).isEmpty() }
                val merged = DictionaryRows(
                    words = rows.words.filter { allowed(it.word) },
                    pairs = rows.pairs.filter { allowed(it.previousWord, it.word) },
                    pairContexts = rows.pairContexts,
                    triples = rows.triples.filter { allowed(it.previousWord2, it.previousWord1, it.word) },
                    tripleContexts = rows.tripleContexts,
                    corrections = rows.corrections.filter { allowed(it.previousWord, it.word) },
                    blocked = rows.blocked,
                )
                merged.words.forEach { userWords.merge(it) }
                phraseRecorder.restore(merged.pairs, merged.pairContexts, merged.triples, merged.tripleContexts)
                merged.corrections.forEach { userCorrections.merge(it) }
                merged
            }
        }
    }

    /**
     * Halves the stored count of every word, pair and triple, and the total of every context,
     * nobody has written in a [halfLifeMillis] or longer, then deletes the words written once and
     * unused for [unconfirmedLifeMillis] (none when it is null) and those past the [keep] used
     * most among the words not added by hand, with the phrases of those no case of which is left,
     * and prunes each language left past its cap ([PhraseRecorder.pruneLanguages]); the keyboard's
     * personal model is read again.
     */
    suspend fun decayStaleEntries(
        keep: Int,
        halfLifeMillis: Long,
        unconfirmedLifeMillis: Long?,
        now: Long = System.currentTimeMillis(),
    ) {
        val cutoff = now - halfLifeMillis
        val write = suspend {
            worker.run {
                transactor.inTransaction {
                    userWords.decayStale(cutoff, now)
                    userBigrams.decayStale(cutoff, now)
                    userBigrams.decayStaleContexts(cutoff, now)
                    userTrigrams.decayStale(cutoff, now)
                    userTrigrams.decayStaleContexts(cutoff, now)
                    val deleted = Deleted()
                    if (unconfirmedLifeMillis != null) {
                        val unconfirmedCutoff = now - unconfirmedLifeMillis
                        val unconfirmed = userWords.unconfirmedBefore(unconfirmedCutoff)
                        userWords.deleteUnconfirmedBefore(unconfirmedCutoff)
                        forgetPhrasesOfGone(unconfirmed, deleted)
                    }
                    deleteWordsBeyond(keep, deleted)
                    phraseRecorder.pruneLanguages()
                }
            }
        }
        reloading { write() }
    }

    /**
     * Deletes the pairs and triples used at most [PhraseBudget.PURGE_MOST_USES] times that hold a
     * word [learnable] answers false for. The verdicts are taken with no transaction open; the
     * delete then reads those phrases again, in one transaction, and a phrase the first read did not
     * see is kept. [learnable] answers for each word of a list, in order, and is never asked about
     * the sentence start; a word it gives no answer for is kept. The keyboard's personal model drops
     * them too. Returns what it deleted.
     */
    suspend fun purgeUnlearnable(learnable: suspend (List<String>) -> List<Boolean>): PhraseRecorder.Dropped =
        update({ purge(learnable) }) { copy, purged ->
            copy.lookup.drop(purged.pairs, purged.triples)
            LinkWrites(gonePairs = purged.pairs, goneTriples = purged.triples)
        }

    /** [purgeUnlearnable] in the personal dictionary. */
    private suspend fun purge(learnable: suspend (List<String>) -> List<Boolean>): PhraseRecorder.Dropped = worker.run {
        val pairs = userBigrams.usedAtMost(PhraseBudget.PURGE_MOST_USES)
        val triples = userTrigrams.usedAtMost(PhraseBudget.PURGE_MOST_USES)
        val words = (
            pairs.flatMap { listOf(it.previousWord, it.word) } +
                triples.flatMap { listOf(it.previousWord2, it.previousWord1, it.word) }
            ).filter { it != UserBigram.SENTENCE_START }.distinct()
        if (words.isEmpty()) {
            return@run PhraseRecorder.Dropped()
        }
        val unlearnable = words.zip(learnable(words)).filterNot { it.second }.mapTo(HashSet()) { it.first }
        if (unlearnable.isEmpty()) {
            return@run PhraseRecorder.Dropped()
        }
        val judged = (pairs.map { it.row() } + triples.map { it.row() }).mapTo(HashSet(), ::identity)
        transactor.inTransaction {
            val gonePairs = userBigrams.usedAtMost(PhraseBudget.PURGE_MOST_USES).filter {
                identity(it.row()) in judged && (it.previousWord in unlearnable || it.word in unlearnable)
            }
            val goneTriples = userTrigrams.usedAtMost(PhraseBudget.PURGE_MOST_USES).filter {
                identity(it.row()) in judged &&
                    (it.previousWord2 in unlearnable || it.previousWord1 in unlearnable || it.word in unlearnable)
            }
            phraseRecorder.purge(gonePairs, goneTriples)
            PhraseRecorder.Dropped(gonePairs, goneTriples)
        }
    }

    /** The columns that name a pair or triple: its language, the words before its last, its last word. */
    private fun identity(row: LinkRow) = Triple(row.language, row.context, row.word)

    /**
     * Deletes the word rows past the [keep] used most, with the phrases of the words no case of
     * which is left; the keyboard's personal model takes them out too.
     */
    suspend fun keepWords(keep: Int) {
        val write = suspend {
            worker.run { transactor.inTransaction { Deleted().also { deleteWordsBeyond(keep, it) } } }
        }
        update(write, change = ::removedFrom)
    }

    /**
     * Deletes the word rows past the [keep] used most, with the phrases of the words no case of
     * which is left, into [deleted].
     */
    private suspend fun deleteWordsBeyond(keep: Int, deleted: Deleted) {
        val beyond = userWords.rowsBeyond(keep)
        for (row in beyond) {
            userWords.delete(row.word, row.language)
        }
        deleted.rows += beyond
        forgetPhrasesOfGone(beyond.map { it.word }, deleted)
    }

    /** Forgets the phrases of each of [words] that no learned word is any longer, in any case, into [deleted]. */
    private suspend fun forgetPhrasesOfGone(words: Collection<String>, deleted: Deleted) {
        if (words.isEmpty()) {
            return
        }
        val left = userWords.allWords().mapTo(HashSet(), PhraseKey::word)
        for (key in words.mapTo(LinkedHashSet(), PhraseKey::word)) {
            if (key !in left) {
                phraseRecorder.forget(key)
                deleted.forgotten += key
            }
        }
    }

    /** The word rows a write deleted, as the personal dictionary held them, and the words whose phrases it forgot. */
    private class Deleted {
        val rows = ArrayList<UserWord>()
        val forgotten = LinkedHashSet<String>()
    }

    /**
     * [deleted] made in the personal model [copy]: each row taken out of its word
     * ([PersonalLookup.removed]), and the pairs and triples of each forgotten word forgotten
     * ([PersonalLookup.forgetLinks]). Returns the link rows that changed.
     */
    private fun removedFrom(copy: WorkingCopy, deleted: Deleted): LinkWrites {
        copy.put(copy.lookup.removed(deleted.rows))
        return deleted.forgotten.fold(LinkWrites()) { writes, key -> writes.then(copy.lookup.forgetLinks(key)) }
    }

    /** Forgets a word and every phrase it was part of. */
    suspend fun forget(word: String) = forget(listOf(word))

    /**
     * Forgets [word] in every case it was learned in, and its phrases in any case, held or not, and
     * what of it waits to be written; the keyboard's personal model is read again. Returns whether
     * the personal dictionary held any case of it.
     */
    suspend fun forgetEveryCase(word: String): Boolean = reloading {
        worker.run {
            val cases = casesOf(word, userWords.allWords())
            deleteWords((cases + word).distinct())
            cases.isNotEmpty()
        }
    }

    /**
     * Forgets [words], and every phrase and learned correction each is part of in any case and in
     * every language, in one transaction, and what of them waits to be written; the keyboard's
     * personal model is read again.
     */
    suspend fun forget(words: Collection<String>) {
        if (words.isEmpty()) {
            return
        }
        reloading { worker.run { deleteWords(words) } }
    }

    /**
     * Deletes [words], and every phrase and learned correction each is part of in any case and in
     * every language, in one transaction.
     */
    private suspend fun deleteWords(words: Collection<String>) {
        if (words.isEmpty()) {
            return
        }
        transactor.inTransaction {
            words.forEach { userWords.delete(it) }
            for (key in words.mapTo(LinkedHashSet(), PhraseKey::word)) {
                phraseRecorder.forget(key)
                userCorrections.deleteWord(key)
            }
        }
    }

    /**
     * Forgets [phrase]: a pair with every triple that runs through it, or one triple, whose pairs
     * stay. The words stay.
     */
    suspend fun forgetPhrase(phrase: UserPhrase) = forgetPhrases(listOf(phrase))

    /** Forgets [phrases] as [forgetPhrase] does, in one transaction; the keyboard's personal model is read again. */
    suspend fun forgetPhrases(phrases: Collection<UserPhrase>) {
        if (phrases.isEmpty()) {
            return
        }
        reloading { worker.run { deletePhrases(phrases) } }
    }

    /** Deletes [phrases] as [forgetPhrase] forgets them, in one transaction. */
    private suspend fun deletePhrases(phrases: Collection<UserPhrase>) {
        transactor.inTransaction {
            for (phrase in phrases) {
                if (phrase.isPair) {
                    val (previousWord, word) = phrase.words
                    userBigrams.inContext(phrase.language, previousWord)
                        .firstOrNull { it.word == word }
                        ?.let { phraseRecorder.forgetPair(it) }
                } else {
                    val (previousWord2, previousWord1, word) = phrase.words
                    userTrigrams.inContext(phrase.language, previousWord2, previousWord1)
                        .firstOrNull { it.word == word }
                        ?.let { phraseRecorder.forgetTriple(it) }
                }
            }
        }
    }

    /**
     * Forgets every pair and triple, with their contexts and clocks; the keyboard's personal model
     * is read again. The words stay.
     */
    suspend fun forgetAllPhrases() = reloading {
        worker.run { transactor.inTransaction { phraseRecorder.forgetAll() } }
    }

    /**
     * Forgets every learned word, pair, triple and correction, and the heatmap, with what waited to
     * be written; the keyboard's personal model and its heatmap are read again.
     */
    suspend fun forgetEverything() {
        reloading {
            worker.run {
                transactor.inTransaction {
                    userWords.deleteAll()
                    phraseRecorder.forgetAll()
                    userCorrections.deleteAll()
                    keyTouches.deleteAll()
                }
            }
        }
        host?.forgetTouches {}
    }

    /** The heatmap's totals for [bucket], as stored. */
    suspend fun touchesIn(bucket: String): List<KeyTouch> = worker.run { keyTouches.inBucket(bucket) }

    /**
     * Adds [touches], totals of taps since the last write, to what is stored, the stored totals
     * first weighed down by their age.
     */
    suspend fun applyTouches(touches: List<KeyTouch>, halfLifeMillis: Long) = worker.run {
        if (touches.isEmpty()) {
            return@run
        }
        transactor.inTransaction { writeTouches(touches, halfLifeMillis) }
    }

    private suspend fun writeTouches(touches: List<KeyTouch>, halfLifeMillis: Long) {
        for (touch in touches) {
            keyTouches.upsert(
                KeyTouches.merged(keyTouches.find(touch.bucket, touch.code), touch, halfLifeMillis),
            )
        }
    }

    /** Forgets where taps land on every key, and the taps waiting to be written. The words stay. */
    suspend fun forgetTouchPattern() {
        val write = suspend { worker.run { keyTouches.deleteAll() } }
        val running = host ?: return write()
        running.forgetTouches(write)
    }

    /**
     * Refuses a word permanently and removes whatever was learned about it in any case: its rows of
     * every case and language, its phrases and its learned corrections; the keyboard's personal
     * model takes it out too, with what of it waits to be written, and refuses it.
     */
    suspend fun block(word: String) {
        val write = suspend { worker.run { transactor.inTransaction { blockInTables(word) } } }
        update(write, listOf(word), ::removedFrom)
        host?.blockedChanged()
    }

    /** [block] in the personal dictionary; the caller holds the transaction. Returns what it deleted. */
    private suspend fun blockInTables(word: String): Deleted {
        blockedWords.insert(BlockedWord(word))
        val deleted = Deleted()
        for (case in casesOf(word, userWords.allWords())) {
            deleted.rows += userWords.rows(case)
            userWords.delete(case)
        }
        phraseRecorder.forget(word)
        userCorrections.deleteWord(PhraseKey.word(word))
        deleted.forgotten += PhraseKey.word(word)
        return deleted
    }

    /** Lets a blocked word be learned and suggested again. */
    suspend fun unblock(word: String) {
        worker.run { blockedWords.delete(word) }
        host?.blockedChanged()
    }

    /** What [add] or [addPhrase] did. */
    enum class Added { ADDED, BLOCKED }

    /**
     * Adds [word] by hand in [locale]'s primary language subtag as one chosen use at [now]: its
     * count, its asserted count and its added count each up by one, a first-letter capital counted
     * as the user's.
     * A word blocked in any case is unblocked first when [unblock], else left as it is with
     * [Added.BLOCKED] returned and nothing written.
     */
    suspend fun add(word: String, locale: String, now: Long, unblock: Boolean): Added =
        addWords(listOf(word), locale, now, unblock, phrase = false)

    /**
     * Adds [words], two or three, as a phrase by hand: each word as [add] adds one, and the pair
     * of two, or the two pairs and the triple of three, under [locale]'s primary language subtag
     * ([PhraseRecorder.addPhrase]); a blocked word among them as [add] handles one.
     */
    suspend fun addPhrase(words: List<String>, locale: String, now: Long, unblock: Boolean): Added =
        addWords(words, locale, now, unblock, phrase = true)

    private suspend fun addWords(
        words: List<String>,
        locale: String,
        now: Long,
        unblock: Boolean,
        phrase: Boolean,
    ): Added {
        val language = LanguageTags.primary(locale)
        var unblocked = false
        val write = suspend {
            worker.run {
                transactor.inTransaction {
                    val stored = blockedWords.allWords()
                    val blocked = words.flatMap { casesOf(it, stored) }.distinct()
                    if (blocked.isNotEmpty()) {
                        if (!unblock) {
                            return@inTransaction Added.BLOCKED
                        }
                        blocked.forEach { blockedWords.delete(it) }
                        unblocked = true
                    }
                    for (word in words) {
                        userWords.increment(
                            word, language, delta = 1, lastUsedAt = now,
                            deliberateCapitalDelta = if (WordShape.startsWithCapital(word)) 1 else 0,
                            assertedDelta = 1, addedDelta = 1,
                        )
                    }
                    if (phrase) {
                        phraseRecorder.addPhrase(language, words, now)
                    }
                    Added.ADDED
                }
            }
        }
        val added = update(write) { copy, added ->
            if (added != Added.ADDED) {
                return@update LinkWrites()
            }
            copy.put(copy.lookup.added(language, words, now))
            if (phrase) copy.lookup.addedPhrase(language, words, now) else LinkWrites()
        }
        if (unblocked) {
            host?.blockedChanged()
        }
        return added
    }

    /**
     * Runs [write], then [change] on the personal model of the keyboard running in this process
     * with what [write] returned, the learning waiting there dropping [forgotten]
     * ([PersonalModelHost.update]); with none running, [write] alone.
     */
    private suspend fun <T> update(
        write: suspend () -> T,
        forgotten: Collection<String> = emptyList(),
        change: (WorkingCopy, T) -> LinkWrites,
    ): T {
        val running = host ?: return write()
        return running.update(write, forgotten, change)
    }

    /**
     * Runs [edit] as a reload of the personal model of the keyboard running in this process
     * ([PersonalModelHost.reload]); with none running, [edit] alone.
     */
    private suspend fun <T> reloading(edit: suspend () -> T): T {
        val running = host ?: return edit()
        return running.reload(edit)
    }

    /** Whether [word] is blocked, in any case. */
    suspend fun isBlocked(word: String): Boolean = isBlocked(listOf(word))

    /** Whether any of [words] is blocked, in any case. */
    suspend fun isBlocked(words: List<String>): Boolean = worker.run {
        val stored = blockedWords.allWords()
        words.any { casesOf(it, stored).isNotEmpty() }
    }

    companion object {
        /** The pairs of one language a load reads besides those chosen on purpose: what a language keeps. */
        const val MAX_BIGRAMS_IN_MEMORY = PhraseBudget.LANGUAGE_CAP

        /** [MAX_BIGRAMS_IN_MEMORY], for the triples. */
        const val MAX_TRIGRAMS_IN_MEMORY = PhraseBudget.LANGUAGE_CAP

        /** Of [pairs], the ones [pairsToLoad] gives, the chosen first and the rest in their order. */
        fun loadedPairs(pairs: List<UserBigram>): List<UserBigram> =
            mostUsedPerLanguage(pairs, MAX_BIGRAMS_IN_MEMORY, UserBigram::language, UserBigram::count) { it.asserted > 0 }

        /** Of [triples], the ones [triplesToLoad] gives, the chosen first and the rest in their order. */
        fun loadedTriples(triples: List<UserTrigram>): List<UserTrigram> =
            mostUsedPerLanguage(triples, MAX_TRIGRAMS_IN_MEMORY, UserTrigram::language, UserTrigram::count) { it.asserted > 0 }

        /**
         * Of [links], every one [assertedOf] answers true for, first, then each language's [most]
         * others with the highest counts, in the links' order.
         */
        private fun <T> mostUsedPerLanguage(
            links: List<T>,
            most: Int,
            languageOf: (T) -> String,
            countOf: (T) -> Int,
            assertedOf: (T) -> Boolean,
        ): List<T> {
            val (chosen, others) = links.partition(assertedOf)
            val kept = others.groupBy(languageOf).values.flatMap { inLanguage ->
                if (inLanguage.size <= most) inLanguage else inLanguage.sortedByDescending(countOf).take(most)
            }.toHashSet()
            return chosen + others.filter { it in kept }
        }
    }
}

/** The entries of [stored] that are [word] in some case: "This", "this" and "THIS" are one word. */
internal fun casesOf(word: String, stored: Collection<String>): List<String> =
    stored.filter { it.equals(word, ignoreCase = true) }
