// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import java.io.InputStream
import java.io.OutputStream

/** Reads and writes [CustomThemeLibrary] as JSON for the typed DataStore, the same shape as
 *  [KeyboardThemeSerializer] and for the same reasons. */
object CustomThemeLibrarySerializer : Serializer<CustomThemeLibrary> {

    private val json = PERSISTED_JSON

    override val defaultValue: CustomThemeLibrary = CustomThemeLibrary()

    override suspend fun readFrom(input: InputStream): CustomThemeLibrary {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) {
            return defaultValue
        }
        return try {
            json.decodeFromString(CustomThemeLibrary.serializer(), bytes.decodeToString()).sanitised()
        } catch (error: SerializationException) {
            throw CorruptionException("the custom theme library could not be parsed", error)
        } catch (error: IllegalArgumentException) {
            throw CorruptionException("the custom theme library is not valid UTF-8", error)
        }
    }

    override suspend fun writeTo(t: CustomThemeLibrary, output: OutputStream) {
        output.write(json.encodeToString(CustomThemeLibrary.serializer(), t).encodeToByteArray())
    }
}
