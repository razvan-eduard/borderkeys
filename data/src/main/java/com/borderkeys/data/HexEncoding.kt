// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

/** Lower-case hex, the form a SHA-256 is shown and compared in throughout this module. */
internal fun ByteArray.toHexString(): String {
    val digits = "0123456789abcdef"
    val hex = CharArray(size * HEX_DIGITS_PER_BYTE)
    for (index in indices) {
        val value = this[index].toInt() and BYTE_MASK
        hex[index * HEX_DIGITS_PER_BYTE] = digits[value ushr NIBBLE_BITS]
        hex[index * HEX_DIGITS_PER_BYTE + 1] = digits[value and NIBBLE_MASK]
    }
    return String(hex)
}

/** A byte's hex digits, the bits of each, and the masks that keep a byte and a nibble. */
private const val HEX_DIGITS_PER_BYTE = 2
private const val NIBBLE_BITS = 4
private const val BYTE_MASK = 0xFF
private const val NIBBLE_MASK = 0x0F
