// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Learned phrases page's delete button: what it says over the phrases listed. */
class LearnedPhrasesLogicTest {

    @Test
    fun `the delete button names the filtered phrases while narrowed, else all phrases`() {
        assertEquals(
            Keys.DICTIONARY_DELETE_ALL_PHRASES to Keys.DICTIONARY_DELETE_ALL_PHRASES_TITLE,
            phraseDeleteKeys(narrowed = false),
        )
        assertEquals(
            Keys.DICTIONARY_DELETE_FILTERED_PHRASES to Keys.DICTIONARY_DELETE_FILTERED_PHRASES_TITLE,
            phraseDeleteKeys(narrowed = true),
        )
    }
}
