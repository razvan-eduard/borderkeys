// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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

    private val json = Json {
        // A future build adding a field must not make the file unreadable by this one.
        ignoreUnknownKeys = true
        // Missing keys fall back to the data class defaults, which is what makes a partial file
        // -- or one written before a field existed -- still usable.
        encodeDefaults = true
        prettyPrint = false
    }

    override val defaultValue: KeyboardTheme = KeyboardTheme()

    override suspend fun readFrom(input: InputStream): KeyboardTheme {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) {
            return defaultValue
        }
        return try {
            json.decodeFromString(KeyboardTheme.serializer(), bytes.decodeToString()).sanitised()
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

    override suspend fun writeTo(t: KeyboardTheme, output: OutputStream) {
        output.write(json.encodeToString(KeyboardTheme.serializer(), t).encodeToByteArray())
    }
}
