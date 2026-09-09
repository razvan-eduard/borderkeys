// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * The smallest edit that turns one string into another.
 *
 * Its own object, tested without an editor or an `InputConnection`, for the same reason
 * [AutoCorrection] and [AutoShift] are: undo/redo on the real field could rewrite a paragraph
 * nobody touched, and the policy for not doing that is worth being able to check on its own.
 *
 * The field belongs to whatever application is behind the keyboard, not to us the way the draft
 * box's own buffer does. Replacing all of it on every step back or forward would be a bigger
 * edit than the user asked for, and one visible as a flicker or a fight with the app's own
 * cursor and spellcheck state. Trimming to the shared prefix and shared suffix and touching only
 * what is left between them is the same principle [AutoCorrection]'s revert already applies at
 * word scale: never touch more than actually changed.
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
