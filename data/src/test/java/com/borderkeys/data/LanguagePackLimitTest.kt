// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** [LanguagePackRepository.MAX_ENABLED] against `Engine::kMaxPacks`, read from engine.hpp. */
class LanguagePackLimitTest {

    @Test
    fun `the enable limit is the engine's slot count`() {
        var directory: File? = File("").absoluteFile
        while (directory != null && !File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
        }
        assertNotNull("repository root not found above ${File("").absolutePath}", directory)
        val header = File(directory, "keyboard/src/main/cpp/engine.hpp").readText()
        val slots = Regex("""kMaxPacks\s*=\s*(\d+)""").find(header)?.groupValues?.get(1)?.toInt()
        assertNotNull("kMaxPacks not found in engine.hpp", slots)
        assertEquals(slots, LanguagePackRepository.MAX_ENABLED)
    }
}
