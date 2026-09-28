// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * The smallest edit that turns one string into another: what lies between their shared prefix
 * and shared suffix.
 */
internal object FieldRestore {

    /** Where a replace has to happen, and with what. Both zero when the strings are equal. */
    data class Span(val deleteFrom: Int, val deleteCount: Int, val insert: String)

    fun diff(current: String, target: String): Span {
        val maxPrefix = minOf(current.length, target.length)
        var prefix = 0
        while (prefix < maxPrefix && current[prefix] == target[prefix]) {
            prefix++
        }
        val maxSuffix = maxPrefix - prefix
        var suffix = 0
        while (suffix < maxSuffix &&
            current[current.length - 1 - suffix] == target[target.length - 1 - suffix]
        ) {
            suffix++
        }
        return Span(
            deleteFrom = prefix,
            deleteCount = current.length - prefix - suffix,
            insert = target.substring(prefix, target.length - suffix),
        )
    }
}
