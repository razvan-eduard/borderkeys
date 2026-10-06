// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.ime.AutoCorrection
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Every case in `pipeline_cases.tsv` and `pipeline_cases_ro.tsv` through [Pipeline], each payload
 * in one test that reports all of its failures together.
 */
class PipelineTest {

    private data class Case(val typed: String, val committed: String?, val situation: String)

    @Test
    fun `every case commits what it should, for the reason it should`() {
        Pipeline.require()
        val cases = readCases()
        assertTrue("no cases were read -- the payload is missing", cases.isNotEmpty())

        val readings = Pipeline.readings("pipeline_cases.tsv")
        val failures = cases.mapNotNull { case ->
            val outcome = pipeline.commit(case.typed)
            readings?.println(pipeline.readingLine(outcome))
            val committed = outcome.committed
            when {
                committed != case.committed ->
                    "  ${case.typed}: expected ${describe(case.committed)}, " +
                        "committed ${describe(committed)}  [${outcome.reason}]"
                outcome.reason != case.situation ->
                    "  ${case.typed}: right answer for the wrong reason -- expected " +
                        "${case.situation}, was ${outcome.reason}"
                else -> null
            }
        }
        readings?.close()
        assertTrue(
            "${failures.size} of ${cases.size} cases failed:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `a phrase leaves its correctly spelled words alone`() {
        Pipeline.require()
        val outcomes = pipeline.commitPhrase("can we put the form in their folder")
        val changed = outcomes.filter { it.committed != null }
        assertTrue(
            "nothing in this phrase needs correcting, but " +
                changed.joinToString { "${it.typed} -> ${it.committed} [${it.reason}]" },
            changed.isEmpty(),
        )
    }

    @Test
    fun `every Romanian case commits what it should, for the reason it should`() {
        Pipeline.require()
        val romanian = Pipeline.open("ro-RO")
        val readings = Pipeline.readings("pipeline_cases_ro.tsv")
        try {
            val failures = readCases("pipeline_cases_ro.tsv").mapNotNull { case ->
                val outcome = romanian.commit(case.typed)
                readings?.println(romanian.readingLine(outcome))
                when {
                    outcome.committed != case.committed ->
                        "  ${case.typed}: expected ${describe(case.committed)}, " +
                            "committed ${describe(outcome.committed)}  [${outcome.reason}]"
                    outcome.reason != case.situation ->
                        "  ${case.typed}: right answer for the wrong reason -- expected " +
                            "${case.situation}, was ${outcome.reason}"
                    else -> null
                }
            }
            assertTrue("${failures.size} failed:\n" + failures.joinToString("\n"),
                       failures.isEmpty())
        } finally {
            readings?.close()
            romanian.close()
        }
    }

    /**
     * A word the personal dictionary holds counts as a known word only once established:
     * chosen on purpose, or written often enough.
     */
    @Test
    fun `a typo typed past twice is still corrected, a word chosen once is not`() {
        Pipeline.require()
        val own = Pipeline.open("en-US")
        // The personal dictionary is consulted only with Learning on.
        own.learning = true
        try {
            own.learn("teh", times = 2)
            val twice = own.commit("teh")
            assertEquals("the", twice.committed)
            assertEquals(AutoCorrection.Situation.Correctable.name, twice.reason)

            own.learn("teh", asserted = true)
            val chosen = own.commit("teh")
            assertNull(chosen.committed)
            assertEquals(AutoCorrection.Situation.KnownWord.name, chosen.reason)
        } finally {
            own.close()
        }
    }

    /**
     * With nothing typed, the pack's successor index is walked before the frequent shortlist,
     * so a strong successor that is itself a rare word is offered.
     */
    @Test
    fun `a successor outside the frequent shortlist is predicted after its context`() {
        Pipeline.require()
        val pairs = listOf(
            "ice" to "cream", "peanut" to "butter", "human" to "rights", "united" to "kingdom",
        )
        val failures = pairs.mapNotNull { (previous, expected) ->
            val strip = pipeline.strip("", previous).take(5)
            if (expected in strip) null else "  after \"$previous\": expected \"$expected\" in $strip"
        }
        assertTrue(
            "${failures.size} of ${pairs.size} successors were not predicted:\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /** A word the taps fit no better than a rival is left on the strip, one tap away. */
    @Test
    fun `a close call is offered on the strip rather than applied`() {
        Pipeline.require()
        val outcome = pipeline.commit("beleiv")
        assertNull("beleiv was applied as ${outcome.committed}", outcome.committed)
        val strip = pipeline.strip("beleiv").take(STRIP_SLOTS)
        assertTrue("believe is not among $strip", "believe" in strip)
    }

    private fun describe(text: String?) = text ?: "nothing"

    /** At a minimum length of five, words the default corrects are refused as TooShort. */
    @Test
    fun `a word below the minimum length is refused on length, not corrected`() {
        Pipeline.require()
        val failures = listOf("tge", "hte", "teh", "adn").mapNotNull { typed ->
            val outcome = pipeline.commit(typed, minimumLength = 5)
            when {
                outcome.committed != null ->
                    "  $typed: committed ${outcome.committed}  [${outcome.reason}]"
                outcome.reason != AutoCorrection.Situation.TooShort.name ->
                    "  $typed: expected TooShort, was ${outcome.reason}"
                else -> null
            }
        }
        assertTrue(
            "${failures.size} of 4 failed:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /** Every case again with its first letter capitalised commits the same word, capitalised. */
    @Test
    fun `every case behaves the same when the first letter is capitalised`() {
        Pipeline.require()
        val romanian = Pipeline.open("ro-RO")
        val readings = Pipeline.readings("pipeline_cases_capitalised.tsv")
        val failures = try {
            // Each payload against its own language's pipeline.
            val runs = readCases().map { it to pipeline } +
                readCases("pipeline_cases_ro.tsv").map { it to romanian }
            runs.mapNotNull { (case, engine) ->
                if (case.typed.isEmpty() || !case.typed[0].isLowerCase()) {
                    return@mapNotNull null
                }
                val capitalised = case.typed.replaceFirstChar { it.uppercaseChar() }
                val expected = case.committed?.replaceFirstChar { it.uppercaseChar() }
                // Skipped when the correction was only the capital.
                if (expected == capitalised) {
                    return@mapNotNull null
                }
                val outcome = engine.commit(capitalised)
                readings?.println(engine.readingLine(outcome))
                if (outcome.committed != expected) {
                    "  $capitalised: expected ${describe(expected)}, " +
                        "committed ${describe(outcome.committed)}  [${outcome.reason}]"
                } else {
                    null
                }
            }
        } finally {
            readings?.close()
            romanian.close()
        }
        assertTrue(
            "${failures.size} capitalised cases behave differently:\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    private fun readCases(name: String = "pipeline_cases.tsv"): List<Case> =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "$name is not on the test classpath"
        }.bufferedReader().readLines().mapNotNull { line ->
            if (line.isBlank() || line.startsWith("#")) {
                return@mapNotNull null
            }
            val parts = line.split('\t').filter { it.isNotEmpty() }
            if (parts.size < 3) null
            else Case(parts[0], parts[1].takeIf { it != "-" }, parts[2])
        }

    companion object {
        /** The words the strip shows beside the typed one. */
        private const val STRIP_SLOTS = 3

        private lateinit var pipeline: Pipeline

        @BeforeClass
        @JvmStatic
        fun open() {
            if (Pipeline.available()) {
                pipeline = Pipeline.open("en-US")
            }
        }

        @AfterClass
        @JvmStatic
        fun close() {
            if (Companion::pipeline.isInitialized) {
                pipeline.close()
            }
        }
    }
}
