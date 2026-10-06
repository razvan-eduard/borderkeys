// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.i18n.Keys
import org.junit.Assert.assertEquals
import org.junit.Test

class RareWordsReachTest {

    @Test
    fun `every step has a name`() {
        assertEquals(
            KeyboardPreferences.RARE_WORDS_ALL - KeyboardPreferences.RARE_WORDS_LISTED + 1,
            RARE_WORDS_STEP_KEYS.size,
        )
    }

    @Test
    fun `the dictionaries with rare words are named apart from the rest`() {
        val lines = rareWordsReach(
            listOf(PackReach("English", true), PackReach("Romanian", false), PackReach("German", true)),
        )
        assertEquals(
            listOf(
                ReachLine(Keys.CORRECTIONS_RARE_WORDS_AFFECTS, "English, German"),
                ReachLine(Keys.CORRECTIONS_RARE_WORDS_UNAFFECTED, "Romanian"),
            ),
            lines,
        )
    }

    @Test
    fun `with every dictionary carrying rare words only the affected line is shown`() {
        assertEquals(
            listOf(ReachLine(Keys.CORRECTIONS_RARE_WORDS_AFFECTS, "English")),
            rareWordsReach(listOf(PackReach("English", true))),
        )
    }

    @Test
    fun `with no dictionary carrying rare words the note says the setting changes nothing`() {
        val none = listOf(ReachLine(Keys.CORRECTIONS_RARE_WORDS_NONE))
        assertEquals(none, rareWordsReach(listOf(PackReach("Romanian", false))))
        assertEquals(none, rareWordsReach(emptyList()))
    }
}
