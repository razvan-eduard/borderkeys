// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import java.io.InputStream
import java.io.OutputStream

/** Reads and writes [ParticleEffectsSettings] as JSON for the typed DataStore. */
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
