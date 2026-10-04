// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.AssumptionViolatedException
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Words typed with tap positions through the whole keyboard: the tap corpora of
 * tools/make_tap_corpus.py, one per profile, in the directory the `borderkeys.taps` property
 * names. The test words are the [TEST_WORDS] words from tap [TEST_FROM_TAP], as `touch_eval`
 * takes them; each typed with a slip into a string no active pack spells, at least three letters
 * long, is typed twice, once at its taps and once with no positions, and its outcome scored:
 * the word meant, another word, or left alone. With the taps, autocorrect's right word must stay
 * at or above the profile's floor, in percent. Learning is off, so the touch model prices the
 * taps with its default patterns.
 */
class TapCorpusTest {

    @Test
    fun `tapped words come out right at or above each profile's floor`() {
        Pipeline.require()
        val directory = System.getProperty("borderkeys.taps")?.let(::File)?.takeIf { it.isDirectory }
        if (directory == null) {
            val missing = "the tap corpora (tools/make_tap_corpus.py, one <profile>.tsv each) " +
                "in the directory -Pborderkeys.taps names"
            if (System.getenv("CI") != null) {
                throw AssertionError("$missing -- and CI is expected to have written them")
            }
            throw AssumptionViolatedException(missing)
        }
        val pipeline = Pipeline.open("en-US")
        try {
            val report = StringBuilder()
            val failures = ArrayList<String>()
            for ((profile, floor) in FLOORS) {
                val file = File(directory, "$profile.tsv")
                assertTrue("no tap corpus $file", file.isFile)
                val words = testWords(file)
                var slipped = 0
                val tapped = IntArray(3)
                val untapped = IntArray(3)
                for (word in words) {
                    if (word.typed == word.intended || word.typed.length < 3 || pipeline.spells(word.typed)) {
                        continue
                    }
                    ++slipped
                    score(pipeline.commitTapped(word.typed, word.xs, word.ys), word.intended, tapped)
                    score(pipeline.commitTapped(word.typed, NO_TAPS, NO_TAPS), word.intended, untapped)
                }
                assertTrue("no slipped words in $file", slipped > 0)
                // Compared as printed, to one decimal.
                val share = Math.round(1000.0 * tapped[RIGHT] / slipped) / 10.0
                report.append(
                    String.format(
                        Locale.ROOT,
                        "%-12s %4d slipped  taps: right %5.1f%% wrong %5.1f%% alone %5.1f%%  " +
                            "no taps: right %5.1f%% wrong %5.1f%% alone %5.1f%%  floor %.1f%%\n",
                        profile, slipped, share, 100.0 * tapped[WRONG] / slipped,
                        100.0 * tapped[ALONE] / slipped, 100.0 * untapped[RIGHT] / slipped,
                        100.0 * untapped[WRONG] / slipped, 100.0 * untapped[ALONE] / slipped, floor,
                    ),
                )
                if (share + 1e-9 < floor) {
                    failures += String.format(Locale.ROOT, "%s: %.1f%% right, floor %.1f%%", profile, share, floor)
                }
            }
            println(report)
            assertTrue("$report\nbelow their floor: $failures", failures.isEmpty())
        } finally {
            pipeline.close()
        }
    }

    private class Word(val intended: String, val typed: String, val xs: FloatArray, val ys: FloatArray)

    /** The [TEST_WORDS] words from tap [TEST_FROM_TAP] of a corpus. */
    private fun testWords(file: File): List<Word> {
        val words = ArrayList<Word>()
        var tap = 0L
        for (line in file.readLines()) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue
            }
            val fields = line.split('\t')
            if (fields.size < 3) {
                continue
            }
            val points = fields[2].trim().split(' ').filter { it.isNotEmpty() }.map { it.split(',') }
            if (points.size != fields[0].length || fields[1].length != fields[0].length) {
                continue
            }
            if (tap < TEST_FROM_TAP) {
                tap += fields[0].length
                continue
            }
            words += Word(
                fields[0], fields[1],
                FloatArray(points.size) { points[it][0].toFloat() },
                FloatArray(points.size) { points[it][1].toFloat() },
            )
            if (words.size == TEST_WORDS) {
                break
            }
        }
        return words
    }

    private fun score(outcome: Pipeline.Outcome, intended: String, tally: IntArray) {
        val committed = outcome.committed
        when {
            committed == null -> ++tally[ALONE]
            committed.equals(intended, ignoreCase = true) -> ++tally[RIGHT]
            else -> ++tally[WRONG]
        }
    }

    private companion object {
        const val TEST_FROM_TAP = 40_000L
        const val TEST_WORDS = 4_000
        const val RIGHT = 0
        const val WRONG = 1
        const val ALONE = 2
        val NO_TAPS = FloatArray(0)

        /** Autocorrect's right word with the taps, in percent, per profile. */
        val FLOORS = linkedMapOf(
            "centred" to 88.0,
            "low" to 85.3,
            "thumbs" to 85.2,
            "right-thumb" to 80.8,
            "precise" to 88.8,
            "sloppy" to 79.1,
        )
    }
}
