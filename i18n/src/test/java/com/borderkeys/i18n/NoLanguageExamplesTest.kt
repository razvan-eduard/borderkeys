// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * No description may explain itself with an example in a particular language.
 *
 * The catalogues were written from English originals that used Romanian words to illustrate
 * phrase learning and language detection. Translated literally, that put Romanian examples in
 * front of French and German readers, who have no reason to care what happens between two
 * languages neither of them writes. A description has to describe the behaviour.
 *
 * Language names as *labels* are not examples: the picker has to say "Romanian" somewhere, and
 * so does a translation target.
 */
class NoLanguageExamplesTest {

    private val examples = listOf(
        "vreau", "Romanian", "rumeno", "rumano", "roumain", "Rumänisch", "românesc",
    )

    /** Keys whose whole job is to name a language. */
    private val labels = listOf(
        "language_name_", "assistant_english", "assistant_rom", "assistant_traducere",
        "assistant_translation", "screen_", "home_",
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
