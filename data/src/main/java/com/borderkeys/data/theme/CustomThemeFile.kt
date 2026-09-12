// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * One theme, as its own small file -- independent of the full backup format in
 * `com.borderkeys.data.backup`, because a theme is a handful of colours someone might want to
 * hand a friend, and a friend does not want the dictionary and clipboard history that come
 * along with a real backup to get that.
 */
object CustomThemeFile {

    const val MIME_TYPE = "application/json"

    /** Pretty-printed, unlike [PERSISTED_JSON]: this file is written to be read by a person, not
     *  only by the application that wrote it -- the whole reason it exists as its own format. */
    private val json = Json(PERSISTED_JSON) { prettyPrint = true }

    @Serializable
    private data class Payload(val name: String, val theme: KeyboardTheme)

    fun write(name: String, theme: KeyboardTheme): String =
        json.encodeToString(Payload.serializer(), Payload(name, theme))

    /** The name and theme the file names, or null for anything this build cannot make sense of --
     *  never a partial result, since a theme built from half a file is not the theme anyone
     *  exported. */
    fun read(text: String): Pair<String, KeyboardTheme>? = try {
        val payload = json.decodeFromString(Payload.serializer(), text)
        payload.name to payload.theme.sanitised()
    } catch (error: SerializationException) {
        null
    } catch (error: IllegalArgumentException) {
        null
    }
}
