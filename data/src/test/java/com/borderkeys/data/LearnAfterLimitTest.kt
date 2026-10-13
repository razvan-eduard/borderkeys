// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.theme.KeyboardPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/** [KeyboardPreferences]' learn-after stops against the engine's, read from engine.hpp. */
class LearnAfterLimitTest {

    @Test
    fun `the engine's largest learn-after setting is the last stop`() {
        assertEquals(
            KeyboardPreferences.LEARN_AFTER_USES_STEPS.last(),
            NativeSource.number(NativeSource.ENGINE_HEADER, "kMaxLearnAfterUses"),
        )
    }

    @Test
    fun `the engine's learn-after default is the settings' default`() {
        assertEquals(
            KeyboardPreferences.DEFAULT_LEARN_AFTER_USES,
            NativeSource.number(NativeSource.ENGINE_HEADER, "kDefaultLearnAfterUses"),
        )
    }
}
