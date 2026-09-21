// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.ime.SuggestionRow
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The word the space bar commits is the word the row outlines, over every corpus.
 *
 * The engine answers twice from one walk: the strip ranks what is probably being written, the
 * corrections heap what was probably meant, and the two disagree by design -- typing "teh" ranks
 * "tehran" first and commits "the". [SuggestionRow.arrange] is what reconciles them, inserting
 * the correction when the strip did not rank it. Nothing asserted that it always does.
 *
 * This is the test "put" would have failed: the strip showed "put | putem | can" and the space
 * bar committed "out", which was on no row anyone saw.
 */
class StripAgreementTest {

    @Test
    fun `what is committed is what the row outlines`() {
        Pipeline.require()
        val corpora = listOf(
            "en-US" to "autocorrect_midword_en.tsv",
            "en-US" to "autocorrect_typo_en.tsv",
            "en-US" to "autocorrect_unknown_en.tsv",
            "en-US" to "autocorrect_midtypo_en.tsv",
            "ro-RO" to "autocorrect_accents_ro.tsv",
        )
        val failures = mutableListOf<String>()
        var checked = 0

        for ((tag, name) in corpora) {
            val file = File(CORPUS_DIRECTORY, name)
            if (!file.isFile) {
                failures += "  $name is missing"
                continue
            }
            val pipeline = Pipeline.open(tag)
            try {
                for (line in file.readLines()) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue
                    }
                    val typed = line.substringBefore('\t')
                    val committed = pipeline.commit(typed).committed ?: continue
                    checked++

                    val shown = pipeline.strip(typed)
                    val words = arrayOfNulls<String>(SLOTS)
                    val count = minOf(shown.size, SLOTS)
                    for (index in 0 until count) {
                        words[index] = shown[index]
                    }
                    val row = SuggestionRow()
                    val filled = row.arrange(words, count, typed, SLOTS, committed)

                    val outlined = row.appliedIndex.takeIf { it in 0 until filled }?.let { words[it] }
                    if (outlined == null || !outlined.equals(committed, ignoreCase = true)) {
                        failures += "  $name: \"$typed\" commits \"$committed\", row outlines " +
                            "\"${outlined ?: "nothing"}\" of ${words.take(filled)}"
                    }
                }
            } finally {
                pipeline.close()
            }
        }

        assertTrue("no corpus case committed anything -- the corpora are missing", checked > 0)
        assertTrue(
            "${failures.size} of $checked committed words were not the outlined word:\n" +
                failures.take(20).joinToString("\n"),
            failures.isEmpty(),
        )
    }

    private companion object {
        /** The default number of suggestion slots, KeyboardPreferences.DEFAULT_SUGGESTIONS. */
        const val SLOTS = 3
        const val CORPUS_DIRECTORY = "../native-tests/data"
    }
}
