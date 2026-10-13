// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.DictionaryRepository
import com.borderkeys.data.PersonalRows
import com.borderkeys.data.PersonalWordDecay
import com.borderkeys.data.decayed
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext
import com.borderkeys.data.entity.UserWord
import com.borderkeys.data.theme.KeyboardPreferences

/**
 * What the engine's personal model is replaced with: the learned words, pairs and triples, their
 * contexts' totals and each language's clocks.
 */
internal data class PersonalModelLoad(
    val words: List<UserWord>,
    val pairs: List<UserBigram>,
    val pairContexts: List<UserPairContext>,
    val triples: List<UserTrigram>,
    val tripleContexts: List<UserTripleContext>,
    val clocks: List<UserPhraseClock>,
) {

    /** Replaces the personal model of the engine at [handle] with this one: the words, then the links. */
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

    companion object {

        /**
         * The model [dictionary] loads under [settings] at [now]: its rows as
         * [DictionaryRepository.personalRows] reads them up to [KeyboardPreferences.learnedWordLimit]
         * words, made into a model by [of].
         */
        suspend fun read(
            dictionary: DictionaryRepository,
            settings: KeyboardPreferences,
            offensiveWords: Set<String>,
            now: Long,
        ): PersonalModelLoad = of(dictionary.personalRows(settings.learnedWordLimit), settings, offensiveWords, now)

        /**
         * The model [rows] load under [settings] at [now]: with the offensive-word switch on, the
         * words, pairs and triples holding one of [offensiveWords] left out; every word, pair,
         * triple and context total decayed for how long it has sat unused; the clocks as read.
         */
        fun of(
            rows: PersonalRows,
            settings: KeyboardPreferences,
            offensiveWords: Set<String>,
            now: Long,
        ): PersonalModelLoad {
            val halfLife = PersonalWordDecay.halfLifeMillis(settings.unlearnHalfLifeDays)
            val hidden = if (settings.blockOffensiveWords) offensiveWords else emptySet()
            fun shown(word: String) = hidden.isEmpty() || WordFold.fold(word) !in hidden
            return PersonalModelLoad(
                words = rows.words.filter { shown(it.word) }.map { it.decayed(now, halfLife) },
                pairs = rows.pairs
                    .filter { shown(it.previousWord) && shown(it.word) }
                    .map { it.decayed(now, halfLife) },
                pairContexts = rows.pairContexts.map { it.decayed(now, halfLife) },
                triples = rows.triples
                    .filter { shown(it.previousWord2) && shown(it.previousWord1) && shown(it.word) }
                    .map { it.decayed(now, halfLife) },
                tripleContexts = rows.tripleContexts.map { it.decayed(now, halfLife) },
                clocks = rows.clocks,
            )
        }
    }
}
