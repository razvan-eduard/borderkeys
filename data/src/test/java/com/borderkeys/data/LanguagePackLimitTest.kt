// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.theme.KeyboardPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/** [LanguagePackRepository.MAX_ENABLED] against `Engine::kMaxPacks`, read from engine.hpp. */
class LanguagePackLimitTest {

    @Test
    fun `the enable limit is the engine's slot count`() {
        assertEquals(NativeSource.number(NativeSource.ENGINE_HEADER, "kMaxPacks"), LanguagePackRepository.MAX_ENABLED)
    }

    @Test
    fun `the longest language tag kept is the engine's`() {
        assertEquals(
            NativeSource.number(NativeSource.PACK_FORMAT_HEADER, "kMaxLanguageTagLength"),
            KeyboardPreferences.MAX_LANGUAGE_TAG,
        )
    }
}
