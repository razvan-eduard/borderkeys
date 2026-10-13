// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.language.LanguageTags
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A persona's history learned, then words typed after their two-word context through [Pipeline],
 * with a floor per learned context and kind. The corpora are written by
 * tools/make_personal_corpus.py: `personal_history_<language>.txt` holds one run of words per
 * line, and each row of `personal_rows_<language>.tsv` is `typed<TAB>meant<TAB>kind<TAB>previous2
 * previous1`, a `clean` row right when the word is left alone and a typo right when it becomes the
 * meant word. Each row is typed with an empty personal model, with the history's words alone, and
 * with the whole history, the model held as loaded while the rows are typed.
 */
class PersonalCorpusTest {

    private class Row(
        val typed: String,
        val meant: String,
        val kind: String,
        val previous2: String,
        val previous1: String,
    ) {
        val clean: Boolean get() = kind == CLEAN

        /** Whether [outcome] is what this row expects, case aside. */
        fun rightFor(outcome: Pipeline.Outcome): Boolean =
            if (clean) outcome.committed == null else outcome.committed.equals(meant, ignoreCase = true)
    }

    /** The personal model a row is typed with. */
    private enum class Arm { EMPTY, WORDS, FULL }

    private class Tally {
        var cases = 0
        var right = 0
        val misses = mutableListOf<String>()
    }

    @Test
    fun `a learned history holds its floors through the whole path`() {
        Pipeline.require()
        val learnAfter = KeyboardPreferences().learnAfter
        val report = StringBuilder()
        val failures = mutableListOf<String>()
        for ((tag, language) in LANGUAGES) {
            val historyFile = File(Pipeline.CORPUS_DIRECTORY, "personal_history_$language.txt")
            val rowsFile = File(Pipeline.CORPUS_DIRECTORY, "personal_rows_$language.tsv")
            if (!historyFile.isFile || !rowsFile.isFile) {
                failures += "  the $language corpus is missing"
                continue
            }
            val pipeline = Pipeline.open(tag)
            pipeline.learning = true
            val tallies = mutableMapOf<String, Tally>()
            val readings = Arm.entries.associateWith { arm ->
                Pipeline.readings("personal_${language}_${arm.name.lowercase()}.tsv")
            }
            try {
                pipeline.learnHistory(content(historyFile).map { it.split(' ') })
                val full = pipeline.personalSnapshot()
                val personal = mapOf(
                    Arm.EMPTY to PersonalSnapshot.EMPTY,
                    Arm.WORDS to full.withoutPhrases(),
                    Arm.FULL to full,
                )
                val rows = content(rowsFile).map { line ->
                    val (typed, meant, kind, context) = line.split('\t')
                    val (previous2, previous1) = context.split(' ')
                    Row(typed, meant, kind, previous2, previous1)
                }
                val started = System.nanoTime()
                val typedByArm = Arm.entries.associateWith { arm ->
                    pipeline.withPersonal(personal.getValue(arm)) {
                        rows.map { pipeline.commitAfter(it.typed, it.previous2, it.previous1) }
                    }
                }
                for ((index, row) in rows.withIndex()) {
                    val context = full.contextOf(LanguageTags.primary(tag), row.previous2, row.previous1, row.meant, learnAfter)
                    val byArm = Arm.entries.associateWith { arm -> typedByArm.getValue(arm)[index] }
                    for ((arm, reading) in byArm) {
                        readings[arm]?.println("${context.name.lowercase()}\t${row.kind}\t${reading.line}")
                        val tally = tallies.getOrPut(key(language, arm, context, row.clean)) { Tally() }
                        tally.cases++
                        if (row.rightFor(reading.outcome)) {
                            tally.right++
                        } else {
                            tally.misses += "${row.previous2} ${row.previous1} ${row.typed} -> " +
                                "${reading.outcome.committed ?: "(nothing)"} [${reading.outcome.reason}]"
                        }
                    }
                    val words = byArm.getValue(Arm.WORDS)
                    val whole = byArm.getValue(Arm.FULL)
                    if (context == PersonalSnapshot.Context.NONE && words != whole) {
                        failures += "  $language: ${row.previous2} ${row.previous1} ${row.typed} reads " +
                            "differently with the history's phrases:\n    ${words.line}\n    ${whole.line}"
                    }
                }
                val millisPerRow = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) /
                    (rows.size * Arm.entries.size).coerceAtLeast(1)
                report.append("  $language: ${rows.size} rows, ${full.words.size} words, ${full.pairs.size} pairs, " +
                    "${full.triples.size} triples loaded; ~$millisPerRow ms per typed row\n")
            } finally {
                readings.values.forEach { it?.close() }
                pipeline.close()
            }
            for (context in PersonalSnapshot.Context.entries) {
                for (clean in listOf(true, false)) {
                    val line = StringBuilder("    %-7s %-6s".format(context.name.lowercase(), if (clean) CLEAN else TYPO))
                    for (arm in Arm.entries) {
                        val tally = tallies[key(language, arm, context, clean)] ?: Tally()
                        line.append("  %s %3d/%3d".format(arm.name.lowercase(), tally.right, tally.cases))
                    }
                    report.append(line).append('\n')
                    val full = tallies[key(language, Arm.FULL, context, clean)] ?: Tally()
                    for (miss in full.misses.take(MISSES_LISTED)) {
                        report.append("        $miss\n")
                    }
                    val name = floorName(language, context, clean)
                    val floor = FLOORS[name]
                    if (floor == null) {
                        failures += "  $name has no floor; full reads ${full.right}"
                    } else if (full.right < floor) {
                        failures += "  $name: ${full.right} right, floor $floor"
                    }
                }
            }
        }
        val summary = "a learned history through the whole path:\n$report"
        println(summary)
        Pipeline.readings("SUMMARY.txt", append = true)?.use { it.print(summary) }
        assertTrue(
            "${failures.size} failures:\n" + failures.joinToString("\n") + "\n$report",
            failures.isEmpty(),
        )
    }

    /** The lines of [file] that are neither blank nor comments. */
    private fun content(file: File): List<String> =
        file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }

    private fun key(language: String, arm: Arm, context: PersonalSnapshot.Context, clean: Boolean) =
        "$language ${arm.name} ${context.name} $clean"

    private fun floorName(language: String, context: PersonalSnapshot.Context, clean: Boolean) =
        "${language}_${context.name.lowercase()}_${if (clean) CLEAN else TYPO}"

    private companion object {
        const val MISSES_LISTED = 12
        const val CLEAN = "clean"
        const val TYPO = "typo"

        /** The packs measured, by the corpora's language code. */
        val LANGUAGES = listOf("en-US" to "en", "ro-RO" to "ro", "es-ES" to "es")

        /** Rows answered as expected with the whole history, out of the class's rows of that kind. */
        val FLOORS = mapOf(
            "en_meant_clean" to 81, "en_meant_typo" to 73,
            "en_others_clean" to 289, "en_others_typo" to 222,
            "en_none_clean" to 230, "en_none_typo" to 170,
            "ro_meant_clean" to 106, "ro_meant_typo" to 93,
            "ro_others_clean" to 297, "ro_others_typo" to 228,
            "ro_none_clean" to 191, "ro_none_typo" to 131,
            "es_meant_clean" to 58, "es_meant_typo" to 54,
            "es_others_clean" to 332, "es_others_typo" to 264,
            "es_none_clean" to 204, "es_none_typo" to 141,
        )
    }
}
