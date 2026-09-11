// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.json.Json

/**
 * The one [Json] configuration both DataStore serializers -- [KeyboardPreferencesSerializer],
 * [KeyboardThemeSerializer] -- need, and need identically: `ignoreUnknownKeys` so a file a future
 * build adds a field to still reads on this one, `encodeDefaults` so a partial or pre-field file
 * still reads as the data class's own defaults for what it is missing, and no pretty-printing --
 * this is read by the application that wrote it, never by a person.
 */
internal val PERSISTED_JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}
