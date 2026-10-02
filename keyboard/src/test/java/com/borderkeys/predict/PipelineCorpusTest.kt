// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every autocorrect corpus through [Pipeline], with a floor per corpus. A corpus row is
 * `typed<TAB>expected`; `expected` equal to `typed` means the word is left alone.
 */
class PipelineCorpusTest {

    private class Corpus(val tag: String, val file: String, val floor: Int)

    private class Tally {
        var cases = 0
        var right = 0
        var wrongWord = 0
        var leftAlone = 0
        val misses = mutableListOf<String>()
    }

    @Test
    fun `every corpus holds its floor through the whole path`() {
        Pipeline.require()
        val corpora = listOf(
            Corpus("en-US", "autocorrect_typo_en.tsv", TYPO_FLOOR),
            Corpus("en-US", "autocorrect_midword_en.tsv", MIDWORD_FLOOR),
            Corpus("en-US", "autocorrect_midtypo_en.tsv", MIDTYPO_FLOOR),
            Corpus("en-US", "autocorrect_unknown_en.tsv", UNKNOWN_FLOOR),
            Corpus("en-US", "autocorrect_doubled_en.tsv", DOUBLED_FLOOR),
            Corpus("en-US", "autocorrect_firstletter_en.tsv", FIRSTLETTER_FLOOR),
            Corpus("en-US", "autocorrect_marks_en.tsv", MARKS_FLOOR),
            Corpus("en-US", "autocorrect_slip_en.tsv", SLIP_FLOOR),
            Corpus("en-US", "autocorrect_omitted_en.tsv", OMITTED_FLOOR),
            Corpus("en-US", "autocorrect_extra_en.tsv", EXTRA_FLOOR),
            Corpus("en-US", "autocorrect_rareprefix_en.tsv", RAREPREFIX_FLOOR),
            Corpus("en-US", "autocorrect_known_en.tsv", KNOWN_FLOOR),
            Corpus("ro-RO", "autocorrect_accents_ro.tsv", ACCENTS_FLOOR),
            Corpus("ro-RO", "autocorrect_twins_ro.tsv", TWINS_RO_FLOOR),
            Corpus("ro-RO", "autocorrect_plain_ro.tsv", PLAIN_RO_FLOOR),
            Corpus("fr-FR", "autocorrect_twins_fr.tsv", TWINS_FR_FLOOR),
            Corpus("fr-FR", "autocorrect_plain_fr.tsv", PLAIN_FR_FLOOR),
            Corpus("es-ES", "autocorrect_twins_es.tsv", TWINS_ES_FLOOR),
            Corpus("es-ES", "autocorrect_plain_es.tsv", PLAIN_ES_FLOOR),
            Corpus("it-IT", "autocorrect_twins_it.tsv", TWINS_IT_FLOOR),
            Corpus("it-IT", "autocorrect_plain_it.tsv", PLAIN_IT_FLOOR),
        )
        val report = StringBuilder()
        val failures = mutableListOf<String>()
        for (corpus in corpora) {
            val file = File(CORPUS_DIRECTORY, corpus.file)
            if (!file.isFile) {
                failures += "  ${corpus.file} is missing"
                continue
            }
            val tally = Tally()
            val pipeline = Pipeline.open(corpus.tag)
            val readings = Pipeline.readings(corpus.file)
            try {
                for (line in file.readLines()) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue
                    }
                    val typed = line.substringBefore('\t')
                    val expected = line.substringAfter('\t').substringBefore('\t')
                    val outcome = pipeline.commit(typed)
                    readings?.println(pipeline.readingLine(outcome))
                    val committed = outcome.committed
                    tally.cases++
                    // Compared ignoring case.
                    val right = if (expected == typed) {
                        committed == null
                    } else {
                        committed.equals(expected, ignoreCase = true)
                    }
                    when {
                        right -> tally.right++
                        committed == null -> tally.leftAlone++
                        else -> tally.wrongWord++
                    }
                    if (!right) {
                        tally.misses += "$typed -> ${committed ?: "(nothing)"} [${outcome.reason}]"
                    }
                }
            } finally {
                readings?.close()
                pipeline.close()
            }
            report.append(
                "  %-28s right %3d  wrong word %3d  left alone %3d  of %3d\n".format(
                    corpus.file, tally.right, tally.wrongWord, tally.leftAlone, tally.cases,
                ),
            )
            for (miss in tally.misses.take(MISSES_LISTED)) {
                report.append("      $miss\n")
            }
            if (tally.right < corpus.floor) {
                failures += "  ${corpus.file}: ${tally.right} right, floor ${corpus.floor}"
            }
        }
        val summary = "autocorrect corpora through the whole path:\n$report"
        println(summary)
        Pipeline.readings("SUMMARY.txt", append = true)?.use { it.print(summary) }
        assertTrue(
            "${failures.size} corpora fell below their floor:\n" + failures.joinToString("\n") +
                "\n$report",
            failures.isEmpty(),
        )
    }

    private companion object {
        const val CORPUS_DIRECTORY = "../native-tests/data"
        const val MISSES_LISTED = 24

        /** Rows answered as the corpus expects, out of 200 -- 240 for the marks corpus and
         *  248 for the accents corpus. */
        const val TYPO_FLOOR = 199
        const val MIDWORD_FLOOR = 192
        const val MIDTYPO_FLOOR = 44
        const val UNKNOWN_FLOOR = 190
        const val DOUBLED_FLOOR = 191
        const val FIRSTLETTER_FLOOR = 167
        const val MARKS_FLOOR = 240
        const val SLIP_FLOOR = 178
        const val OMITTED_FLOOR = 184
        const val EXTRA_FLOOR = 192
        const val RAREPREFIX_FLOOR = 131
        const val KNOWN_FLOOR = 193
        const val ACCENTS_FLOOR = 237

        /** Out of 200, 190 for the Spanish twins and 57 for the Italian ones. */
        const val TWINS_RO_FLOOR = 186
        const val PLAIN_RO_FLOOR = 200
        const val TWINS_FR_FLOOR = 196
        const val PLAIN_FR_FLOOR = 200
        const val TWINS_ES_FLOOR = 188
        const val PLAIN_ES_FLOOR = 200
        const val TWINS_IT_FLOOR = 57
        const val PLAIN_IT_FLOOR = 200
    }
}
