// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeModelsTest {

    private val latin = "qwertyuiopasdfghjklzxcvbnm".map { it.toString() } + listOf("⇧", "⌫", "?123", ",")
    private val cyrillic = "йцукенгшщзхфывапролджэячсмитьбю".map { it.toString() } + listOf("⇧", "⌫")
    private val greek = "ςερτυθιοπασδφγηξκλζχψωβνμ".map { it.toString() }

    @Test
    fun `a Latin layout, a Latin custom one and a Latin variant decode with the Latin model`() {
        val shipped = setOf("russian.bkw")
        assertEquals(SwipeModels.LATIN, SwipeModels.modelFor("qwerty", latin, shipped, emptySet()))
        assertEquals(SwipeModels.LATIN, SwipeModels.modelFor("custom-3", latin, shipped, emptySet()))
        assertEquals(SwipeModels.LATIN, SwipeModels.modelFor("azerty+num+acc", latin, shipped, emptySet()))
    }

    @Test
    fun `an own-script layout whose model ships decodes with it, its variants too, until it is written off`() {
        val shipped = setOf("russian.bkw", "greek.bkw")
        val russian = SwipeModels.modelFor("russian", cyrillic, shipped, emptySet())!!
        assertEquals("swipe/russian.bkw", russian.asset)
        assertNotEquals(SwipeModels.LATIN.script, russian.script)
        assertEquals(russian, SwipeModels.modelFor("russian+num-noemoji", cyrillic, shipped, emptySet()))
        assertNotEquals(russian.script, SwipeModels.modelFor("greek", greek, shipped, emptySet())!!.script)
        assertNull(SwipeModels.modelFor("russian", cyrillic, shipped, setOf("swipe/russian.bkw")))
    }

    @Test
    fun `a layout in another script with no model of its own decodes geometrically`() {
        assertNull(SwipeModels.modelFor("greek", greek, setOf("russian.bkw"), emptySet()))
        assertNull(SwipeModels.modelFor("custom-4", cyrillic, setOf("russian.bkw"), emptySet()))
        assertNull(SwipeModels.modelFor("custom-5", listOf("⇧", "⌫", "1"), emptySet(), emptySet()))
    }

    @Test
    fun `the base id drops every variant suffix`() {
        assertEquals("russian", SwipeModels.baseId("russian+num+acc1a2b-noglobe"))
        assertEquals("custom-3", SwipeModels.baseId("custom-3-noemoji"))
        assertTrue(SwipeModels.isLatin(listOf("é", "ß", "q")))
        assertFalse(SwipeModels.isLatin(listOf("q", "й")))
    }
}
