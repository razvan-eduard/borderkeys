// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The apostrophe-twin rule on a hundred prepared swipes through [Pipeline]: `ill`, `is` and the
 * commonest English bare spellings with a twin, each through its keys' centres, by the geometric
 * decoder and by the Latin model when it loads. A row is checked by a decoder when its own words
 * hold the bare spelling or its twin. Then the keyboard's list puts the rule's word first and its
 * partner right after it (`I'll` then `ill`, else the bare spelling then its twin), a spelling
 * with no twin has none spliced after it, and, when the decoder's top word is the bare spelling or
 * its twin, the rule's word is the one written. Every row must be checked by at least one decoder,
 * at least [GEOMETRIC_ROWS] by the geometric one and at least [LATIN_ROWS] by the Latin model,
 * which must load under CI. How well the decoders read the swipes is not gated here.
 */
class SwipeTwinsTest {

    @Test
    fun `swiped words with apostrophe twins come out as the rule says`() {
        Pipeline.require()
        val bases = File(PAIRS).readLines()
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.split('\t') }
            .sortedByDescending { it[2].toLong() }
            .map { it[0] }
        val words = listOf("ill", "is") + bases.filter { it != "ill" }.take(SWIPES - 2)
        val pipeline = Pipeline.open("en-US")
        try {
            val report = StringBuilder()
            val wrong = ArrayList<String>()
            val checkedBy = HashMap<String, Int>()
            var geometricRows = 0
            var latinRows = -1
            var checks = 0
            for (neural in listOf(false, true)) {
                if (neural && !pipeline.useLatinModel()) {
                    if (System.getenv("CI") != null) {
                        throw AssertionError("the Latin model did not load, and CI is expected to have it")
                    }
                    report.append("the Latin model did not load; geometric decoder only\n")
                    continue
                }
                val tier = if (neural) "Latin model" else "geometric"
                var checked = 0
                val skipped = ArrayList<String>()
                for (word in words) {
                    val twin = pipeline.twinOf(word)
                    val swiped = pipeline.swipe(word)
                    fun isPair(text: String) =
                        text.equals(word, ignoreCase = true) || (twin != null && text.equals(twin, ignoreCase = true))
                    if (swiped.decoded.none(::isPair)) {
                        skipped += "$word (decoded ${swiped.decoded.take(SHOWN)})"
                        continue
                    }
                    ++checked
                    checkedBy[word] = (checkedBy[word] ?: 0) + 1
                    val first = if (word == "ill") "I'll" else word
                    val partner = if (word == "ill") "ill" else twin
                    val at = swiped.offered.indexOfFirst { it.equals(first, ignoreCase = true) }
                    val after = swiped.offered.getOrNull(at + 1)
                    ++checks
                    if (at < 0) {
                        wrong += "$tier: $word, $first not in ${swiped.offered}"
                    } else if (partner != null && !partner.equals(after, ignoreCase = true)) {
                        wrong += "$tier: $word, $partner not right after $first in ${swiped.offered}"
                    } else if (partner == null && after != null && after.contains('\'') &&
                        after.replace("'", "").equals(word, ignoreCase = true)
                    ) {
                        wrong += "$tier: $word, a twin $after spliced after it"
                    }
                    if (isPair(swiped.decoded.first())) {
                        ++checks
                        if (swiped.written != first) {
                            wrong += "$tier: $word wrote ${swiped.written}, expected $first"
                        }
                    }
                }
                if (neural) {
                    latinRows = checked
                } else {
                    geometricRows = checked
                }
                report.append("$tier: $checked of ${words.size} rows checked")
                report.append(if (skipped.isEmpty()) "\n" else "; not decoded, so not checked: $skipped\n")
            }
            val unchecked = words.filter { it !in checkedBy }
            report.append("twin rule: ${checks - wrong.size} of $checks checks as expected\n")
            println(report)
            assertTrue("$report\nwrong: $wrong", wrong.isEmpty())
            assertTrue("$report\nchecked by neither decoder: $unchecked", unchecked.isEmpty())
            assertTrue(
                "$report\nthe geometric decoder checked $geometricRows rows, fewer than $GEOMETRIC_ROWS",
                geometricRows >= GEOMETRIC_ROWS,
            )
            if (latinRows >= 0) {
                assertTrue(
                    "$report\nthe Latin model checked $latinRows rows, fewer than $LATIN_ROWS",
                    latinRows >= LATIN_ROWS,
                )
            }
        } finally {
            pipeline.close()
        }
    }

    private companion object {
        const val PAIRS = "src/main/assets/contractions/en-US.pairs.txt"
        const val SWIPES = 100
        const val GEOMETRIC_ROWS = 100
        const val LATIN_ROWS = 93
        const val SHOWN = 3
    }
}
