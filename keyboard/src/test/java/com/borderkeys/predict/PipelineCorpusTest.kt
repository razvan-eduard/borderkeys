// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every autocorrect corpus through the real path, with a floor per corpus.
 *
 * `suggest_eval --autocorrect` measures the engine alone. This measures what a user gets: the
 * engine, the bridge, [com.borderkeys.ime.WordCommit] and every guard in it. The two differ
 * wherever a guard refuses what the engine offered, and the floors here are the numbers
 * `docs/testing.md` quotes for the whole path.
 *
 * A corpus row is `typed<TAB>expected`; `expected` equal to `typed` means the word must be left
 * alone. Skipped, not failed, without the host bridge and the packs -- see [Pipeline.require].
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
            Corpus("ro-RO", "autocorrect_accents_ro.tsv", ACCENTS_FLOOR),
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
            try {
                for (line in file.readLines()) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue
                    }
                    val typed = line.substringBefore('\t')
                    val expected = line.substringAfter('\t').substringBefore('\t')
                    val outcome = pipeline.commit(typed)
                    val committed = outcome.committed
                    tally.cases++
                    // A corpus states spellings; the case a word lands in is the path's own.
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
        println("autocorrect corpora through the whole path:\n$report")
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
        const val UNKNOWN_FLOOR = 188
        const val DOUBLED_FLOOR = 187
        const val FIRSTLETTER_FLOOR = 166
        const val MARKS_FLOOR = 233
        const val ACCENTS_FLOOR = 237
    }
}
