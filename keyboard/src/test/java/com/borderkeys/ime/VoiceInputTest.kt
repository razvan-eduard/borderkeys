// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputTest {

    private val google = "com.google/.Voice"
    private val other = "com.other/.Dictate"

    @Test
    fun `no voice keyboard means none`() {
        assertTrue(VoiceInput.decide(emptyList(), "", "", hold = false) is VoiceInput.Step.None)
        assertTrue(VoiceInput.decide(emptyList(), google, "", hold = true) is VoiceInput.Step.None)
    }

    @Test
    fun `the only voice keyboard is taken without asking`() {
        val step = VoiceInput.decide(listOf(google), "", "", hold = false)
        assertEquals(google, (step as VoiceInput.Step.Switch).id)
    }

    @Test
    fun `the remembered one is taken while the set it was chosen from is unchanged`() {
        val set = VoiceInput.signature(listOf(other, google))
        val step = VoiceInput.decide(listOf(google, other), other, set, hold = false)
        assertEquals(other, (step as VoiceInput.Step.Switch).id)
    }

    @Test
    fun `a changed set, or none remembered, asks`() {
        val oldSet = VoiceInput.signature(listOf(google))
        assertTrue(VoiceInput.decide(listOf(google, other), google, oldSet, hold = false) is VoiceInput.Step.Picker)
        assertTrue(VoiceInput.decide(listOf(google, other), "", "", hold = false) is VoiceInput.Step.Picker)
        assertTrue(VoiceInput.decide(listOf(google, other), "com.gone/.Voice", VoiceInput.signature(listOf(google, other)), hold = false) is VoiceInput.Step.Picker)
    }

    @Test
    fun `a hold always asks when there is anything to choose from`() {
        val set = VoiceInput.signature(listOf(google))
        assertTrue(VoiceInput.decide(listOf(google), google, set, hold = true) is VoiceInput.Step.Picker)
    }

    @Test
    fun `the signature ignores order`() {
        assertEquals(VoiceInput.signature(listOf(other, google)), VoiceInput.signature(listOf(google, other)))
    }
}
