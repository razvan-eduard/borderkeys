// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.json.Json

/**
 * The [Json] configuration of every stored file: unknown keys ignored, defaults encoded, no
 * pretty-printing.
 */
internal val PERSISTED_JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}
