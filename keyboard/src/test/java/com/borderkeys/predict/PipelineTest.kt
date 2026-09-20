// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.ime.AutoCorrection
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Every case in `pipeline_cases.tsv`, through the real path, asserted.
 *
 * The suites either side of this each test half of a decision. `borderkeys_tests` stops at the
 * engine; `AutoCorrectionTest` and `SituationTest` start after it, on values handed to them.
 * Between the two sat the join, and every bug reported from a device this session lived exactly
 * there -- a word the engine offered and the guards were supposed to refuse, or the reverse.
 *
 * One test method rather than one per case, deliberately: a payload file is meant to be appended
 * to when something is reported, and that should cost a line rather than a method. Every failure
 * is collected and reported together, so a change that breaks nine cases says so once instead of
 * stopping at the first.
 *
 * Skipped, not failed, where the host bridge or the packs are missing: neither is produced by an
 * ordinary `./gradlew test`, and a suite that fails on a machine that simply has not run
 * `cmake --build native-tests/build --target borderkeys` teaches people to ignore it.
 */
class PipelineTest {

    private data class Case(val typed: String, val committed: String?, val situation: String)

    @Test
    fun `every case commits what it should, for the reason it should`() {
        Pipeline.require()
        val cases = readCases()
        assertTrue("no cases were read -- the payload is missing", cases.isNotEmpty())

        val failures = cases.mapNotNull { case ->
            val outcome = pipeline.commit(case.typed)
            val committed = outcome.committed
            when {
                committed != case.committed ->
                    "  ${case.typed}: expected ${describe(case.committed)}, " +
                        "committed ${describe(committed)}  [${outcome.situation}]"
                outcome.situation.name != case.situation ->
                    "  ${case.typed}: right answer for the wrong reason -- expected " +
                        "${case.situation}, was ${outcome.situation}"
                else -> null
            }
        }
        assertTrue(
            "${failures.size} of ${cases.size} cases failed:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /** A phrase carries context word to word, which a single-word corpus cannot exercise: the
     *  n-grams are most of why a candidate wins, and "put" was committed as "out" mid-sentence. */
    @Test
    fun `a phrase leaves its correctly spelled words alone`() {
        Pipeline.require()
        val outcomes = pipeline.commitPhrase("can we put the form in their folder")
        val changed = outcomes.filter { it.committed != null }
        assertTrue(
            "nothing in this phrase needs correcting, but " +
                changed.joinToString { "${it.typed} -> ${it.committed} [${it.situation}]" },
            changed.isEmpty(),
        )
    }

    /** The same contract against the Romanian pack, which is where most of the reports came
     *  from and which reaches a rule English never does: a missing accent may be restored below
     *  the length at which guessing otherwise stops. */
    @Test
    fun `every Romanian case commits what it should, for the reason it should`() {
        Pipeline.require()
        val romanian = Pipeline.open("ro-RO")
        try {
            val failures = readCases("pipeline_cases_ro.tsv").mapNotNull { case ->
                val outcome = romanian.commit(case.typed)
                when {
                    outcome.committed != case.committed ->
                        "  ${case.typed}: expected ${describe(case.committed)}, " +
                            "committed ${describe(outcome.committed)}  [${outcome.situation}]"
                    outcome.situation.name != case.situation ->
                        "  ${case.typed}: right answer for the wrong reason -- expected " +
                            "${case.situation}, was ${outcome.situation}"
                    else -> null
                }
            }
            assertTrue("${failures.size} failed:\n" + failures.joinToString("\n"),
                       failures.isEmpty())
        } finally {
            romanian.close()
        }
    }

    private fun describe(text: String?) = text ?: "nothing"

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
