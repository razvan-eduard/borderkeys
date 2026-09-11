// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

/**
 * Lower-case hex, the form a SHA-256 is shown and compared in everywhere in this module --
 * against [com.borderkeys.data.assist.KnownAssistModels]'s registry, in a language pack's own
 * record, in what a screen shows underneath a model's name. Written by hand rather than through
 * `Base64`/`String.format`: a digest is 32 bytes on the hottest path an import has, and this is
 * one pass with no allocation beyond the result.
 */
internal fun ByteArray.toHexString(): String {
    val digits = "0123456789abcdef"
    val hex = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xFF
        hex[index * 2] = digits[value ushr 4]
        hex[index * 2 + 1] = digits[value and 0x0F]
    }
    return String(hex)
}
