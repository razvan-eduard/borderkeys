// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.theme.KeyboardPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files

/**
 * The correction DEV rows typed through [Pipeline], so each row's commit is the app's own: the
 * corpora tools/make_dev_corpus.py writes into the directory the `borderkeys.dev` property names,
 * as many as it holds; without the property the reading is skipped, and a property that names no
 * directory fails it. Each row of
 * `corrections_<language>.tsv` is typed at a start and after its two words; Romanian's and
 * English's again at a start with both languages on, after a sentence in the other one, as
 * [TwoLanguageCorpusTest] types its corpora; each row of `personal_dev_<language>.tsv` after its
 * two words, once the persona's TEST history is learned, with each personal model
 * [PersonalCorpusTest] types its rows with. Each reading is a rows file in the
 * `borderkeys.readings` directory, its measures [MEASURES]: the field kept the word meant
 * ([Pipeline.Outcome.rightFor]), and the engine's ranking there, asked before the word was typed,
 * holds the word meant first and among its first three, case aside.
 */
class CorrectionDevReadingsTest {

    @Test
    fun `every DEV row is read through the app's own commit`() {
        Pipeline.require()
        val dev = System.getProperty(DEV_PROPERTY)?.let(::File)
            ?: throw AssumptionViolatedException(
                "the DEV corpora (tools/make_dev_corpus.py) in the directory -P$DEV_PROPERTY names",
            )
        assertTrue("-P$DEV_PROPERTY names no directory: $dev", dev.isDirectory)
        val report = readAll(dev) { name -> Pipeline.readings(name, measures = MEASURES) }
        val summary = "correction DEV rows through the whole path:\n$report"
        println(summary)
        Pipeline.readings("SUMMARY.txt", append = true)?.use { it.print(summary) }
    }

    @Test
    fun `a row is right when the commit is the word meant, and placed where the ranking puts it`() {
        Pipeline.require()
        val dev = Files.createTempDirectory("dev").toFile()
        try {
            File(dev, "corrections_en.tsv").writeText(
                "# Two rows: a slip autocorrect takes back to the word meant, and a word typed as meant.\n" +
                    "# typed<TAB>meant<TAB>kind<TAB>previous2 previous1\n" +
                    "asvised\tadvised\tslip\tit has\n" +
                    "people\tpeople\tclean\tso many\n",
            )
            val written = linkedMapOf<String, StringWriter>()
            readAll(dev) { name -> Pipeline.rowsFile(written.getOrPut(name) { StringWriter() }, MEASURES) }
            assertEquals(listOf("corrections_en.tsv", "corrections_en_in_context.tsv"), written.keys.toList())
            for ((name, buffer) in written) {
                val lines = buffer.toString().lines().filter { it.isNotEmpty() }
                assertEquals(name, "# measures: right first top-three", lines.first())
                assertEquals(
                    name,
                    listOf("1\t1\t1\tit has asvised\tslip", "1\t1\t1\tso many people\tclean"),
                    lines.drop(1).map { it.split('\t').take(5).joinToString("\t") },
                )
            }
        } finally {
            dev.deleteRecursively()
        }
    }

    private companion object {
        /** The system property naming the directory of the DEV corpora. */
        const val DEV_PROPERTY = "borderkeys.dev"

        /** A row's measures: the commit, then the word meant's place in the engine's ranking. */
        val MEASURES = listOf(Pipeline.RIGHT, Pipeline.FIRST, Pipeline.IN_TOP_THREE)

        /** The languages whose correction rows are read: each pack's tag and the corpora's code. */
        val LANGUAGES = listOf(
            "en-US" to "en", "ro-RO" to "ro", "de-DE" to "de", "fr-FR" to "fr", "es-ES" to "es", "it-IT" to "it",
        )

        /**
         * Reads every corpus [dev] holds into the rows file [open] gives for its name, when it
         * gives one, and returns the report: one line per rows file, its rows, right, first and
         * top three by kind.
         */
        fun readAll(dev: File, open: (String) -> PrintWriter?): String {
            val report = StringBuilder()
            val corrections = LANGUAGES.mapNotNull { (tag, language) ->
                val file = File(dev, "corrections_$language.tsv").takeIf { it.isFile } ?: return@mapNotNull null
                Triple(tag, language, PersonalCorpusTest.rowsOf(file))
            }
            for ((tag, language, rows) in corrections) {
                val pipeline = Pipeline.open(tag)
                try {
                    val atStart = rows.map { pipeline.commitAfter(it.typed, null, null) }
                    write(report, "corrections_$language.tsv", rows, atStart, open)
                    val inContext = rows.map { pipeline.commitAfter(it.typed, it.previous2, it.previous1) }
                    write(report, "corrections_${language}_in_context.tsv", rows, inContext, open)
                } finally {
                    pipeline.close()
                }
            }
            val byLanguage = corrections.associate { (_, language, rows) -> language to rows }
            val romanian = byLanguage[ROMANIAN]
            val english = byLanguage[ENGLISH]
            if (romanian != null && english != null) {
                val pipeline = Pipeline.open("ro-RO", "en-US")
                try {
                    pipeline.languageLock(KeyboardPreferences.LANGUAGE_LOCK_BALANCED)
                    val runs = listOf(
                        Triple(romanian, TwoLanguageCorpusTest.ENGLISH, after(ROMANIAN, ENGLISH)),
                        Triple(english, TwoLanguageCorpusTest.ROMANIAN, after(ENGLISH, ROMANIAN)),
                    )
                    for ((rows, lead, name) in runs) {
                        pipeline.forgetLanguage()
                        pipeline.commitPhrase(lead)
                        val decided = pipeline.languageEvidence()
                        val readings = rows.map {
                            pipeline.restoreLanguageEvidence(decided)
                            pipeline.commitAfter(it.typed, null, null)
                        }
                        write(report, name, rows, readings, open)
                    }
                } finally {
                    pipeline.close()
                }
            }
            for ((tag, language) in PersonalCorpusTest.LANGUAGES) {
                val file = File(dev, "personal_dev_$language.tsv").takeIf { it.isFile } ?: continue
                val rows = PersonalCorpusTest.rowsOf(file)
                val pipeline = Pipeline.open(tag)
                pipeline.learning = true
                try {
                    val history = File(Pipeline.CORPUS_DIRECTORY, "personal_history_$language.txt")
                    val full = PersonalCorpusTest.learn(pipeline, history)
                    for ((arm, readings) in PersonalCorpusTest.typeByArm(pipeline, full, rows)) {
                        write(report, "personal_dev_${language}_${arm.name.lowercase()}.tsv", rows, readings, open)
                    }
                } finally {
                    pipeline.close()
                }
            }
            return report.toString()
        }

        /**
         * Writes each of [rows] as it was read, its reading in [readings], into the rows file [open]
         * gives for [name]: its verdicts and key, then its kind, the word meant's place in the
         * ranking and the reading line; appends the file's tally to [report].
         */
        fun write(
            report: StringBuilder,
            name: String,
            rows: List<PersonalCorpusTest.Row>,
            readings: List<Pipeline.Reading>,
            open: (String) -> PrintWriter?,
        ) {
            // By kind: the rows, then the rows each measure holds.
            val tally = sortedMapOf<String, IntArray>()
            open(name).use { out ->
                for ((row, reading) in rows.zip(readings)) {
                    val rank = reading.ranked.indexOfFirst { it.equals(row.meant, ignoreCase = true) }
                    val verdicts = listOf(reading.outcome.rightFor(row.meant), rank == 0, rank in 0 until Pipeline.TOP_THREE)
                    out?.println(Pipeline.row(verdicts, row.key, "${row.kind}\t${Pipeline.placeOf(rank)}\t${reading.line}"))
                    val counts = tally.getOrPut(row.kind) { IntArray(1 + MEASURES.size) }
                    counts[0]++
                    verdicts.forEachIndexed { at, verdict -> counts[1 + at] += if (verdict) 1 else 0 }
                }
            }
            report.append("  $name\n")
            for ((kind, counts) in tally) {
                report.append("    %-8s %4d rows  right %4d  first %4d  top three %4d\n".format(kind, *counts.toTypedArray()))
            }
        }

        /** The rows file of [language]'s correction rows typed after a sentence in [lead]'s. */
        fun after(language: String, lead: String): String = "corrections_${language}_after_$lead.tsv"

        /** The codes of the two languages read both on. */
        const val ROMANIAN = TwoLanguageCorpusTest.ROMANIAN_CODE
        const val ENGLISH = TwoLanguageCorpusTest.ENGLISH_CODE
    }
}
