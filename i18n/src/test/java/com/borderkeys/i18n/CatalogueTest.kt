// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogues themselves, checked as data.
 *
 * Reads the shipped assets off disk rather than through a Context, so the shipped files are what
 * is under test and the test needs no device. A translation is content, and content breaks the
 * same way code does -- a key renamed on one side, a `%s` dropped from a sentence that carries a
 * number -- except that nothing else would notice.
 */
class CatalogueTest {

    private val directory = File("src/main/assets/translations")

    private fun catalogue(language: String): Map<String, String> =
        LanguageManager.parse(File(directory, "$language.json").readText())

    private fun languages(): List<String> =
        directory.listFiles()!!.map { it.name.removeSuffix(".json") }.sorted()

    @Test
    fun `english is complete`() {
        assertTrue("no English catalogue", catalogue(Strings.Languages.DEFAULT).isNotEmpty())
    }

    @Test
    fun `every language carries the same keys`() {
        val problems = TranslationParity.problems(languages().associateWith { catalogue(it).keys })
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test
    fun `no entry is blank`() {
        for (language in languages()) {
            for ((key, value) in catalogue(language)) {
                assertTrue("$language:$key is blank", value.isNotBlank())
            }
        }
    }

    /**
     * A translation that drops a `%s` shows a sentence with a hole where the number was, and one
     * that adds a second `%s` shows the placeholder itself, because [LanguageManager.format]
     * substitutes only what it was given.
     */
    @Test
    fun `placeholders match english`() {
        val english = catalogue(Strings.Languages.DEFAULT)
        for (language in languages()) {
            if (language == Strings.Languages.DEFAULT) continue
            for ((key, value) in catalogue(language)) {
                val expected = english[key.removeSuffix("_many").removeSuffix("_one")]
                    ?: english[key] ?: continue
                assertEquals(
                    "$language:$key has a different number of %s than English",
                    expected.split("%s").size,
                    value.split("%s").size,
                )
            }
        }
    }

    /** Keys.kt is generated; this is what makes a stale copy of it a failing build. */
    @Test
    fun `Keys matches the english catalogue`() {
        val declared = File("src/main/java/com/borderkeys/i18n/Keys.kt")
            .readLines()
            .mapNotNull { Regex("""const val \w+ = "([^"]+)"""").find(it)?.groupValues?.get(1) }
            .toSet()
        val actual = catalogue(Strings.Languages.DEFAULT).keys
        assertEquals(
            "run tools/gen_keys.py",
            emptySet<String>(),
            (declared - actual) + (actual - declared),
        )
    }
}
