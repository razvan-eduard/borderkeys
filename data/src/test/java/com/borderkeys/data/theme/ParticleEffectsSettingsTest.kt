// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import androidx.datastore.core.CorruptionException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ParticleEffectsSettingsTest {

    private fun write(settings: ParticleEffectsSettings): ByteArray = ByteArrayOutputStream().also { output ->
        kotlinx.coroutines.runBlocking { ParticleEffectsSettingsSerializer.writeTo(settings, output) }
    }.toByteArray()

    private suspend fun read(bytes: ByteArray): ParticleEffectsSettings =
        ParticleEffectsSettingsSerializer.readFrom(ByteArrayInputStream(bytes))

    @Test
    fun `settings survive a round trip unchanged`() = runTest {
        val original = ParticleEffectsSettings(
            keyboard = ParticleRegionSettings(
                enabled = true,
                outline = ParticleOutlineLayer(type = ParticleEffectsSettings.OUTLINE_COMET, width = 1.5f),
                fill = ParticleFillLayer(type = ParticleEffectsSettings.FILL_FIRE, speed = 1.5f),
            ),
        )
        assertEquals(original, read(write(original)))
    }

    @Test
    fun `an empty file reads as the default rather than failing`() = runTest {
        assertEquals(ParticleEffectsSettings(), read(ByteArray(0)))
    }

    @Test
    fun `every region is off by default`() {
        val defaults = ParticleEffectsSettings()
        assertFalse(defaults.keyboard.enabled)
        assertFalse(defaults.radial.enabled)
        assertFalse(defaults.strip.enabled)
        assertFalse(defaults.languageRevert.enabled)
        assertFalse(defaults.quickActions.enabled)
        assertEquals(ParticleEffectsSettings.OUTLINE_NONE, defaults.keyboard.outline.type)
    }

    @Test
    fun `garbage and a truncated file are both reported as corruption`() {
        assertThrows(CorruptionException::class.java) {
            kotlinx.coroutines.runBlocking { read("not json".encodeToByteArray()) }
        }
        assertThrows(CorruptionException::class.java) {
            kotlinx.coroutines.runBlocking { read(write(ParticleEffectsSettings()).copyOfRange(0, 5)) }
        }
    }

    @Test
    fun `an out-of-range type falls back to the default rather than crashing`() {
        val fill = ParticleFillLayer(type = 99).sanitised()
        assertEquals(ParticleEffectsSettings.FILL_GLOW, fill.type)

        val outline = ParticleOutlineLayer(type = -1).sanitised()
        assertEquals(ParticleEffectsSettings.OUTLINE_NONE, outline.type)
    }

    @Test
    fun `speed density and width are clamped to their own range`() {
        val fill = ParticleFillLayer(speed = 99f, density = -5f).sanitised()
        assertTrue(fill.speed in ParticleEffectsSettings.MIN_SPEED..ParticleEffectsSettings.MAX_SPEED)
        assertTrue(fill.density in ParticleEffectsSettings.MIN_DENSITY..ParticleEffectsSettings.MAX_DENSITY)

        val outline = ParticleOutlineLayer(speed = -5f, density = 99f, width = 99f).sanitised()
        assertTrue(outline.speed in ParticleEffectsSettings.MIN_SPEED..ParticleEffectsSettings.MAX_SPEED)
        assertTrue(outline.density in ParticleEffectsSettings.MIN_DENSITY..ParticleEffectsSettings.MAX_DENSITY)
        assertTrue(outline.width in ParticleEffectsSettings.MIN_WIDTH..ParticleEffectsSettings.MAX_WIDTH)
    }

    @Test
    fun `sanitised reaches every region, not just the top level`() {
        val settings = ParticleEffectsSettings(
            keyboard = ParticleRegionSettings(fill = ParticleFillLayer(type = 99)),
        ).sanitised()
        assertEquals(ParticleEffectsSettings.FILL_GLOW, settings.keyboard.fill.type)
    }

    @Test
    fun `matchesPreset is true for a fresh layer and false after any one field changes`() {
        val freshFill = ParticleFillLayer(type = ParticleEffectsSettings.FILL_FIRE)
        assertTrue(freshFill.matchesPreset())
        assertFalse(freshFill.copy(speed = 1.7f).matchesPreset())
        assertFalse(freshFill.copy(primaryColor = 0xFF000000.toInt()).matchesPreset())

        val freshOutline = ParticleOutlineLayer(type = ParticleEffectsSettings.OUTLINE_PULSE)
        assertTrue(freshOutline.matchesPreset())
        assertFalse(freshOutline.copy(width = 1.7f).matchesPreset())
    }

    @Test
    fun `switching type alone preserves whatever colours and speed were already dialled in`() {
        // The picker's whole "Custom" story depends on this: changing type is a plain
        // copy(type = ...), never a reset to that type's own fresh defaults.
        val customised = ParticleFillLayer(
            type = ParticleEffectsSettings.FILL_FIRE,
            primaryColor = 0xFF123456.toInt(),
            speed = 1.8f,
        )
        val switched = customised.copy(type = ParticleEffectsSettings.FILL_GLOW)
        assertEquals(ParticleEffectsSettings.FILL_GLOW, switched.type)
        assertEquals(0xFF123456.toInt(), switched.primaryColor)
        assertEquals(1.8f, switched.speed, 0.001f)
        assertFalse(switched.matchesPreset())
    }
}
