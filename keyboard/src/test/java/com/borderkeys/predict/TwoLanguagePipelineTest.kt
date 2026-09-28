// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Romanian and English open at once: the known-word rule asks the language being written. */
class TwoLanguagePipelineTest {

    @Test
    fun `an English word does not block a Romanian correction`() {
        Pipeline.require()
        // Words only the Romanian pack holds.
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
        val outcome = pipeline.commit("ca", previous = "doare")
        assertTrue(
            "ca must not be rewritten: committed ${outcome.committed} [${outcome.reason}]",
            outcome.committed == null || outcome.committed == "ca",
        )
    }

    @Test
    fun `with nothing written yet neither language speaks for the other`() {
        Pipeline.require()
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
