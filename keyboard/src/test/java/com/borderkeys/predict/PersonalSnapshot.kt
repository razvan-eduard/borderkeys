// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.PhraseKey

/** The personal models a row is typed with, and what one taught after a context. */
internal object PersonalSnapshot {

    /** No words, pairs or triples. */
    val EMPTY = PersonalModelLoad(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    /** [model]'s words, without its pairs and triples. */
    fun withoutPhrases(model: PersonalModelLoad) = EMPTY.copy(words = model.words)

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
