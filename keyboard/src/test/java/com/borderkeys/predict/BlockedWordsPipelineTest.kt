// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Blocked words through [Pipeline], against the shipped packs. */
class BlockedWordsPipelineTest {

    @Test
    fun `a blocked spelling gives way to the other spelling of its key`() {
        Pipeline.require()
        val romanian = Pipeline.open("ro-RO")
        try {
            romanian.block("maine")
            val strip = romanian.strip("maine")
            assertTrue(strip.toString(), "mâine" in strip)
            assertTrue(strip.toString(), strip.none { it.equals("maine", ignoreCase = true) })
            assertEquals("mâine", romanian.commit("maine").committed)
        } finally {
            romanian.close()
        }
    }

    @Test
    fun `a blocked word is absent from every language, whatever its case`() {
        Pipeline.require()
        val both = Pipeline.open("en-US", "ro-RO")
        try {
            both.block("Maine")
            val strip = both.strip("maine")
            assertTrue(strip.toString(), "mâine" in strip)
            assertTrue(strip.toString(), strip.none { it.equals("maine", ignoreCase = true) })
            assertEquals("mâine", both.commit("maine").committed)
        } finally {
            both.close()
        }
    }

    @Test
    fun `an empty list unblocks everything`() {
        Pipeline.require()
        val romanian = Pipeline.open("ro-RO")
        try {
            val before = romanian.strip("maine")
            romanian.block("maine")
            romanian.block()
            assertEquals(before, romanian.strip("maine"))
        } finally {
            romanian.close()
        }
    }
}
