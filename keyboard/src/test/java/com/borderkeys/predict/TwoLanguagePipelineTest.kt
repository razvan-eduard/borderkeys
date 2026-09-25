// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Two languages at once, which is how this keyboard is actually used and what nothing covered.
 *
 * Every other pipeline fixture opens a single pack, so a rule that consults "the dictionaries"
 * could never tell one from many. That is where a real report came from: writing Romanian, with
 * English also enabled, "daca" would not become "dacă" -- because "daca" is in the English pack
 * as the lowercased acronym DACA, and a word the dictionaries hold is not corrected.
 *
 * The word is not the bug and the English pack is right to have it. What was wrong is that the
 * rule asked every open language instead of the one being written.
 */
class TwoLanguagePipelineTest {

    @Test
    fun `an English word does not block a Romanian correction`() {
        Pipeline.require()
        // Every word here is Romanian and *not* English, which is what moves the weights apart:
        // a word both packs know pushes both toward one and separates nothing, and a word
        // neither knows changes nothing at all. Seven of them put English about a third behind.
        pipeline.commitPhrase("vad prea medicamentul doare stomacul pastile trebuia")
        val outcome = pipeline.commit("daca", previous = "doare")
        assertEquals(
            "daca committed ${outcome.committed} [${outcome.reason}]",
            "dacă", outcome.committed,
        )
    }

    @Test
    fun `a word both languages know is still left alone`() {
        Pipeline.require()
        pipeline.commitPhrase("vad prea medicamentul doare stomacul pastile trebuia")
        // "ca" is a Romanian word in its own right, whatever English thinks of it.
        val outcome = pipeline.commit("ca", previous = "doare")
        assertTrue(
            "ca must not be rewritten: committed ${outcome.committed} [${outcome.reason}]",
            outcome.committed == null || outcome.committed == "ca",
        )
    }

    @Test
    fun `with nothing written yet neither language speaks for the other`() {
        Pipeline.require()
        // A fresh conversation has no evidence, so no language is preferred and both answer --
        // which is the right answer for a field nobody has typed in.
        assertEquals(-1, fresh.dominantPack())
    }

    companion object {
        private lateinit var pipeline: Pipeline
        private lateinit var fresh: Pipeline

        @JvmStatic
        @BeforeClass
        fun open() {
            if (!Pipeline.available()) return
            pipeline = Pipeline.open("ro-RO", "en-US")
            fresh = Pipeline.open("ro-RO", "en-US")
        }

        @JvmStatic
        @AfterClass
        fun close() {
            if (!Pipeline.available()) return
            pipeline.close()
            fresh.close()
        }
    }
}
