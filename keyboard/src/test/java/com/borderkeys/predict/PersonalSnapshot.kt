// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.PhraseKey
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserWord
import com.borderkeys.data.theme.KeyboardPreferences

/** The personal models a row is typed with, and what one taught after a context. */
internal object PersonalSnapshot {

    /** No words, pairs or triples. */
    val EMPTY = PersonalModelLoad(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    /** [model]'s words, without its pairs and triples. */
    fun withoutPhrases(model: PersonalModelLoad) = EMPTY.copy(words = model.words)

    /**
     * Each of [learned]'s words, used the learn-after count of times and last at [at], learned
     * after [context] in the store of the language it is keyed by.
     */
    fun learnedAfter(context: String, learned: Map<String, String>, at: Long): PersonalModelLoad {
        val uses = KeyboardPreferences.DEFAULT_LEARN_AFTER_USES
        val seen = uses.toLong()
        return EMPTY.copy(
            words = learned.map { (language, word) -> UserWord(word, language, uses, at) },
            pairs = learned.map { (language, word) -> UserBigram(language, context, word, uses, at, seen, seen) },
            pairContexts = learned.keys.map { language -> UserPairContext(language, context, seen, at, seen) },
        )
    }

    /** What a history taught about the words after a context. */
    enum class Context {
        /** A learned pair or triple ends in the word. */
        MEANT,

        /** The context learned other words. */
        OTHERS,

        /** The context learned nothing. */
        NONE,
    }

    /**
     * What [model] learned in [language] after [previous2] [previous1] about [word], counting a
     * pair or triple from [learnAfter] uses; the three words keyed as the store keys its rows.
     */
    fun contextOf(
        model: PersonalModelLoad,
        language: String,
        previous2: String,
        previous1: String,
        word: String,
        learnAfter: Int,
    ): Context {
        val before2 = PhraseKey.word(previous2)
        val before1 = PhraseKey.word(previous1)
        val after = PhraseKey.word(word)
        val learnedTriples = model.triples
            .filter {
                it.language == language && it.previousWord2 == before2 && it.previousWord1 == before1 &&
                    it.count >= learnAfter
            }
            .map { it.word }
        val learnedPairs = model.pairs
            .filter { it.language == language && it.previousWord == before1 && it.count >= learnAfter }
            .map { it.word }
        return when {
            after in learnedTriples || after in learnedPairs -> Context.MEANT
            learnedTriples.isNotEmpty() || learnedPairs.isNotEmpty() -> Context.OTHERS
            else -> Context.NONE
        }
    }
}
