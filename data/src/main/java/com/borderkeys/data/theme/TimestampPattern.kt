// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The pattern the date-and-time quick action writes with, in the letters `java.time` reads:
 * `yyyy` the year, `MM` the month, `dd` the day, `HH:mm` the time, `EEEE` and `MMMM` the
 * weekday and the month by name, text in single quotes as it is.
 */
object TimestampPattern {

    const val DATE = "yyyy-MM-dd"
    const val TIME = "HH:mm"
    const val DATE_AND_TIME = "yyyy-MM-dd HH:mm"
    const val IN_WORDS = "EEEE d MMMM yyyy"
    const val ISO_8601 = "yyyy-MM-dd'T'HH:mm:ssXXX"

    const val DEFAULT = DATE_AND_TIME

    /** Longer than any pattern needs, and short enough to keep a stored file honest. */
    const val MAX_LENGTH = 64

    /** Whether [pattern] can write a moment at all. */
    fun isValid(pattern: String): Boolean =
        pattern.isNotBlank() && pattern.length <= MAX_LENGTH &&
            runCatching { DateTimeFormatter.ofPattern(pattern, Locale.ROOT).format(SAMPLE) }.isSuccess

    /** [pattern] when it is valid, [DEFAULT] otherwise. */
    fun sanitised(pattern: String): String = if (isValid(pattern)) pattern else DEFAULT

    /** [moment] written with [pattern] for [locale]; a pattern that cannot be read writes [DEFAULT]. */
    fun format(pattern: String, moment: ZonedDateTime, locale: Locale): String =
        DateTimeFormatter.ofPattern(sanitised(pattern), locale).format(moment)

    private val SAMPLE: ZonedDateTime = ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
}
