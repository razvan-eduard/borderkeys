// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimestampPatternTest {

    private val moment = ZonedDateTime.of(2026, 9, 26, 17, 5, 9, 0, ZoneOffset.ofHours(3))

    @Test
    fun `every preset is a pattern that writes`() {
        for (pattern in listOf(
            TimestampPattern.DATE,
            TimestampPattern.TIME,
            TimestampPattern.DATE_AND_TIME,
            TimestampPattern.IN_WORDS,
            TimestampPattern.ISO_8601,
        )) {
            assertTrue(pattern, TimestampPattern.isValid(pattern))
        }
        assertEquals("2026-09-26", TimestampPattern.format(TimestampPattern.DATE, moment, Locale.ROOT))
        assertEquals("17:05", TimestampPattern.format(TimestampPattern.TIME, moment, Locale.ROOT))
        assertEquals("2026-09-26 17:05", TimestampPattern.format(TimestampPattern.DEFAULT, moment, Locale.ROOT))
        assertEquals("2026-09-26T17:05:09+03:00", TimestampPattern.format(TimestampPattern.ISO_8601, moment, Locale.ROOT))
    }

    @Test
    fun `names come out in the language asked for`() {
        assertEquals("Saturday 26 September 2026", TimestampPattern.format(TimestampPattern.IN_WORDS, moment, Locale.ENGLISH))
        assertEquals("Samstag 26 September 2026", TimestampPattern.format(TimestampPattern.IN_WORDS, moment, Locale.GERMAN))
    }

    @Test
    fun `quoted text is written as it is`() {
        assertEquals("at 17:05 on the 26th", TimestampPattern.format("'at' HH:mm 'on the' d'th'", moment, Locale.ROOT))
    }

    @Test
    fun `a pattern that cannot be read is not valid and writes the default`() {
        for (pattern in listOf("", "   ", "'", "yyyy-MM-dd {", "b".repeat(TimestampPattern.MAX_LENGTH + 1))) {
            assertFalse(pattern, TimestampPattern.isValid(pattern))
            assertEquals(TimestampPattern.DEFAULT, TimestampPattern.sanitised(pattern))
        }
        assertEquals("2026-09-26 17:05", TimestampPattern.format("'", moment, Locale.ROOT))
    }

    @Test
    fun `a stored pattern that cannot be read is replaced when the preferences are sanitised`() {
        assertEquals(TimestampPattern.DEFAULT, KeyboardPreferences(timestampPattern = "{").sanitised().timestampPattern)
        assertEquals("HH:mm", KeyboardPreferences(timestampPattern = "HH:mm").sanitised().timestampPattern)
    }
}
