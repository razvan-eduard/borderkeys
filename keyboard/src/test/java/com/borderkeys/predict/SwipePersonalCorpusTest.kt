// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.language.LanguageTags
import com.borderkeys.typing.ManualClock
import com.borderkeys.typing.SwipePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import java.io.File
import java.io.PrintWriter

/**
 * A persona's history learned, then the clean words of its personal rows swiped after their
 * two-word context through [Pipeline], each from a recorded trace, with an empty personal model
 * and with the whole history, the model held as loaded while the rows are swiped: by the
 * geometric decoder, then by the Latin model when it loads. The history and the rows are
 * [PersonalCorpusTest]'s; the traces are `swipe_personal_<language>.csv` in the directory the
 * `borderkeys.swipes` property names, one gesture per clean row in order, written by
 * `tools/gesture_replay.py --synthesise --fold`; without the property the corpus is skipped. With
 * `borderkeys.readings` set, each language, model and decoder is a rows file,
 * `swipe_personal_<language>_<model>_<decoder>.tsv`: whether the word meant is the decoder's first
 * word and among its first three, case aside. Each decoder swipes in a pipeline of its own, so the
 * geometric one's rows never go through the neural decoder.
 */
class SwipePersonalCorpusTest {

    /** A clean row: the word meant, swiped after [previous2] and [previous1]. */
    private class Row(val meant: String, val previous2: String, val previous1: String)

    /** A recorded swipe: its word and its samples. */
    private class Trace(val word: String, val xs: FloatArray, val ys: FloatArray, val timestamps: LongArray)

    /** The personal model a row is swiped with. */
    private enum class Arm { EMPTY, FULL }

    /** The decoder a row is swiped with: the geometric one, then the Latin model. */
    private enum class Decoder { SHARK2, TCN }

    /**
     * What [swipeRows] read: the whole history's model; per decoder and arm each row's rank of the
     * word meant among the decoder's words, -1 when absent; and how many of the neural decoder's
     * swipes the geometric one answered.
     */
    private class Swiped(val full: PersonalModelLoad, val ranks: Map<Decoder, Map<Arm, List<Int>>>, val geometric: Int)

    @Test
    fun `clean words swiped after a learned history are read row by row`() {
        Pipeline.require()
        val directory = System.getProperty(SWIPES_PROPERTY)?.let(::File)?.takeIf { it.isDirectory }
            ?: throw AssumptionViolatedException(
                "the swipe traces (tools/gesture_replay.py --synthesise --fold, one swipe_personal_<language>.csv " +
                    "each) in the directory -P$SWIPES_PROPERTY names",
            )
        val learnAfter = KeyboardPreferences().learnAfter
        val report = StringBuilder()
        for ((tag, language) in PersonalCorpusTest.languagesNamed(System.getProperty(PersonalCorpusTest.LANGUAGES_PROPERTY))) {
            val history = content(File(Pipeline.CORPUS_DIRECTORY, "personal_history_$language.txt")).map { it.split(' ') }
            val rows = content(File(Pipeline.CORPUS_DIRECTORY, "personal_rows_$language.tsv"))
                .map { it.split('\t') }
                .filter { it[KIND] == PersonalCorpusTest.CLEAN }
                .map { fields -> fields[CONTEXT].split(' ').let { (previous2, previous1) -> Row(fields[MEANT], previous2, previous1) } }
            val traces = readTraces(File(directory, "swipe_personal_$language.csv"))
            assertEquals("the $language traces are its clean rows' words in order", rows.map { it.meant }, traces.map { it.word })
            report.append("  $language: ${rows.size} rows\n")
            val swiped = swipeRows(tag, history, rows, traces, report) { decoder, arm ->
                Pipeline.readings(
                    "swipe_personal_${language}_${arm.name.lowercase()}_${decoder.name.lowercase()}.tsv",
                    measures = MEASURES,
                )
            }
            for ((decoder, arms) in swiped.ranks) {
                for ((arm, ranks) in arms) {
                    report.append(
                        "    %-6s %-5s first %3d  top three %3d\n".format(
                            decoder.name.lowercase(), arm.name.lowercase(), ranks.count { it == 0 },
                            ranks.count { it in 0 until Pipeline.TOP_THREE },
                        ),
                    )
                }
            }
            if (Decoder.TCN in swiped.ranks) {
                report.append("    tcn swipes answered by the geometric decoder: ${swiped.geometric}\n")
            }
            val contexts = rows.groupingBy {
                PersonalSnapshot.contextOf(swiped.full, LanguageTags.primary(tag), it.previous2, it.previous1, it.meant, learnAfter)
            }.eachCount()
            report.append("    contexts: $contexts\n")
        }
        val summary = "clean words swiped after a learned history:\n$report"
        println(summary)
        Pipeline.readings("SUMMARY.txt", append = true)?.use { it.print(summary) }
    }

    @Test
    fun `a learned successor comes first once the history is loaded`() {
        Pipeline.require()
        val history = List(HISTORY_REPEATS) { listOf("purple", "elephant", "too") }
        val rows = listOf(Row("too", "purple", "elephant"), Row("people", "the", "nice"))
        val clock = ManualClock()
        // A swipe through "too" passes over "to"'s keys as well.
        val traces = rows.map { row ->
            SwipePath.through(row.meant, clock).let { Trace(row.meant, it.xs, it.ys, it.timestamps) }
        }
        val ranks = swipeRows("en-US", history, rows, traces, StringBuilder()) { _, _ -> null }.ranks.getValue(Decoder.SHARK2)
        assertEquals(listOf(false, true), ranks.getValue(Arm.EMPTY).map { it == 0 })
        assertEquals(listOf(true, true), ranks.getValue(Arm.FULL).map { it == 0 })
    }

    /**
     * Learns [history] in a pipeline on [tag]'s pack, then swipes each of [rows] along its trace
     * in [traces] with each [Arm]'s personal model, by each [Decoder] in a pipeline of its own,
     * the neural one only when the Latin model loads ([report] says so when it does not). Each
     * decoder and arm's rows are written to the rows file [open] gives, when it gives one: the two
     * verdicts, keyed by the context and the word meant, then the word meant's place among the
     * decoder's words ([Pipeline.placeOf]), and the decoder's first word.
     */
    private fun swipeRows(
        tag: String,
        history: List<List<String>>,
        rows: List<Row>,
        traces: List<Trace>,
        report: StringBuilder,
        open: (Decoder, Arm) -> PrintWriter?,
    ): Swiped {
        val learned = Pipeline.open(tag)
        val full = try {
            learned.learning = true
            learned.learnHistory(history)
            learned.personalSnapshot()
        } finally {
            learned.close()
        }
        val models = mapOf(Arm.EMPTY to PersonalSnapshot.EMPTY, Arm.FULL to full)
        val swiped = LinkedHashMap<Decoder, Map<Arm, List<Int>>>()
        var geometric = 0
        for (decoder in Decoder.entries) {
            val pipeline = Pipeline.open(tag)
            pipeline.learning = true
            try {
                if (decoder == Decoder.TCN && !pipeline.useLatinModel()) {
                    report.append("    the Latin model did not load: no tcn rows\n")
                    continue
                }
                swiped[decoder] = Arm.entries.associateWith { arm ->
                    val decoded = pipeline.withPersonal(models.getValue(arm)) {
                        rows.zip(traces).map { (row, trace) ->
                            pipeline.decodeRecorded(trace.xs, trace.ys, trace.timestamps, row.previous1, row.previous2).also {
                                val neural = pipeline.lastDecodeUsedNeural()
                                check(decoder == Decoder.TCN || !neural) { "a geometric swipe went through the neural decoder" }
                                geometric += if (decoder == Decoder.TCN && !neural) 1 else 0
                            }
                        }
                    }
                    val ranks = rows.zip(decoded).map { (row, words) -> words.indexOfFirst { it.equals(row.meant, ignoreCase = true) } }
                    open(decoder, arm)?.use { writer ->
                        for ((index, row) in rows.withIndex()) {
                            val rank = ranks[index]
                            writer.println(
                                Pipeline.row(
                                    listOf(rank == 0, rank in 0 until Pipeline.TOP_THREE), "${row.previous2} ${row.previous1} ${row.meant}",
                                    "${Pipeline.placeOf(rank)}\t${decoded[index].firstOrNull() ?: "-"}",
                                ),
                            )
                        }
                    }
                    ranks
                }
            } finally {
                pipeline.close()
            }
        }
        return Swiped(full, swiped, geometric)
    }

    /** The gestures of a corpus in `id,word,x,y,t` form, each id's samples in order. */
    private fun readTraces(file: File): List<Trace> {
        assertTrue("no swipe traces $file", file.isFile)
        val gestures = LinkedHashMap<String, Pair<String, MutableList<List<String>>>>()
        for (line in file.readLines()) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("id,")) {
                continue
            }
            val fields = line.split(',')
            gestures.getOrPut(fields[0]) { fields[1] to ArrayList() }.second += fields
        }
        return gestures.values.map { (word, samples) ->
            Trace(
                word,
                FloatArray(samples.size) { samples[it][X].toFloat() },
                FloatArray(samples.size) { samples[it][Y].toFloat() },
                LongArray(samples.size) { samples[it][T].toLong() },
            )
        }
    }

    /** The lines of [file] that are neither blank nor comments. */
    private fun content(file: File): List<String> =
        file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }

    private companion object {
        /** The system property naming the directory of the swipe traces. */
        const val SWIPES_PROPERTY = "borderkeys.swipes"

        /** A row's measures: the word meant is the decoder's first word, and among its first [Pipeline.TOP_THREE]. */
        val MEASURES = listOf(Pipeline.FIRST, Pipeline.IN_TOP_THREE)

        /** The columns of a personal row: `typed<TAB>meant<TAB>kind<TAB>previous2 previous1`. */
        const val MEANT = 1
        const val KIND = 2
        const val CONTEXT = 3

        /** The columns of a trace's sample: `id,word,x,y,t`. */
        const val X = 2
        const val Y = 3
        const val T = 4

        /** How many times the fixture's history runs its one phrase. */
        const val HISTORY_REPEATS = 12
    }
}
