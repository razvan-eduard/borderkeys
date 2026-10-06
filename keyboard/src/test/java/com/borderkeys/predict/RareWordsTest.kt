// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.theme.KeyboardPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The "Rare words" setting through the whole keyboard: each step counts rarer words a dictionary
 * knows but never offers as spelled, so typing one leaves it alone.
 */
class RareWordsTest {

    @Test
    fun `each step keeps at least the rare words the step before kept`() {
        Pipeline.require()
        val pipeline = Pipeline.open("en-US")
        val report = StringBuilder()
        try {
            var keptBefore = -1
            for (step in KeyboardPreferences.RARE_WORDS_LISTED..KeyboardPreferences.RARE_WORDS_ALL) {
                pipeline.rareWords(step)
                val kept = CORPORA.associateWith { name -> right(pipeline, name) }
                report.append("  step $step: ")
                    .append(kept.entries.joinToString("  ") { (name, right) -> "$name $right" })
                    .append('\n')
                val unknown = kept.getValue(UNKNOWN)
                assertTrue("step $step keeps $unknown unknown words, the step before $keptBefore\n$report",
                    unknown >= keptBefore)
                keptBefore = unknown
            }
        } finally {
            pipeline.close()
        }
        println("rare words, words right per step:\n$report")
    }

    @Test
    fun `a rare word the dictionary knows is left alone once the setting reaches it`() {
        Pipeline.require()
        val pipeline = Pipeline.open("en-US")
        try {
            pipeline.rareWords(KeyboardPreferences.RARE_WORDS_LISTED)
            assertEquals("battle", pipeline.commit("tattle").committed)
            pipeline.rareWords(KeyboardPreferences.RARE_WORDS_ALL)
            assertNull(pipeline.commit("tattle").committed)
            assertTrue("tattle is offered", "tattle" !in pipeline.strip("tattl"))
        } finally {
            pipeline.close()
        }
    }

    /** How many rows of corpus [name] the keyboard answers as the corpus expects. */
    private fun right(pipeline: Pipeline, name: String): Int =
        File(CORPUS_DIRECTORY, name).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .count { line ->
                val typed = line.substringBefore('\t')
                val expected = line.substringAfter('\t').substringBefore('\t')
                val committed = pipeline.commit(typed).committed
                if (expected == typed) committed == null else committed.equals(expected, ignoreCase = true)
            }

    private companion object {
        const val CORPUS_DIRECTORY = "../native-tests/data"
        const val UNKNOWN = "autocorrect_unknown_en.tsv"
        val CORPORA = listOf(
            UNKNOWN, "autocorrect_unlisted_en.tsv", "autocorrect_real_en.tsv",
            "autocorrect_typo_en.tsv", "autocorrect_slip_en.tsv", "autocorrect_firstletter_en.tsv",
            "autocorrect_rareprefix_en.tsv", "autocorrect_known_en.tsv",
        )
    }
}
