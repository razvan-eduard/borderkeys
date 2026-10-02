// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The autocorrect corpora with Romanian and English both on, each word typed right after a
 * sentence that decided either language.
 */
class TwoLanguageCorpusTest {

    private class Run(val file: String, val lead: String, val floor: Int)

    @Test
    fun `each language is read right after a sentence in either`() {
        Pipeline.require()
        val runs = listOf(
            Run("autocorrect_accents_ro.tsv", ENGLISH, ACCENTS_RO_AFTER_ENGLISH),
            Run("autocorrect_plain_ro.tsv", ENGLISH, PLAIN_RO_AFTER_ENGLISH),
            Run("autocorrect_twins_ro.tsv", ENGLISH, TWINS_RO_AFTER_ENGLISH),
            Run("autocorrect_typo_en.tsv", ROMANIAN, TYPO_EN_AFTER_ROMANIAN),
            Run("autocorrect_unknown_en.tsv", ROMANIAN, UNKNOWN_EN_AFTER_ROMANIAN),
            Run("autocorrect_doubled_en.tsv", ROMANIAN, DOUBLED_EN_AFTER_ROMANIAN),
            Run("autocorrect_firstletter_en.tsv", ROMANIAN, FIRSTLETTER_EN_AFTER_ROMANIAN),
            Run("autocorrect_marks_en.tsv", ROMANIAN, MARKS_EN_AFTER_ROMANIAN),
            Run("autocorrect_accents_ro.tsv", ROMANIAN, ACCENTS_RO_AFTER_ROMANIAN),
            Run("autocorrect_plain_ro.tsv", ROMANIAN, PLAIN_RO_AFTER_ROMANIAN),
            Run("autocorrect_twins_ro.tsv", ROMANIAN, TWINS_RO_AFTER_ROMANIAN),
            Run("autocorrect_typo_en.tsv", ENGLISH, TYPO_EN_AFTER_ENGLISH),
            Run("autocorrect_unknown_en.tsv", ENGLISH, UNKNOWN_EN_AFTER_ENGLISH),
            Run("autocorrect_doubled_en.tsv", ENGLISH, DOUBLED_EN_AFTER_ENGLISH),
            Run("autocorrect_firstletter_en.tsv", ENGLISH, FIRSTLETTER_EN_AFTER_ENGLISH),
            Run("autocorrect_marks_en.tsv", ENGLISH, MARKS_EN_AFTER_ENGLISH),
        )
        val report = StringBuilder()
        val failures = mutableListOf<String>()
        val pipeline = Pipeline.open("ro-RO", "en-US")
        try {
            pipeline.languageLock(BALANCED_EVIDENCE)
            for (run in runs) {
                val file = File(CORPUS_DIRECTORY, run.file)
                if (!file.isFile) {
                    failures += "  ${run.file} is missing"
                    continue
                }
                pipeline.forgetLanguage()
                pipeline.commitPhrase(run.lead)
                val decided = pipeline.languageEvidence()
                var cases = 0
                var right = 0
                val misses = mutableListOf<String>()
                for (line in file.readLines()) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue
                    }
                    val typed = line.substringBefore('\t')
                    val expected = line.substringAfter('\t').substringBefore('\t')
                    pipeline.restoreLanguageEvidence(decided)
                    val outcome = pipeline.commit(typed)
                    val committed = outcome.committed
                    cases++
                    // Compared ignoring case.
                    val isRight = if (expected == typed) {
                        committed == null
                    } else {
                        committed.equals(expected, ignoreCase = true)
                    }
                    if (isRight) {
                        right++
                    } else {
                        misses += "$typed -> ${committed ?: "(nothing)"} [${outcome.reason}]"
                    }
                }
                val after = if (run.lead == ENGLISH) "after English" else "after Romanian"
                report.append(
                    "  %-32s %-15s right %3d of %3d\n".format(run.file, after, right, cases),
                )
                for (miss in misses.take(MISSES_LISTED)) {
                    report.append("      $miss\n")
                }
                if (right < run.floor) {
                    failures += "  ${run.file} $after: $right right, floor ${run.floor}"
                }
            }
        } finally {
            pipeline.close()
        }
        println("autocorrect corpora, Romanian and English both on:\n$report")
        assertTrue(
            "${failures.size} runs fell below their floor:\n" + failures.joinToString("\n") +
                "\n$report",
            failures.isEmpty(),
        )
    }

    private companion object {
        const val CORPUS_DIRECTORY = "../native-tests/data"
        const val MISSES_LISTED = 12

        /** The Languages screen's default. */
        const val BALANCED_EVIDENCE = 1.8f

        const val ENGLISH = "what is the best way to learn this song before the weekend"
        const val ROMANIAN = "mi-a trecut prin cap că ar fi bine să vorbim despre asta mâine"

        /** Rows answered as the corpus expects, out of 200; 248 for accents, 240 for marks. */
        const val ACCENTS_RO_AFTER_ENGLISH = 234
        const val PLAIN_RO_AFTER_ENGLISH = 200
        const val TWINS_RO_AFTER_ENGLISH = 178
        const val TYPO_EN_AFTER_ROMANIAN = 188
        const val UNKNOWN_EN_AFTER_ROMANIAN = 191
        const val DOUBLED_EN_AFTER_ROMANIAN = 191
        const val FIRSTLETTER_EN_AFTER_ROMANIAN = 156
        const val MARKS_EN_AFTER_ROMANIAN = 239
        const val ACCENTS_RO_AFTER_ROMANIAN = 237
        const val PLAIN_RO_AFTER_ROMANIAN = 200
        const val TWINS_RO_AFTER_ROMANIAN = 186
        const val TYPO_EN_AFTER_ENGLISH = 199
        const val UNKNOWN_EN_AFTER_ENGLISH = 191
        const val DOUBLED_EN_AFTER_ENGLISH = 194
        const val FIRSTLETTER_EN_AFTER_ENGLISH = 168
        const val MARKS_EN_AFTER_ENGLISH = 239
    }
}
