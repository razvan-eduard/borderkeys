// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.NativeSource
import com.borderkeys.data.personal.SliceRows
import com.borderkeys.data.personal.WordRows
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
        assertEquals(slot("kTextAnswerPack"), NativePredictor.TEXT_ANSWER_PACK)
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
    fun `the word slots are the bridge's`() {
        assertEquals(slot("kWordCount"), WordRows.COUNT)
        assertEquals(slot("kWordDeliberateCapitals"), WordRows.DELIBERATE_CAPITALS)
        assertEquals(slot("kWordAsserted"), WordRows.ASSERTED)
        assertEquals(slot("kWordAdded"), WordRows.ADDED)
        assertEquals(slot("kWordFields"), WordRows.FIELDS)
        assertEquals(slot("kNewWordId"), WordRows.NEW_WORD)
    }

    @Test
    fun `the slice slots are the bridge's`() {
        assertEquals(slot("kSliceHeaderFields"), SliceRows.HEADER_FIELDS)
        assertEquals(slot("kSliceLinkFields"), SliceRows.LINK_FIELDS)
        assertEquals(slot("kSliceHopFields"), SliceRows.HOP_FIELDS)
        assertEquals(slot("kSliceContextSeenFields"), SliceRows.CONTEXT_SEEN_FIELDS)
        assertEquals(slot("kSliceMaxHops"), SliceRows.MAX_HOPS)
        assertEquals(slot("kSliceNone"), SliceRows.NONE)
        assertEquals(slot("kPersonalContextSlots"), SliceRows.KEY_SLOTS)
    }

    @Test
    fun `the slice's fields are in the engine's order`() {
        val header = listOf(
            "kLanguageField" to SliceRows.LANGUAGE, "kContext1Field" to SliceRows.CONTEXT1,
            "kContext2Field" to SliceRows.CONTEXT2, "kPairTotalField" to SliceRows.PAIR_TOTAL,
            "kTripleTotalField" to SliceRows.TRIPLE_TOTAL, "kPairsField" to SliceRows.PAIRS,
            "kTriplesField" to SliceRows.TRIPLES, "kHopsField" to SliceRows.HOPS,
        )
        val link = listOf(
            "kLinkWordField" to SliceRows.LINK_WORD, "kLinkCountField" to SliceRows.LINK_COUNT,
            "kLinkAssertedField" to SliceRows.LINK_ASSERTED,
        )
        val hop = listOf(
            "kHopWordField" to SliceRows.HOP_WORD, "kHopCountField" to SliceRows.HOP_COUNT,
            "kHopAssertedField" to SliceRows.HOP_ASSERTED, "kHopMiddleTotalField" to SliceRows.HOP_MIDDLE_TOTAL,
        )
        val seen = listOf("kPairSeenField" to SliceRows.PAIR_SEEN, "kTripleSeenField" to SliceRows.TRIPLE_SEEN)
        for (fields in listOf(header, link, hop, seen)) {
            assertEquals(fields.map { it.second }, fields.map { NativeSource.number(NativeSource.CONTEXT_SLICE_HEADER, it.first) })
        }
    }

    @Test
    fun `the bridge takes as many gesture points as a capture holds`() {
        assertEquals(slot("kMaxGesturePoints"), GestureCapture.DEFAULT_CAPACITY)
    }

    @Test
    fun `the bridge takes as many blocked words as the setting keeps learned words`() {
        assertEquals(slot("kMaxUserWordsPerCall"), KeyboardPreferences.MAX_LEARNED_WORD_LIMIT)
    }

    @Test
    fun `the bridge takes as many word rows as one int array holds the fields of`() {
        assertEquals(slot("kMaxWordRowsPerCall"), Int.MAX_VALUE / WordRows.FIELDS)
    }

    @Test
    fun `no pack is the engine's`() {
        assertEquals(NativeSource.number(NativeSource.ENGINE_HEADER, "kNoPack"), NativePredictor.NO_PACK)
    }
}
