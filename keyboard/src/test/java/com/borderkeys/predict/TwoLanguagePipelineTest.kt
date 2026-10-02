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

    @Test
    fun `a word only the other language holds is offered once one language is decided`() {
        Pipeline.require()
        decided(ENGLISH) { assertEquals("mintea", it.strip("mintea").firstOrNull()) }
        decided(ROMANIAN) { assertEquals("weather", it.strip("weather").firstOrNull()) }
    }

    @Test
    fun `a slip on the other language's word is corrected into that language`() {
        Pipeline.require()
        decided(ENGLISH) { assertEquals("mintea", it.commit("mibtea").committed) }
        decided(ROMANIAN) { assertEquals("weather", it.commit("weathr").committed) }
    }

    @Test
    fun `the other language's word typed without its accents gets them back`() {
        Pipeline.require()
        decided(ENGLISH) { assertEquals("când", it.commit("cand").committed) }
    }

    @Test
    fun `the decided language keeps the words it spells itself`() {
        Pipeline.require()
        decided(ENGLISH) {
            val strip = it.strip("car")
            assertEquals("car", strip.firstOrNull())
            assertTrue("Romanian crowds in: $strip", strip.none { word -> word in setOf("ar", "cu") })
        }
        decided(ROMANIAN) {
            val strip = it.strip("cu")
            assertEquals("cu", strip.firstOrNull())
            assertTrue(
                "English crowds in: $strip",
                strip.none { word -> word in setOf("cup", "cut", "cue", "cube") },
            )
        }
    }

    @Test
    fun `a preferred language answers first without hiding a closer match`() {
        Pipeline.require()
        val pipeline = Pipeline.open("ro-RO", "en-US")
        try {
            pipeline.languageLock(BALANCED_EVIDENCE)
            pipeline.preferredLanguage("en-US")
            assertEquals("mintea", pipeline.strip("mintea").firstOrNull())
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `strict keeps to the decided language`() {
        Pipeline.require()
        decided(ENGLISH, strict = true) {
            val strip = it.strip("mintea")
            assertTrue("strict offered Romanian: $strip", "mintea" !in strip)
        }
    }

    @Test
    fun `evidence carried into a restarted keyboard decides the language the writing did`() {
        Pipeline.require()
        val written = Pipeline.open("ro-RO", "en-US")
        val evidence = try {
            written.languageLock(BALANCED_EVIDENCE)
            written.commitPhrase(ENGLISH)
            written.languageEvidence()
        } finally {
            written.close()
        }
        val restarted = Pipeline.open("ro-RO", "en-US")
        try {
            restarted.languageLock(0f)
            restarted.restoreLanguageEvidence(evidence)
            assertEquals(-1, restarted.dominantPack())
            restarted.languageLock(BALANCED_EVIDENCE)
            assertEquals(ENGLISH_PACK, restarted.dominantPack())
            val strip = restarted.strip("car")
            assertEquals("car", strip.firstOrNull())
            assertTrue("Romanian crowds in: $strip", strip.none { word -> word in setOf("ar", "cu") })
        } finally {
            restarted.close()
        }
    }

    /** A pipeline of its own, [lead] written until its language is decided, handed to [check]. */
    private fun decided(lead: String, strict: Boolean = false, check: (Pipeline) -> Unit) {
        val pipeline = Pipeline.open("ro-RO", "en-US")
        try {
            pipeline.languageLock(if (strict) STRICT_EVIDENCE else BALANCED_EVIDENCE, strict)
            pipeline.commitPhrase(lead)
            val expected = if (lead == ENGLISH) ENGLISH_PACK else ROMANIAN_PACK
            assertEquals("the lead did not decide its language", expected, pipeline.dominantPack())
            check(pipeline)
        } finally {
            pipeline.close()
        }
    }

    companion object {
        private lateinit var pipeline: Pipeline
        private lateinit var fresh: Pipeline

        private const val ROMANIAN_PACK = 0
        private const val ENGLISH_PACK = 1

        /** The Languages screen's Balanced and Strict thresholds. */
        private const val BALANCED_EVIDENCE = 1.8f
        private const val STRICT_EVIDENCE = 0.9f

        private const val ENGLISH = "what is the best way to learn this song before the weekend"
        private const val ROMANIAN = "mi-a trecut prin cap că ar fi bine să vorbim despre asta mâine"

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
