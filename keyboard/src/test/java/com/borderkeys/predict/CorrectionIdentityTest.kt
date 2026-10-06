// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list `nativeAnswer` hands over is autocorrect's own: in order, one spelling once, whole,
 * with at most the tap decoder's word after it.
 */
class CorrectionIdentityTest {

    @Test
    fun `the bridge's list is the engine's, deduplicated, and the word committed is on it`() {
        Pipeline.require()
        val pipeline = Pipeline.open("ro-RO")
        try {
            var listed = 0
            var committedFromList = 0
            for (typed in WORDS) {
                val offers = pipeline.corrections(typed)
                val listedOffers = offers.filterIsInstance<ListedCorrection>()
                assertTrue("$typed: more than five entries", listedOffers.size <= 5)
                assertTrue("$typed: more than one decoded word", offers.count { it is DecodedCorrection } <= 1)
                assertEquals(
                    "$typed: a spelling listed twice: ${listedOffers.map { it.text }}",
                    listedOffers.map { it.text.lowercase() }.distinct().size, listedOffers.size,
                )
                assertTrue("$typed: an empty entry", offers.none { it.text.isEmpty() })
                if (offers.isNotEmpty()) {
                    listed++
                }
                val committed = pipeline.commit(typed).committed ?: continue
                assertTrue(
                    "$typed: committed $committed, not on the list ${offers.map { it.text }}",
                    offers.any { it.text.equals(committed, ignoreCase = true) },
                )
                committedFromList++
            }
            assertTrue("no word had a list at all -- the slots are not being filled", listed > 0)
            assertTrue("no word was corrected from its list", committedFromList > 0)
            println("listed $listed, committed from the list $committedFromList")
        } finally {
            pipeline.close()
        }
    }

    private companion object {
        val WORDS = listOf(
            "daca", "sapte", "inainte", "stiu", "tara", "putem", "copii", "masina",
            "scoala", "gradina", "invatat", "romaneste", "cand", "pana", "asa",
        )
    }
}
