// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.UserWord
import com.borderkeys.data.theme.KeyboardPreferences

/** Which saved words and phrases have entered the personal dictionary, as the keyboard counts them. */
object PersonalEntries {

    /**
     * The words of [words] that have entered under [settings] as of [now]: chosen on purpose, or
     * used [KeyboardPreferences.learnAfter] times, each decayed count added to those of its other
     * cases ([PhraseKey.word]).
     */
    fun words(words: List<UserWord>, settings: KeyboardPreferences, now: Long): List<UserWord> {
        val halfLife = PersonalWordDecay.halfLifeMillis(settings.unlearnHalfLifeDays)
        val entered = words.groupBy { PhraseKey.word(it.word) }.filterValues { forms ->
            forms.any { it.asserted > 0 } ||
                forms.sumOf { PersonalWordDecay.decayed(it.count, it.lastUsedAt, now, halfLife) } >=
                settings.learnAfter
        }.keys
        return words.filter { PhraseKey.word(it.word) in entered }
    }

    /** Where a listed word came from: learned by use, added by hand, or either. */
    enum class Origin { ANY, LEARNED, ADDED }

    /** The words of [words] from [origin]: added by hand when [UserWord.added] is above zero. */
    fun ofOrigin(words: List<UserWord>, origin: Origin): List<UserWord> = when (origin) {
        Origin.ANY -> words
        Origin.LEARNED -> words.filter { it.added == 0 }
        Origin.ADDED -> words.filter { it.added > 0 }
    }

    /** The phrases of [phrases] used [KeyboardPreferences.learnAfter] times, decayed as of [now]. */
    fun phrases(phrases: List<UserPhrase>, settings: KeyboardPreferences, now: Long): List<UserPhrase> {
        val halfLife = PersonalWordDecay.halfLifeMillis(settings.unlearnHalfLifeDays)
        return phrases.filter {
            PersonalWordDecay.decayed(it.count, it.lastUsedAt, now, halfLife) >= settings.learnAfter
        }
    }
}
