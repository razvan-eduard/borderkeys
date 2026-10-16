// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

/** What a phrase added by hand looks like: two or three words, keyed as the store keys them. */
object PhraseShape {

    /**
     * The words of [text] as a phrase to add: two or three, split on whitespace, each a word by
     * [WordShape.isWord], no two in a row one key ([PhraseKey.word]); null otherwise.
     */
    fun wordsOf(text: String): List<String>? {
        val words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.size !in UserPhrase.PAIR_WORDS..UserPhrase.TRIPLE_WORDS || !words.all(WordShape::isWord)) {
            return null
        }
        val keys = words.map(PhraseKey::word)
        return if (keys.zipWithNext().any { (before, after) -> before == after }) null else words
    }

    /** Whether one of [phrases] is [words], keyed, in [language]. */
    fun isListed(words: List<String>, language: String, phrases: List<UserPhrase>): Boolean {
        val keys = words.map(PhraseKey::word)
        return phrases.any { it.language == language && it.words == keys }
    }

    private val WHITESPACE = Regex("\\s+")
}
