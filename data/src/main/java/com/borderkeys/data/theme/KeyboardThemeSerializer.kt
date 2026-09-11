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

/**
 * Reads and writes [KeyboardTheme] as JSON for the typed DataStore.
 *
 * Typed rather than preferences-backed. A string-keyed bag would turn every field rename into a
 * silent default at runtime, and there is no compiler anywhere in that path; here a rename is a
 * compile error, and `ignoreUnknownKeys` means a file written by a newer build is read by an
 * older one instead of throwing.
 */
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
            // Translated rather than propagated. DataStore only recognises CorruptionException,
            // and only a CorruptionException reaches the replace handler that rewrites the file
            // with a default -- anything else surfaces on the caller's collector, on the UI
            // thread, as a crash while showing a keyboard.
            throw CorruptionException("the keyboard theme file could not be parsed", error)
        } catch (error: IllegalArgumentException) {
            // decodeToString rejects malformed UTF-8, which is what a truncated or partially
            // overwritten file looks like.
            throw CorruptionException("the keyboard theme file is not valid UTF-8", error)
        }
    }

    /**
     * Carries a theme written before patterns could layer.
     *
     * The single `backgroundPattern` became a list. `ignoreUnknownKeys` would drop the old key
     * without a word, so the one that was chosen is read out of the raw text and becomes the
     * only member of the new list. Read from the JSON rather than kept as a field on the class,
     * so nothing writes the dead key back out.
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
