// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which languages lend accents to a layout's letters, and what their accents add. */
class AccentOverlaysTest {

    private val overlays = linkedMapOf(
        "fr-FR" to mapOf('e' to "éèêë", 'c' to "ç"),
        "he-IL" to emptyMap(),
        "ro-RO" to mapOf('a' to "ăâ", 's' to "ș"),
        "ru-RU" to mapOf('е' to "ё", 'ь' to "ъ"),
    )

    @Test
    fun `a language is listed only when it has an accent for a letter of the layout`() {
        assertEquals(listOf("fr-FR", "ro-RO"), AccentOverlays.lendingTo("qwertyuiopasdfghjklzxcvbnm".toSet(), overlays))
        assertEquals(listOf("ru-RU"), AccentOverlays.lendingTo("йцукенгшщзхфывапролджэячсмитьбю".toSet(), overlays))
        assertEquals(listOf("fr-FR"), AccentOverlays.lendingTo(setOf('E', 'x'), overlays))
    }

    @Test
    fun `the languages added come after the enabled ones, once each, and an enabled one is not added twice`() {
        val enabled = mapOf('a' to "ăâ")
        val merged = AccentOverlays.withExtra(enabled, listOf("ro-RO"), listOf("fr-FR", "ro-RO"), overlays)
        assertEquals("ăâ", merged['a'])
        assertEquals("éèêë", merged['e'])
        assertEquals("ç", merged['c'])
        assertEquals(enabled, AccentOverlays.withExtra(enabled, listOf("ro-RO"), emptyList(), overlays))
        assertEquals(enabled, AccentOverlays.withExtra(enabled, listOf("ro-RO"), listOf("xx-XX"), overlays))
    }

}
