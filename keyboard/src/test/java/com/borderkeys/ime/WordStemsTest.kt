// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordStemsTest {

    private val english = listOf("en-US")
    private val romanian = listOf("ro-RO")

    @Test
    fun `an ending comes off in every spelling it attaches to`() {
        assertTrue("dice" in WordStems.candidates("dices", english))
        assertTrue("tree" in WordStems.candidates("treeing", english))
        assertTrue("spat" in WordStems.candidates("spatting", english))
        assertTrue("hike" in WordStems.candidates("hiking", english))
        assertTrue("city" in WordStems.candidates("cities", english))
        assertTrue("basic" in WordStems.candidates("basically", english))
    }

    @Test
    fun `a final e is restored only before an ending that begins with a vowel`() {
        assertTrue("hike" in WordStems.candidates("hiking", english))
        assertFalse("thee" in WordStems.candidates("thes", english))
    }

    @Test
    fun `a stem shorter than four letters is not one`() {
        assertFalse("shy" in WordStems.candidates("shyer", english))
        assertTrue(WordStems.candidates("thes", english).isEmpty())
        assertTrue(WordStems.candidates("ands", english).isEmpty())
    }

    @Test
    fun `a doubled final letter is not an ending`() {
        assertFalse("this" in WordStems.candidates("thiss", english))
        assertFalse("they" in WordStems.candidates("theyy", english))
    }

    @Test
    fun `case and accents are folded first`() {
        assertTrue("smooth" in WordStems.candidates("Smooths", english))
        assertTrue("copil" in WordStems.candidates("copilul", romanian))
        assertTrue("fetit" in WordStems.candidates("fetiță", romanian))
    }

    @Test
    fun `a word carrying a mark or a digit has no stem`() {
        assertTrue(WordStems.candidates("maria's", english).isEmpty())
        assertTrue(WordStems.candidates("sha256s", english).isEmpty())
    }

    @Test
    fun `a language without a table yields nothing`() {
        assertTrue(WordStems.candidates("smooths", listOf("de-DE")).isEmpty())
    }

    @Test
    fun `the answer shields the word unless it shares the stem or carries the word on`() {
        assertTrue(WordStems.shields("smooths", "smooth", setOf("smooth"), english))
        assertTrue(WordStems.shields("treeing", "freezing", setOf("tree"), english))
        assertFalse(WordStems.shields("stoped", "stopped", setOf("stop"), english))
        assertFalse(WordStems.shields("truely", "truly", setOf("true"), english))
        assertFalse(WordStems.shields("differen", "different", setOf("differ"), english))
        assertFalse(WordStems.shields("anything", "anything", emptySet(), english))
    }
}
