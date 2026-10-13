// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.language.LanguageTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** [PhraseKey] and the language a link takes, against the engine's names for the same keys. */
class PhraseKeyTest {

    @Test
    fun `the unknown language is the engine's`() {
        val header = NativeSource.read(NativeSource.USER_MODEL_HEADER)
        val engine = Regex("""kUnknownLanguage\s*=\s*"([^"]*)"""").find(header)?.groupValues?.get(1)
        assertNotNull("kUnknownLanguage not found in user_model.hpp", engine)
        assertEquals(engine, LanguageTags.UNKNOWN)
    }

    @Test
    fun `a word is keyed in the engine's lower case`() {
        assertEquals("istanbul", PhraseKey.word("İSTANBUL"))
        assertEquals("οδος", PhraseKey.word("ΟΔΟΣ"))
        assertEquals("σας", PhraseKey.word("ΣΑΣ"))
        assertEquals("σ", PhraseKey.word("Σ"))
        assertEquals("don't", PhraseKey.word("DON’T"))
        assertEquals("masă", PhraseKey.word("Masă"))
        val long = "ΟΔΟΣ".repeat(LONGER_THAN_A_WORD)
        assertEquals("οδοσ".repeat(LONGER_THAN_A_WORD - 1) + "οδος", PhraseKey.word(long))
    }

    @Test
    fun `a link takes its word's language, else the commonest locale's, else the fallback`() {
        val languageOf = PhraseKey.languageOfWord(
            mapOf("acasă" to ROMANIAN_TAG, "night" to ENGLISH_TAG, "the" to ENGLISH_TAG), FALLBACK_LANGUAGE,
        )
        assertEquals(LanguageTags.primary(ROMANIAN_TAG), languageOf("acasă"))
        assertEquals(LanguageTags.primary(ENGLISH_TAG), languageOf("unheard"))
        assertEquals(FALLBACK_LANGUAGE, PhraseKey.languageOfWord(emptyMap(), FALLBACK_LANGUAGE)("unheard"))
    }

    @Test
    fun `a locale that names no language counts as none`() {
        val languageOf = PhraseKey.languageOfWord(
            mapOf("acasă" to LanguageTags.UNKNOWN, "x" to "", "night" to ENGLISH_TAG), FALLBACK_LANGUAGE,
        )
        assertEquals(LanguageTags.primary(ENGLISH_TAG), languageOf("acasă"))
        assertEquals(LanguageTags.primary(ENGLISH_TAG), languageOf("x"))
        val none = PhraseKey.languageOfWord(mapOf("acasă" to LanguageTags.UNKNOWN), FALLBACK_LANGUAGE)
        assertEquals(FALLBACK_LANGUAGE, none("acasă"))
    }

    @Test
    fun `the fallback is the first subtype's language that names one, else the device's`() {
        assertEquals(
            LanguageTags.primary(ROMANIAN_TAG),
            KeyboardSubtypes.languageOf(
                listOf(null, "", LanguageTags.UNKNOWN, ROMANIAN_TAG, ENGLISH_TAG), DEVICE_TAG,
            ),
        )
        assertEquals(LanguageTags.primary(DEVICE_TAG), KeyboardSubtypes.languageOf(listOf(null, ""), DEVICE_TAG))
        assertEquals(LanguageTags.primary(DEVICE_TAG), KeyboardSubtypes.languageOf(emptyList(), DEVICE_TAG))
    }

    private companion object {
        const val ROMANIAN_TAG = "ro-RO"
        const val ENGLISH_TAG = "en-US"
        const val DEVICE_TAG = "fr-FR"

        /** A language none of the tags above names. */
        const val FALLBACK_LANGUAGE = "de"

        /** Repeats of a four-letter word that run past the longest word the engine takes. */
        const val LONGER_THAN_A_WORD = 20
    }
}
