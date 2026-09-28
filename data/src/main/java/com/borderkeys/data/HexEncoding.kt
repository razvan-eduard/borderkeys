// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

/** Lower-case hex, the form a SHA-256 is shown and compared in throughout this module. */
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
