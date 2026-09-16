// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import java.io.InputStream
import java.io.OutputStream

/**
 * Reads and writes [ParticleEffectsSettings] as JSON for the typed DataStore -- the same shape
 * as [KeyboardThemeSerializer], minus a legacy-migration step: this domain did not exist before
 * this file, so there is no older shape to carry forward. An install upgrading from the first
 * particle-effects release starts fresh here (every region off, every layer at its type's own
 * default) rather than migrating the old flat `KeyboardPreferences`/`KeyboardTheme` fields --
 * see this feature's own plan notes for why that reset is the accepted trade-off.
 */
object ParticleEffectsSettingsSerializer : Serializer<ParticleEffectsSettings> {

    private val json = PERSISTED_JSON

    override val defaultValue: ParticleEffectsSettings = ParticleEffectsSettings()

    override suspend fun readFrom(input: InputStream): ParticleEffectsSettings {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) {
            return defaultValue
        }
        return try {
            val text = bytes.decodeToString()
            json.decodeFromString(ParticleEffectsSettings.serializer(), text).sanitised()
        } catch (error: SerializationException) {
            throw CorruptionException("the particle effects file could not be parsed", error)
        } catch (error: IllegalArgumentException) {
            throw CorruptionException("the particle effects file is not valid UTF-8", error)
        }
    }

    override suspend fun writeTo(t: ParticleEffectsSettings, output: OutputStream) {
        output.write(json.encodeToString(ParticleEffectsSettings.serializer(), t).encodeToByteArray())
    }
}
