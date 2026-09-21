// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The correction the engine reports by index is the same word it reports by name.
 *
 * `nativeSuggest` marks which ranked candidate the corrections heap settled on, by pack and word
 * index; `nativeBestCorrection` returns that word's text. The two are separate paths out of one
 * search, and the point of the index is that the caller never has to match the text again -- an
 * identity the two rankings do not share, since the same word can reach them cased differently.
 * This is what says the index means what it claims.
 */
class CorrectionIdentityTest {

    @Test
    fun `the marked candidate is the corrections heap's own answer`() {
        Pipeline.require()
        val pipeline = Pipeline.open("ro-RO")
        try {
            var marked = 0
            var absent = 0
            for (typed in WORDS) {
                val view = pipeline.stripWithCorrection(typed)
                val ranked = view.ranked
                val at = view.correctionAt
                val correction = view.correction
                if (at < 0) {
                    absent++
                    // Not among the ranked words: nothing to compare, and the ordinary case.
                    continue
                }
                assertTrue("$typed: index $at is outside ${ranked.size} words", at < ranked.size)
                assertEquals(
                    "$typed: the marked entry is not the corrections heap's own word",
                    correction, ranked[at],
                )
                marked++
            }
            assertTrue("no word marked a correction at all -- the index is not being set", marked > 0)
            println("marked $marked, not in the ranking $absent")
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
