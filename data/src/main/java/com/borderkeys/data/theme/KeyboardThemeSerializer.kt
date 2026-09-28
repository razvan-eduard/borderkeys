// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.InputStream
import java.io.OutputStream

/** Reads and writes [KeyboardTheme] as JSON for the typed DataStore. */
object KeyboardThemeSerializer : Serializer<KeyboardTheme> {

    private val json = PERSISTED_JSON

    override val defaultValue: KeyboardTheme = KeyboardTheme()

    override suspend fun readFrom(input: InputStream): KeyboardTheme {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) {
            return defaultValue
        }
        return try {
            val text = bytes.decodeToString()
            json.decodeFromString(KeyboardTheme.serializer(), text)
                .withLegacyPattern(text)
                .sanitised()
        } catch (error: SerializationException) {
            // Only a CorruptionException reaches DataStore's replace handler.
            throw CorruptionException("the keyboard theme file could not be parsed", error)
        } catch (error: IllegalArgumentException) {
            // decodeToString rejects malformed UTF-8.
            throw CorruptionException("the keyboard theme file is not valid UTF-8", error)
        }
    }

    /**
     * Reads an older file's single `backgroundPattern` key from [text] into [backgroundPatterns],
     * when that list is empty.
     */
    private fun KeyboardTheme.withLegacyPattern(text: String): KeyboardTheme {
        if (backgroundPatterns.isNotEmpty()) {
            return this
        }
        val old = runCatching {
            (json.parseToJsonElement(text) as? JsonObject)
                ?.get("backgroundPattern")
                ?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        }.getOrNull() ?: return this
        if (old == KeyboardTheme.PATTERN_NONE) {
            return this
        }
        return copy(backgroundPatterns = listOf(old))
    }

    override suspend fun writeTo(t: KeyboardTheme, output: OutputStream) {
        output.write(json.encodeToString(KeyboardTheme.serializer(), t).encodeToByteArray())
    }
}
