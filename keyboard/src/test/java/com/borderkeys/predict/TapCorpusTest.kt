// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale

/**
 * Words typed with tap positions through the whole keyboard: the tap corpora of
 * tools/make_tap_corpus.py, one per profile, in the directory the `borderkeys.taps` property
 * names. The test words are the [TEST_WORDS] words from tap [TEST_FROM_TAP], as `touch_eval`
 * takes them; each typed with a slip into a string no active pack spells, at least three letters
 * long, is typed twice, once at its taps and once with no positions, and its outcome scored:
 * the word meant, another word, or left alone. With the taps, autocorrect's right word must stay
 * at or above the profile's floor, in percent. Learning is off, so the touch model prices the
 * taps with its default patterns. With `borderkeys.readings` set, each profile's words are a
 * rows file, `taps_<profile>.tsv`.
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
                val typed = Pipeline.readings("taps_$profile.tsv", measures = MEASURES).use { rows ->
                    typeProfile(pipeline, words, rows)
                }
                val slipped = typed.slipped
                val tapped = typed.tapped
                val untapped = typed.untapped
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

    @Test
    fun `each slipped word is a row, right with its taps and right with none`() {
        Pipeline.require()
        val pipeline = Pipeline.open("en-US")
        try {
            val buffer = StringWriter()
            Pipeline.rowsFile(buffer, MEASURES).use { rows -> typeProfile(pipeline, FIXTURE, rows) }
            val lines = buffer.toString().lines().filter { it.isNotEmpty() }
            assertEquals("# measures: right right-untapped", lines.first())
            assertEquals(
                listOf("1\t1\t0 peoplw people", "1\t0\t1 fot got", "0\t0\t2 zxqvjk keyboard"),
                lines.drop(1).map { it.split('\t').take(3).joinToString("\t") },
            )
        } finally {
            pipeline.close()
        }
    }

    /** A word of a tap corpus: [index] is its place among the corpus's words, from 0. */
    private class Word(val index: Int, val intended: String, val typed: String, val xs: FloatArray, val ys: FloatArray)

    /** How many of a profile's words slipped, and how many came out [RIGHT], [WRONG] or [ALONE] at their taps and with none. */
    private class Typed(val slipped: Int, val tapped: IntArray, val untapped: IntArray)

    /**
     * Types each of [words] that slipped into a string no active pack spells, at least three
     * letters long, once at its taps and once with no positions, and counts how each came out.
     * Each such word is a row of [rows] when given: whether it came out right at its taps and with
     * none, keyed by its place in the corpus, the typed string and the word meant, then what each
     * typing committed (`-` for nothing) and why.
     */
    private fun typeProfile(pipeline: Pipeline, words: List<Word>, rows: PrintWriter?): Typed {
        var slipped = 0
        val tapped = IntArray(OUTCOMES)
        val untapped = IntArray(OUTCOMES)
        for (word in words) {
            if (word.typed == word.intended || word.typed.length < MIN_LENGTH || pipeline.spells(word.typed)) {
                continue
            }
            ++slipped
            val atTaps = pipeline.commitTapped(word.typed, word.xs, word.ys)
            val atNone = pipeline.commitTapped(word.typed, NO_TAPS, NO_TAPS)
            val tappedOutcome = outcomeOf(atTaps, word.intended)
            val untappedOutcome = outcomeOf(atNone, word.intended)
            ++tapped[tappedOutcome]
            ++untapped[untappedOutcome]
            rows?.println(
                Pipeline.row(
                    listOf(tappedOutcome == RIGHT, untappedOutcome == RIGHT), "${word.index} ${word.typed} ${word.intended}",
                    "${atTaps.committed ?: "-"}\t${atTaps.reason}\t${atNone.committed ?: "-"}\t${atNone.reason}",
                ),
            )
        }
        return Typed(slipped, tapped, untapped)
    }

    /** The [TEST_WORDS] words from tap [TEST_FROM_TAP] of a corpus. */
    private fun testWords(file: File): List<Word> {
        val words = ArrayList<Word>()
        var tap = 0L
        var index = -1
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
            ++index
            if (tap < TEST_FROM_TAP) {
                tap += fields[0].length
                continue
            }
            words += Word(
                index, fields[0], fields[1],
                FloatArray(points.size) { points[it][0].toFloat() },
                FloatArray(points.size) { points[it][1].toFloat() },
            )
            if (words.size == TEST_WORDS) {
                break
            }
        }
        return words
    }

    /**
     * Whether [outcome] is the word meant ([RIGHT], [Pipeline.Outcome.rightFor]), another word
     * ([WRONG]), or nothing ([ALONE]).
     */
    private fun outcomeOf(outcome: Pipeline.Outcome, intended: String): Int = when {
        outcome.rightFor(intended) -> RIGHT
        outcome.committed == null -> ALONE
        else -> WRONG
    }

    private companion object {
        const val TEST_FROM_TAP = 40_000L
        const val TEST_WORDS = 4_000
        const val RIGHT = 0
        const val WRONG = 1
        const val ALONE = 2
        const val OUTCOMES = 3

        /** The shortest slipped string typed. */
        const val MIN_LENGTH = 3
        val NO_TAPS = FloatArray(0)

        /** A row's measures: the word meant committed at its taps, and with no positions. */
        val MEASURES = listOf(Pipeline.RIGHT, "right-untapped")

        /**
         * Three slipped words, each letter tapped at the centre of the key of the letter under it
         * in the third string: one autocorrect reads with or without the taps, one only with
         * them, and one it leaves alone.
         */
        val FIXTURE = listOf(
            Triple("people", "peoplw", "people"),
            Triple("got", "fot", "got"),
            Triple("keyboard", "zxqvjk", "zxqvjk"),
        ).mapIndexed { index, (intended, typed, tapped) ->
            val centres = tapped.map { Pipeline.keyCentre(it) }
            Word(index, intended, typed, FloatArray(centres.size) { centres[it].first }, FloatArray(centres.size) { centres[it].second })
        }

        /** Autocorrect's right word with the taps, in percent, per profile. */
        val FLOORS = linkedMapOf(
            "centred" to 88.5,
            "low" to 87.0,
            "thumbs" to 86.6,
            "right-thumb" to 84.2,
            "precise" to 88.8,
            "sloppy" to 84.1,
        )
    }
}
