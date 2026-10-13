// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.NativeSource
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.ime.GestureCapture
import org.junit.Assert.assertEquals
import org.junit.Test

/** [NativePredictor]'s slots and sentinels against the bridge's and the engine's, read from their headers. */
class NativeSlotsTest {

    private fun slot(name: String) = NativeSource.number(NativeSource.BRIDGE_SLOTS_HEADER, name)

    @Test
    fun `the text slots are the bridge's`() {
        assertEquals(slot("kTextKnownSpelling"), NativePredictor.TEXT_KNOWN_SPELLING)
        assertEquals(slot("kTextPossessive"), NativePredictor.TEXT_POSSESSIVE)
        assertEquals(slot("kTextDecoded"), NativePredictor.TEXT_DECODED)
        assertEquals(slot("kTextLanguage"), NativePredictor.TEXT_LANGUAGE)
        assertEquals(slot("kTextSlots"), NativePredictor.TEXT_SLOTS)
    }

    @Test
    fun `the correction and spelling slots are the bridge's`() {
        assertEquals(slot("kCorrectionSlots"), NativePredictor.CORRECTION_SLOTS)
        assertEquals(slot("kSpellingExact"), NativePredictor.SPELLING_EXACT)
        assertEquals(slot("kSpellingName"), NativePredictor.SPELLING_NAME)
        assertEquals(slot("kSpellingFlagSlots"), NativePredictor.SPELLING_FLAGS)
    }

    @Test
    fun `the learned bits are the bridge's`() {
        assertEquals(slot("kLearnedPair"), NativePredictor.LEARNED_PAIR)
        assertEquals(slot("kLearnedTriple"), NativePredictor.LEARNED_TRIPLE)
    }

    @Test
    fun `the context slots are the bridge's`() {
        assertEquals(slot("kContextTotal"), NativePredictor.CONTEXT_TOTAL)
        assertEquals(slot("kContextSeen"), NativePredictor.CONTEXT_SEEN)
        assertEquals(slot("kContextSlots"), NativePredictor.CONTEXT_SLOTS)
    }

    @Test
    fun `the bridge takes as many gesture points as a capture holds`() {
        assertEquals(slot("kMaxGesturePoints"), GestureCapture.DEFAULT_CAPACITY)
    }

    @Test
    fun `the bridge takes as many learned words as the setting keeps`() {
        assertEquals(slot("kMaxUserWordsPerCall"), KeyboardPreferences.MAX_LEARNED_WORD_LIMIT)
    }

    @Test
    fun `no pack is the engine's`() {
        assertEquals(NativeSource.number(NativeSource.ENGINE_HEADER, "kNoPack"), NativePredictor.NO_PACK)
    }
}
