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
                        "committed ${describe(committed)}  [${outcome.reason}]"
                outcome.reason != case.situation ->
                    "  ${case.typed}: right answer for the wrong reason -- expected " +
                        "${case.situation}, was ${outcome.reason}"
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
                changed.joinToString { "${it.typed} -> ${it.committed} [${it.reason}]" },
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

    private fun describe(text: String?) = text ?: "nothing"

    /**
     * The length guard, asserted at a setting where it can fire.
     *
     * At the default minimum of three the engine proposes nothing at all for a two-character
     * word -- its own edit-cost ceiling is tighter than the guard is -- so TooShort is
     * unreachable through the whole path and a case claiming it passes for the wrong reason.
     * Raised to five, the very words the default corrects are refused on length instead, which
     * is the rule this covers. Measured: at three, "tge", "hte", "teh" and "adn" all commit
     * "the" or "and" as Correctable.
     */
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

    /**
     * Every case again, with the first letter capitalised.
     *
     * The first letter of a field arrives capitalised and nothing in these payloads did, so a
     * rule that compared a spelling against the letters typed byte for byte passed the whole
     * suite and failed on every word on a phone. Capitalising is not a separate feature to be
     * covered separately: it is the ordinary state of the first word of anything anyone writes.
     *
     * The outcome must match the lower-case run with its own first letter capitalised. A case
     * whose committed word differs in any other way is a real difference and belongs in the
     * payload as its own row.
     */
    @Test
    fun `every case behaves the same when the first letter is capitalised`() {
        Pipeline.require()
        val romanian = Pipeline.open("ro-RO")
        val failures = try {
            // Each payload against the engine it was written for. Reading the Romanian rows
            // into the English pipeline is not a stricter test, it is a different one: every
            // Romanian word is then an unknown word and the answers mean nothing.
            val runs = readCases().map { it to pipeline } +
                readCases("pipeline_cases_ro.tsv").map { it to romanian }
            runs.mapNotNull { (case, engine) ->
                if (case.typed.isEmpty() || !case.typed[0].isLowerCase()) {
                    return@mapNotNull null
                }
                val capitalised = case.typed.replaceFirstChar { it.uppercaseChar() }
                val expected = case.committed?.replaceFirstChar { it.uppercaseChar() }
                // A name whose only correction was its capital has nothing left to do once the
                // capital is typed: "tehran" commits "Tehran", "Tehran" commits nothing, and
                // both leave the same text on screen.
                if (expected == capitalised) {
                    return@mapNotNull null
                }
                val outcome = engine.commit(capitalised)
                if (outcome.committed != expected) {
                    "  $capitalised: expected ${describe(expected)}, " +
                        "committed ${describe(outcome.committed)}  [${outcome.reason}]"
                } else {
                    null
                }
            }
        } finally {
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
