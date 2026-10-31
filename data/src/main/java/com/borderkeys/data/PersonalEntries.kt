// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.UserWord
import com.borderkeys.data.personal.PersonalLookup
import com.borderkeys.data.personal.PersonalSpelling
import com.borderkeys.data.theme.KeyboardPreferences

/** Which saved words and phrases have entered the personal dictionary, as the keyboard counts them. */
object PersonalEntries {

    /** A word as the Learned words page lists it under one language. */
    data class ListedWord(
        /** The word, as [PhraseKey.word] keys it. */
        val key: String,
        /** A primary language subtag, or [UserWord.UNPLACED] for its uses in no language known. */
        val language: String,
        /** The spelling served for it ([PersonalSpelling.merge]). */
        val spelling: String,
        /** Its uses in [language], decayed ([PersonalLookup.WordCounts.inLanguage]). */
        val count: Int,
        /** How many times it was added by hand, in any language. */
        val added: Int,
        /** Every spelling the personal dictionary keeps for it, in any case. */
        val spellings: List<String>,
    )

    /**
     * The words of [words] that have entered under [settings] as of [now], each under every
     * language it has rows in, in the order first met: its case forms counted together by
     * [PersonalSpelling], each row's count decayed, and its count in a language its uses there
     * plus its uses in no language known ([PersonalLookup.WordCounts.inLanguage]), or those alone
     * under [UserWord.UNPLACED]. A word has entered a language once it was chosen on purpose in
     * any, or used [KeyboardPreferences.learnAfter] times there.
     */
    fun byLanguage(words: List<UserWord>, settings: KeyboardPreferences, now: Long): List<ListedWord> {
        val halfLife = PersonalWordDecay.halfLifeMillis(settings.unlearnHalfLifeDays)
        val listed = ArrayList<ListedWord>()
        for ((key, rows) in words.groupBy { PhraseKey.word(it.word) }) {
            val uses = rows.map { PersonalWordDecay.decayed(it.count, it.lastUsedAt, now, halfLife) }
            val merged = merged(key, rows, uses)
            val placed = HashMap<String, Int>()
            var unplaced = 0
            for ((row, count) in rows.zip(uses)) {
                if (row.language == UserWord.UNPLACED) {
                    unplaced += count
                } else {
                    placed[row.language] = (placed[row.language] ?: 0) + count
                }
            }
            val counts = PersonalLookup.WordCounts(uses.sum(), placed, unplaced)
            val spellings = rows.map { it.word }.distinct()
            for (language in rows.map { it.language }.distinct()) {
                val count = if (language == UserWord.UNPLACED) counts.unplaced else counts.inLanguage(language)
                if (merged.asserted > 0 || count >= settings.learnAfter) {
                    listed += ListedWord(key, language, merged.spelling, count, merged.added, spellings)
                }
            }
        }
        return listed
    }

    /**
     * The spelling served for each word of [words] under [settings] as of [now], by its key
     * ([PhraseKey.word]): its case forms in every language as one ([PersonalSpelling.merge]), each
     * row's count decayed.
     */
    fun spellings(words: List<UserWord>, settings: KeyboardPreferences, now: Long): Map<String, String> {
        val halfLife = PersonalWordDecay.halfLifeMillis(settings.unlearnHalfLifeDays)
        return words.groupBy { PhraseKey.word(it.word) }.mapValues { (key, rows) ->
            merged(key, rows, rows.map { PersonalWordDecay.decayed(it.count, it.lastUsedAt, now, halfLife) }).spelling
        }
    }

    /**
     * The word keyed [key] from its [rows], each counted [uses], as one entry: the rows of each
     * spelling one form ([PersonalSpelling.Form.plus]), then the forms merged ([PersonalSpelling.merge]).
     */
    private fun merged(key: String, rows: List<UserWord>, uses: List<Int>): PersonalSpelling.Merged {
        val forms = LinkedHashMap<String, PersonalSpelling.Form>()
        for ((row, count) in rows.zip(uses)) {
            val form =
                PersonalSpelling.Form(row.word, count, row.lastUsedAt, row.deliberateCapitals, row.asserted, row.added)
            forms[row.word] = forms[row.word]?.plus(form) ?: form
        }
        return PersonalSpelling.merge(key, forms.values.toList())
    }

    /** Where a listed word or phrase came from: learned by use, added by hand, or either. */
    enum class Origin { ANY, LEARNED, ADDED }

    /** The entries of [items] from [origin]: added by hand when [addedOf] is above zero. */
    fun <T> ofOrigin(items: List<T>, origin: Origin, addedOf: (T) -> Int): List<T> = when (origin) {
        Origin.ANY -> items
        Origin.LEARNED -> items.filter { addedOf(it) == 0 }
        Origin.ADDED -> items.filter { addedOf(it) > 0 }
    }

    /**
     * The phrases of [phrases] that have entered under [settings] as of [now]: chosen on purpose,
     * or used [KeyboardPreferences.learnAfter] times, decayed.
     */
    fun phrases(phrases: List<UserPhrase>, settings: KeyboardPreferences, now: Long): List<UserPhrase> {
        val halfLife = PersonalWordDecay.halfLifeMillis(settings.unlearnHalfLifeDays)
        return phrases.filter {
            it.asserted > 0 || PersonalWordDecay.decayed(it.count, it.lastUsedAt, now, halfLife) >= settings.learnAfter
        }
    }
}
