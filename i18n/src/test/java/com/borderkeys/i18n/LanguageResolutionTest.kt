// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

/** What happens when the phone asks for a language BorderKeys does not have. */
class LanguageResolutionTest {

    private val shipped = listOf("en", "ro")

    @Test
    fun `the phone's language wins when it is shipped`() {
        assertEquals("ro", LanguageResolution.resolve(listOf("ro-RO"), shipped))
    }

    @Test
    fun `an unshipped language falls back to english`() {
        assertEquals("en", LanguageResolution.resolve(listOf("hu-HU"), shipped))
    }

    @Test
    fun `the second preference is used when the first is missing`() {
        assertEquals("ro", LanguageResolution.resolve(listOf("ca-ES", "ro-RO"), shipped))
    }

    @Test
    fun `a region falls back to the bare language`() {
        assertEquals("pt", LanguageResolution.resolve(listOf("pt-BR"), listOf("en", "pt")))
    }

    @Test
    fun `a regional catalogue is preferred over the bare one`() {
        assertEquals("pt-br", LanguageResolution.resolve(listOf("pt-BR"), listOf("en", "pt", "pt-br")))
    }

    @Test
    fun `a hand-picked language beats the phone`() {
        assertEquals("en", LanguageResolution.resolve(listOf("ro-RO"), shipped, override = "en"))
    }

    @Test
    fun `an override that is gone falls back to the phone`() {
        assertEquals("ro", LanguageResolution.resolve(listOf("ro-RO"), shipped, override = "hu"))
    }

    @Test
    fun `no preferences at all gives english`() {
        assertEquals("en", LanguageResolution.resolve(emptyList(), shipped))
    }

    @Test
    fun `underscores and scripts are tolerated`() {
        assertEquals("ro", LanguageResolution.resolve(listOf("ro_RO"), shipped))
        assertEquals("ro", LanguageResolution.resolve(listOf("ro-Latn-RO"), shipped))
    }

    @Test
    fun `indonesian and filipino resolve under either code android uses`() {
        val available = listOf("en", "id", "fil")
        assertEquals("id", LanguageResolution.resolve(listOf("id-ID"), available))
        assertEquals("id", LanguageResolution.resolve(listOf("in-ID"), available))
        assertEquals("fil", LanguageResolution.resolve(listOf("fil-PH"), available))
        assertEquals("fil", LanguageResolution.resolve(listOf("tl-PH"), available))
    }

    @Test
    fun `simplified chinese resolves to zh-cn and traditional does not`() {
        val available = listOf("en", "zh-cn")
        assertEquals("zh-cn", LanguageResolution.resolve(listOf("zh-Hans-CN"), available))
        assertEquals("zh-cn", LanguageResolution.resolve(listOf("zh-CN"), available))
        assertEquals("zh-cn", LanguageResolution.resolve(listOf("zh-Hans"), available))
        assertEquals("zh-cn", LanguageResolution.resolve(listOf("zh-SG"), available))
        assertEquals("en", LanguageResolution.resolve(listOf("zh-Hant-TW"), available))
        assertEquals("en", LanguageResolution.resolve(listOf("zh-TW"), available))
        assertEquals("en", LanguageResolution.resolve(listOf("zh-Hant-HK"), available))
    }

    @Test
    fun `european portuguese settles for the shipped portuguese`() {
        assertEquals("pt", LanguageResolution.resolve(listOf("pt-PT"), listOf("en", "pt")))
    }

    @Test
    fun `a numeric region is a region`() {
        assertEquals("es", LanguageResolution.resolve(listOf("es-419"), listOf("en", "es")))
    }

    @Test
    fun `persian, arabic and hebrew are written right to left`() {
        for (language in listOf("fa", "ar", "he", "iw", "fa-IR")) {
            assertEquals(language, true, LanguageResolution.isRightToLeft(language))
        }
        for (language in listOf("en", "ro", "zh-cn", "ja", "tr")) {
            assertEquals(language, false, LanguageResolution.isRightToLeft(language))
        }
    }
}
