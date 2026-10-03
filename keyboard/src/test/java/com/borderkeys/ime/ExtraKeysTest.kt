// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What a language's extra keys add to a layout, and where. */
class ExtraKeysTest {

    private val qwerty = KeyboardLayout.fallbackQwerty()
    private val none: (Int, Int) -> Boolean = { _, _ -> false }

    private fun entries(vararg texts: String) = texts.map { ExtraKeys.parse(it)!! }

    private fun placed(placements: List<ExtraKeys.Placement>) =
        placements.map { "${it.keyCode.toChar()}${it.direction}:${it.keyName ?: it.text}" }

    @Test
    fun `an entry is a character or an accent, with alternatives and a key to sit beside`() {
        val trema = ExtraKeys.parse("accent_trema:ä:ö:ü@u")!!
        assertEquals("dead_diaeresis", trema.key)
        assertTrue(trema.isAccent)
        assertEquals(listOf("ä", "ö", "ü"), trema.alternatives)
        assertEquals("u", trema.nextTo)
        val pound = ExtraKeys.parse("£@l")!!
        assertEquals("£", pound.key)
        assertEquals("l", pound.nextTo)
        assertNull(ExtraKeys.parse("accent_double_aigu:ő:ű@k"))
        assertEquals("ñ", ExtraKeys.idOf(ExtraKeys.parse("accent_tilde:ñ@n")!!))
    }

    @Test
    fun `German on QWERTY gets the diaeresis beside u and ß and € on the default corners`() {
        val placements = ExtraKeys.place(entries("accent_trema:ä:ö:ü@u", "ß", "€"), qwerty, emptySet(), none)
        assertEquals(listOf("u3:dead_diaeresis", "a3:ß", "a5:€"), placed(placements))
        assertEquals(DeadKeys.cap(KeyCodes.DEAD_DIAERESIS), placements[0].text)
    }

    @Test
    fun `an accent whose letters the long press already holds is not added, and one alternative adds that letter`() {
        val accented = qwerty.withAccents(mapOf('a' to "ä", 'o' to "ö", 'u' to "ü"), "de-DE")
        assertEquals(emptyList<String>(), placed(ExtraKeys.place(entries("accent_trema:ä:ö:ü@u"), accented, emptySet(), none)))
        assertEquals(listOf("n3:ñ"), placed(ExtraKeys.place(entries("accent_tilde:ñ@n"), qwerty, emptySet(), none)))
    }

    @Test
    fun `a switched-off key, a taken corner and a script the layout does not use are respected`() {
        assertEquals(
            listOf("a3:€"),
            placed(ExtraKeys.place(entries("ß", "€"), qwerty, setOf("ß"), none)),
        )
        val taken: (Int, Int) -> Boolean = { code, direction -> code == 'u'.code && direction == 3 }
        assertEquals(listOf("u5:dead_diaeresis"), placed(ExtraKeys.place(entries("accent_trema:ä:ö:ü@u"), qwerty, emptySet(), taken)))
        assertEquals(emptyList<String>(), placed(ExtraKeys.place(entries("accent_aigu:á:é@d"), russian(), emptySet(), none)))
        assertEquals(emptyList<String>(), placed(ExtraKeys.place(entries("ґ"), qwerty, emptySet(), none)))
        assertEquals(1, ExtraKeys.place(entries("ґ"), russian(), emptySet(), none).size)
    }

    @Test
    fun `a character the layout has is not added twice`() {
        assertEquals(emptyList<String>(), placed(ExtraKeys.place(entries("q", "Q"), qwerty, emptySet(), none)))
    }

    private fun russian(): KeyboardLayout = LayoutLoader.parse(File("src/main/assets/layouts/russian.json").readText())
}
