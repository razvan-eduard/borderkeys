// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import java.io.InputStream
import java.io.OutputStream

/** Reads and writes [CustomEffectsPresetLibrary] as JSON for the typed DataStore, the same shape
 *  as [CustomThemeLibrarySerializer] and for the same reasons. */
object CustomEffectsPresetLibrarySerializer : Serializer<CustomEffectsPresetLibrary> {

    private val json = PERSISTED_JSON

    override val defaultValue: CustomEffectsPresetLibrary = CustomEffectsPresetLibrary()

    override suspend fun readFrom(input: InputStream): CustomEffectsPresetLibrary {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) {
            return defaultValue
        }
        return try {
            json.decodeFromString(CustomEffectsPresetLibrary.serializer(), bytes.decodeToString()).sanitised()
        } catch (error: SerializationException) {
            throw CorruptionException("the custom effects preset library could not be parsed", error)
        } catch (error: IllegalArgumentException) {
            throw CorruptionException("the custom effects preset library is not valid UTF-8", error)
        }
    }

    override suspend fun writeTo(t: CustomEffectsPresetLibrary, output: OutputStream) {
        output.write(json.encodeToString(CustomEffectsPresetLibrary.serializer(), t).encodeToByteArray())
    }
}
