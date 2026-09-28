// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.theme.EffectEvent
import com.borderkeys.data.theme.EffectFrequency
import com.borderkeys.data.theme.EffectSetting
import com.borderkeys.data.theme.EffectsSettings
import com.borderkeys.effects.EffectStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stored effect settings against the styles and frequencies `:effects` knows. */
class EffectsSettingsTest {

    @Test
    fun `the stored default names a style that exists`() {
        assertEquals(EffectStyle.DEFAULT.name, EffectSetting.DEFAULT_STYLE)
        assertNotNull(EffectStyle.entries.firstOrNull { it.name == EffectSetting.DEFAULT_STYLE })
    }

    @Test
    fun `off is not a style name`() {
        assertTrue(EffectStyle.entries.none { it.name == EffectSetting.OFF })
        assertFalse("an empty style is off", EffectSetting().enabled)
    }

    @Test
    fun `only the swipe effect is on out of the box`() {
        val defaults = EffectsSettings()
        assertTrue(defaults.enabled)
        assertTrue(defaults.swipeAccepted.enabled)
        assertEquals(EffectStyle.DEFAULT.name, defaults.swipeAccepted.style)
        for (event in EffectEvent.entries - EffectEvent.SwipeAccepted) {
            assertFalse("$event starts silent", defaults.forEvent(event).enabled)
        }
    }

    @Test
    fun `the default frequency id names the default frequency`() {
        assertEquals(EffectFrequency.DEFAULT.id, EffectSetting.DEFAULT_FREQUENCY_ID)
        assertEquals(EffectFrequency.DEFAULT, EffectSetting().frequency)
    }

    /** A file written by a build that had a frequency this one does not reads back the default. */
    @Test
    fun `a frequency this build does not know falls back`() {
        val fromTheFuture = EffectSetting(style = EffectStyle.Pop.name, frequencyId = 9999)
        assertEquals(EffectFrequency.DEFAULT, fromTheFuture.frequency)
        assertTrue("the rest of the setting still reads", fromTheFuture.enabled)
    }

    @Test
    fun `a style this build does not know plays nothing`() {
        val fromTheFuture = EffectSetting(style = "SomethingRemoved")
        assertTrue("it still counts as chosen", fromTheFuture.enabled)
        assertTrue(EffectStyle.entries.none { it.name == fromTheFuture.style })
    }

    @Test
    fun `every event can be read back after being written`() {
        var settings = EffectsSettings()
        for (event in EffectEvent.entries) {
            val setting = EffectSetting(style = EffectStyle.Pop.name, colour = 0x11223344)
            settings = settings.withEvent(event, setting)
            assertEquals("$event round trips", setting, settings.forEvent(event))
        }
    }
}
