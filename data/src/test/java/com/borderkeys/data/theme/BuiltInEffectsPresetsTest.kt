// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInEffectsPresetsTest {

    @Test
    fun `off is first, ahead of every named look`() {
        assertEquals("off", BuiltInEffectsPresets.ALL.first().id)
        assertEquals(setOf("off", "fire", "ice", "sand", "forest", "neon"), BuiltInEffectsPresets.ALL.map { it.id }.toSet())
    }

    @Test
    fun `applying off switches every region off and writes its own stock looks, like any preset`() {
        val off = BuiltInEffectsPresets.OFF
        val customOutline = ParticleOutlineLayer(type = ParticleEffectsSettings.OUTLINE_SPARKLE, speed = 1.7f)
        val customFill = ParticleFillLayer(type = ParticleEffectsSettings.FILL_RAINBOW, density = 1.3f)
        val before = ParticleEffectsSettings(
            keyboard = ParticleRegionSettings(enabled = true, outline = customOutline, fill = customFill),
        )
        val after = off.appliedTo(before)
        assertFalse(after.keyboard.enabled)
        assertFalse(after.radial.enabled)
        assertFalse(after.strip.enabled)
        assertFalse(after.languageRevert.enabled)
        assertFalse(after.quickActions.enabled)
        assertEquals(ParticleOutlineLayer(), after.keyboard.outline)
        assertEquals(ParticleFillLayer(), after.keyboard.fill)
        assertEquals("off", after.appliedPresetId)
        // A fresh install is exactly Off.
        assertTrue(off.matches(ParticleEffectsSettings()))
        assertTrue(off.matches(after))
    }

    @Test
    fun `any change from off is a change, a region switched on or a look altered`() {
        val off = BuiltInEffectsPresets.OFF
        val fresh = ParticleEffectsSettings()
        val oneSwitchedOn = fresh.copy(radial = fresh.radial.copy(enabled = true))
        assertFalse(off.matches(oneSwitchedOn))
        val outlineChangedWhileOff = fresh.copy(
            keyboard = fresh.keyboard.copy(outline = ParticleOutlineLayer(type = ParticleEffectsSettings.OUTLINE_COMET)),
        )
        assertFalse(off.matches(outlineChangedWhileOff))
        // The same rule for a named look: a region switched off is a change from Fire.
        val fire = BuiltInEffectsPresets.ALL.first { it.id == "fire" }
        val applied = fire.appliedTo(fresh)
        assertFalse(fire.matches(applied.copy(strip = applied.strip.copy(enabled = false))))
    }

    @Test
    fun `a named preset still applies and matches as a normal enabled look`() {
        val fire = BuiltInEffectsPresets.ALL.first { it.id == "fire" }
        val applied = fire.appliedTo(ParticleEffectsSettings())
        assertTrue(applied.keyboard.enabled)
        assertTrue(fire.matches(applied))
        assertFalse(fire.matches(ParticleEffectsSettings()))
    }

    @Test
    fun `applying a preset records it as the applied one, and a tweak afterwards keeps that record`() {
        // The picker's "which preset is this" answer must survive the first slider moved: the
        // chip stays selected and the drift notice can name what it drifted from.
        val fire = BuiltInEffectsPresets.ALL.first { it.id == "fire" }
        val applied = fire.appliedTo(ParticleEffectsSettings())
        assertEquals("fire", applied.appliedPresetId)
        val tweaked = applied.copy(keyboard = applied.keyboard.copy(outline = applied.keyboard.outline.copy(speed = 0.5f)))
        assertEquals("fire", tweaked.appliedPresetId)
        assertFalse(fire.matches(tweaked))
        // Off is a preset like any other in this respect.
        val off = BuiltInEffectsPresets.ALL.first { it.id == "off" }
        assertEquals("off", off.appliedTo(tweaked).appliedPresetId)
    }
}
