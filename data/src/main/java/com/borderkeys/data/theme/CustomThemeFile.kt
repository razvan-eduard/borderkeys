// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** One theme as its own small file, apart from the backup format. */
object CustomThemeFile {

    const val MIME_TYPE = "application/json"

    /** [PERSISTED_JSON], pretty-printed. */
    private val json = Json(PERSISTED_JSON) { prettyPrint = true }

    @Serializable
    private data class Payload(val name: String, val theme: KeyboardTheme)

    fun write(name: String, theme: KeyboardTheme): String =
        json.encodeToString(Payload.serializer(), Payload(name, theme))

    /** The name and theme the file holds, or null for anything this build cannot read whole. */
    fun read(text: String): Pair<String, KeyboardTheme>? = try {
        val payload = json.decodeFromString(Payload.serializer(), text)
        payload.name to payload.theme.sanitised()
    } catch (error: SerializationException) {
        null
    } catch (error: IllegalArgumentException) {
        null
    }
}
