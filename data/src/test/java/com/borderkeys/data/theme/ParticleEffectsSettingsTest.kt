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
    fun `the applied preset survives a round trip, and a file from before it reads as none`() = runTest {
        val original = ParticleEffectsSettings(appliedPresetId = "fire")
        assertEquals("fire", read(write(original)).appliedPresetId)
        val before = write(ParticleEffectsSettings()).decodeToString().replace(""","appliedPresetId":""""", "")
        assertFalse(before.contains("appliedPresetId"))
        assertEquals("", read(before.encodeToByteArray()).appliedPresetId)
        assertEquals("x".repeat(ParticleEffectsSettings.MAX_PRESET_ID_LENGTH),
            ParticleEffectsSettings(appliedPresetId = "x".repeat(200)).sanitised().appliedPresetId)
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
    fun `Fill None is a real in-range value, not something sanitised clamps away`() {
        val fill = ParticleFillLayer(type = ParticleEffectsSettings.FILL_NONE).sanitised()
        assertEquals(ParticleEffectsSettings.FILL_NONE, fill.type)
    }

    @Test
    fun `Outline Fire and Wind are real in-range values, not something sanitised clamps away`() {
        val fire = ParticleOutlineLayer(type = ParticleEffectsSettings.OUTLINE_FIRE).sanitised()
        assertEquals(ParticleEffectsSettings.OUTLINE_FIRE, fire.type)

        val wind = ParticleOutlineLayer(type = ParticleEffectsSettings.OUTLINE_WIND).sanitised()
        assertEquals(ParticleEffectsSettings.OUTLINE_WIND, wind.type)
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

    @Test
    fun `every fill and outline preset has its own distinct default colours`() {
        val fillColours = listOf(
            ParticleEffectsSettings.FILL_FIRE,
            ParticleEffectsSettings.FILL_GLOW,
            ParticleEffectsSettings.FILL_WAVES,
            ParticleEffectsSettings.FILL_RAINBOW,
            ParticleEffectsSettings.FILL_NEON,
        ).map { ParticleFillLayer(type = it).primaryColor }
        assertEquals("fill presets should not share a primary colour", fillColours.size, fillColours.toSet().size)

        val outlineColours = listOf(
            ParticleEffectsSettings.OUTLINE_COMET,
            ParticleEffectsSettings.OUTLINE_PULSE,
            ParticleEffectsSettings.OUTLINE_SPARKLE,
        ).map { ParticleOutlineLayer(type = it).primaryColor }
        assertEquals(
            "outline presets should not share a primary colour",
            outlineColours.size,
            outlineColours.toSet().size,
        )
    }

    @Test
    fun `withPresetType on an untouched layer selects the new preset cleanly, not as Custom`() {
        // Nothing customised yet, so speed/density are still at their own shared defaults --
        // this is the one case where the result can honestly still match a fresh instance of
        // the new type, colours included.
        val fresh = ParticleFillLayer(type = ParticleEffectsSettings.FILL_GLOW)
        val switched = fresh.withPresetType(ParticleEffectsSettings.FILL_FIRE)
        assertEquals(ParticleEffectsSettings.FILL_FIRE, switched.type)
        assertEquals(ParticleFillLayer(type = ParticleEffectsSettings.FILL_FIRE).primaryColor, switched.primaryColor)
        assertTrue(switched.matchesPreset())
    }

    @Test
    fun `withPresetType adopts the new preset's own colours but keeps speed and density`() {
        val customised = ParticleFillLayer(
            type = ParticleEffectsSettings.FILL_FIRE,
            primaryColor = 0xFF123456.toInt(),
            speed = 1.8f,
            density = 1.6f,
        )
        val switched = customised.withPresetType(ParticleEffectsSettings.FILL_WAVES)
        assertEquals(ParticleEffectsSettings.FILL_WAVES, switched.type)
        assertEquals(ParticleFillLayer(type = ParticleEffectsSettings.FILL_WAVES).primaryColor, switched.primaryColor)
        assertEquals(1.8f, switched.speed, 0.001f)
        assertEquals(1.6f, switched.density, 0.001f)
        // Still reads as Custom -- correctly: an already-customised speed/density surviving the
        // switch is exactly why, the same as it already would for the preset left behind.
        assertFalse(switched.matchesPreset())
    }

    @Test
    fun `withPresetType on Outline also keeps width`() {
        val customised = ParticleOutlineLayer(
            type = ParticleEffectsSettings.OUTLINE_COMET,
            width = 1.9f,
        )
        val switched = customised.withPresetType(ParticleEffectsSettings.OUTLINE_SPARKLE)
        assertEquals(ParticleEffectsSettings.OUTLINE_SPARKLE, switched.type)
        assertEquals(1.9f, switched.width, 0.001f)
        assertFalse(switched.matchesPreset())
    }
}
