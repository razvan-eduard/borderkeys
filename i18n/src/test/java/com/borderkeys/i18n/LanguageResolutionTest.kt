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

    /** A phone set to Catalan then Spanish should get Spanish, not English. */
    @Test
    fun `the second preference is used when the first is missing`() {
        assertEquals("ro", LanguageResolution.resolve(listOf("ca-ES", "ro-RO"), shipped))
    }

    /** A Brazilian reading European Portuguese is better served than one reading English. */
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

    /** A stored language that is no longer shipped must not strand the user in its absence. */
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
}
