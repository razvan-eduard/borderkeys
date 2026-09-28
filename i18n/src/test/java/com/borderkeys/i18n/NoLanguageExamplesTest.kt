// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * No description may explain itself with an example in a particular language. Language names as
 * labels are allowed.
 */
class NoLanguageExamplesTest {

    private val examples = listOf(
        "vreau", "Romanian", "rumeno", "rumano", "roumain", "Rumänisch", "românesc",
    )

    /**
     * Keys whose job is to name a language: `language_` (the dictionary names and the draft box's
     * translation targets) and `composer_working_translate_` (the working overlay's line).
     */
    private val labels = listOf(
        "language_", "assistant_english", "assistant_rom", "assistant_traducere",
        "assistant_translation", "screen_", "home_", "composer_working_translate_",
    )

    @Test
    fun `no catalogue explains itself with one language`() {
        val offences = mutableListOf<String>()
        val directory = File("src/main/assets/translations")
        for (file in directory.listFiles().orEmpty().sortedBy { it.name }) {
            val catalogue = LanguageManager.parse(file.readText())
            for ((key, value) in catalogue) {
                if (labels.any { key.startsWith(it) }) continue
                for (example in examples) {
                    if (value.contains(example)) {
                        offences += "${file.nameWithoutExtension}:$key mentions '$example'"
                    }
                }
            }
        }
        assertEquals(
            "describe the behaviour instead of illustrating it in one language",
            emptyList<String>(),
            offences,
        )
    }
}
