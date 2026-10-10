// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.UserWord
import com.borderkeys.data.theme.KeyboardPreferences

/** Which saved words and phrases have entered the personal dictionary, as the keyboard counts them. */
object PersonalEntries {

    /**
     * The words of [words] that have entered under [settings] as of [now]: chosen on purpose, or
     * used [KeyboardPreferences.learnAfter] times, each decayed count added to those of the forms
     * [fold] makes the same word.
     */
    fun words(
        words: List<UserWord>,
        settings: KeyboardPreferences,
        now: Long,
        fold: (String) -> String,
    ): List<UserWord> {
        val halfLife = PersonalWordDecay.halfLifeMillis(settings.unlearnHalfLifeDays)
        val entered = words.groupBy { fold(it.word) }.filterValues { forms ->
            forms.any { it.asserted > 0 } ||
                forms.sumOf { PersonalWordDecay.decayed(it.count, it.lastUsedAt, now, halfLife) } >=
                settings.learnAfter
        }.keys
        return words.filter { fold(it.word) in entered }
    }

    /** The phrases of [phrases] used [KeyboardPreferences.learnAfter] times, decayed as of [now]. */
    fun phrases(phrases: List<UserPhrase>, settings: KeyboardPreferences, now: Long): List<UserPhrase> {
        val halfLife = PersonalWordDecay.halfLifeMillis(settings.unlearnHalfLifeDays)
        return phrases.filter {
            PersonalWordDecay.decayed(it.count, it.lastUsedAt, now, halfLife) >= settings.learnAfter
        }
    }
}
