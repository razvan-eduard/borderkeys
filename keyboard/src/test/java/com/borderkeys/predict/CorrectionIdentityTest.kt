// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The correction `nativeAnswer` marks by index is the correction it names. */
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
                    // Not among the ranked words.
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
