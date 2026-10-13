// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.DictionaryRepository
import com.borderkeys.data.MemoryClocks
import com.borderkeys.data.MemoryPairs
import com.borderkeys.data.MemoryTriples
import com.borderkeys.data.PersonalWordDecay
import com.borderkeys.data.PhraseKey
import com.borderkeys.data.PhraseRecorder
import com.borderkeys.data.decayed
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext
import com.borderkeys.data.entity.UserWord
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.typing.LearningBatch
import kotlinx.coroutines.runBlocking

/**
 * The personal dictionary a store holds after its [LearningBatch]es, as the service pushes it
 * into the engine at start: the words used most, and every pair and triple the budget kept, with
 * their contexts' totals, each decayed to when it is read, and each language's clocks.
 */
internal class PersonalSnapshot(
    val words: List<UserWord>,
    val pairs: List<UserBigram>,
    val pairContexts: List<UserPairContext>,
    val triples: List<UserTrigram>,
    val tripleContexts: List<UserTripleContext>,
    val clocks: List<UserPhraseClock>,
) {

    /** What the history taught about the words after a context. */
    enum class Context {
        /** A learned pair or triple ends in the word. */
        MEANT,

        /** The context learned other words. */
        OTHERS,

        /** The context learned nothing. */
        NONE,
    }

    /** The same words, without their pairs and triples. */
    fun withoutPhrases() = PersonalSnapshot(words, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    /** Replaces the personal model of the engine at [handle] with this one. */
    fun loadInto(handle: Long) {
        NativePredictor.nativeLoadUserWords(
            handle,
            Array(words.size) { words[it].word },
            IntArray(words.size) { words[it].count },
            IntArray(words.size) { words[it].deliberateCapitals },
            IntArray(words.size) { words[it].asserted },
        )
        PersonalLinks.load(handle, pairs, pairContexts, triples, tripleContexts, clocks)
    }

    /**
     * What this snapshot learned in [language] after [previous2] [previous1] about [word],
     * counting a pair or triple from [learnAfter] uses; the three words keyed as the store keys
     * its rows.
     */
    fun contextOf(language: String, previous2: String, previous1: String, word: String, learnAfter: Int): Context {
        val before2 = PhraseKey.word(previous2)
        val before1 = PhraseKey.word(previous1)
        val after = PhraseKey.word(word)
        val learnedTriples = triples
            .filter {
                it.language == language && it.previousWord2 == before2 && it.previousWord1 == before1 &&
                    it.count >= learnAfter
            }
            .map { it.word }
        val learnedPairs = pairs
            .filter { it.language == language && it.previousWord == before1 && it.count >= learnAfter }
            .map { it.word }
        return when {
            after in learnedTriples || after in learnedPairs -> Context.MEANT
            learnedTriples.isNotEmpty() || learnedPairs.isNotEmpty() -> Context.OTHERS
            else -> Context.NONE
        }
    }

    companion object {
        val EMPTY = PersonalSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        /**
         * The rows [batches] leave in the store, each batch written as a flush writes it, then read
         * as the service reads them at start under [settings].
         */
        fun of(batches: List<LearningBatch>, settings: KeyboardPreferences, now: Long): PersonalSnapshot {
            val words = LinkedHashMap<String, UserWord>()
            val pairs = MemoryPairs()
            val triples = MemoryTriples()
            val clocks = MemoryClocks()
            val recorder = PhraseRecorder(pairs, triples, clocks)
            runBlocking {
                for (batch in batches) {
                    for (update in batch.updates) {
                        val deliberate = if (update.deliberateCapital) 1 else 0
                        val asserted = if (update.asserted) 1 else 0
                        words[update.word] = words[update.word]?.let {
                            it.copy(
                                locale = update.locale,
                                count = it.count + update.delta,
                                lastUsedAt = update.lastUsedAt,
                                deliberateCapitals = it.deliberateCapitals + deliberate,
                                asserted = it.asserted + asserted,
                            )
                        } ?: UserWord(update.word, update.locale, update.delta, update.lastUsedAt, deliberate, asserted)
                    }
                    recorder.recordPairs(batch.pairs)
                    recorder.recordTriples(batch.triples)
                }
            }
            val halfLife = PersonalWordDecay.halfLifeMillis(settings.unlearnHalfLifeDays)
            return runBlocking {
                PersonalSnapshot(
                    words.values
                        .sortedWith(compareByDescending<UserWord> { it.count }.thenByDescending { it.lastUsedAt })
                        .take(settings.learnedWordLimit)
                        .map { it.decayed(now, halfLife) },
                    DictionaryRepository.loadedPairs(pairs.all()).map { it.decayed(now, halfLife) },
                    pairs.allContexts().map { it.decayed(now, halfLife) },
                    DictionaryRepository.loadedTriples(triples.all()).map { it.decayed(now, halfLife) },
                    triples.allContexts().map { it.decayed(now, halfLife) },
                    clocks.all(),
                )
            }
        }
    }
}
