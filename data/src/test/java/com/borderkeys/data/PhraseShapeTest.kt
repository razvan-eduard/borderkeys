// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** [PhraseShape]: what reads as a phrase to add, and whether it is already listed. */
@RunWith(SharedNativeTextRunner::class)
@Config(sdk = [SharedNativeTextRunner.SDK])
class PhraseShapeTest {

    @Test
    fun `a phrase to add is two or three words, each a word, no two in a row the same`() {
        assertEquals(listOf("see", "you"), PhraseShape.wordsOf("  see   you "))
        assertEquals(listOf("We", "go", "home"), PhraseShape.wordsOf("We go home"))
        for (text in listOf("", "hello", "we go home now", "see 2", "go go", "Go go")) {
            assertNull(text, PhraseShape.wordsOf(text))
        }
    }

    @Test
    fun `a phrase already listed in the language, keyed, is found, in another language it is not`() {
        val listed = listOf(UserPhrase("en", listOf("see", "you"), 3, 0L))
        assertTrue(PhraseShape.isListed(listOf("See", "you"), "en", listed))
        assertFalse(PhraseShape.isListed(listOf("See", "you"), "ro", listed))
        assertFalse(PhraseShape.isListed(listOf("see", "you", "soon"), "en", listed))
    }
}
