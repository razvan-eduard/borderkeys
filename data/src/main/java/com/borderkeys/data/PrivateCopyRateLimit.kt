// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

/**
 * How often the text-selection menu's private copy may be taken: [PER_PACKAGE] times from one
 * app and [TOTAL] times in all within [WINDOW_MILLIS]. The history is the recent copies as
 * `time|package` lines, kept by the caller.
 */
object PrivateCopyRateLimit {

    const val PER_PACKAGE = 10
    const val TOTAL = 30
    const val WINDOW_MILLIS = 60_000L

    class Decision(val allowed: Boolean, val history: List<String>)

    /** Whether a copy from [source] at [now] is allowed, and the history with it recorded if so. */
    fun decide(now: Long, source: String?, history: List<String>): Decision {
        val recent = history.mapNotNull { line ->
            val at = line.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
            if (now - at in 0 until WINDOW_MILLIS) line else null
        }
        val fromSource = recent.count { it.substringAfter('|', "") == source.orEmpty() }
        if (recent.size >= TOTAL || fromSource >= PER_PACKAGE) {
            return Decision(false, recent)
        }
        return Decision(true, recent + "$now|${source.orEmpty()}")
    }
}
