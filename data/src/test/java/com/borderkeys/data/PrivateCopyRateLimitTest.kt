// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateCopyRateLimitTest {

    @Test
    fun `ten from one app a minute, then no more from it`() {
        var history = emptyList<String>()
        for (i in 0 until PrivateCopyRateLimit.PER_PACKAGE) {
            val decision = PrivateCopyRateLimit.decide(1_000L + i, "com.a", history)
            assertTrue("copy $i", decision.allowed)
            history = decision.history
        }
        assertFalse(PrivateCopyRateLimit.decide(2_000L, "com.a", history).allowed)
        assertTrue(PrivateCopyRateLimit.decide(2_000L, "com.b", history).allowed)
    }

    @Test
    fun `thirty in all a minute, whatever the apps`() {
        var history = emptyList<String>()
        for (i in 0 until PrivateCopyRateLimit.TOTAL) {
            history = PrivateCopyRateLimit.decide(1_000L + i, "com.app$i", history).history
        }
        assertEquals(PrivateCopyRateLimit.TOTAL, history.size)
        assertFalse(PrivateCopyRateLimit.decide(2_000L, "com.new", history).allowed)
    }

    @Test
    fun `the window slides, and what fell out of it is forgotten`() {
        var history = emptyList<String>()
        for (i in 0 until PrivateCopyRateLimit.PER_PACKAGE) {
            history = PrivateCopyRateLimit.decide(1_000L + i, "com.a", history).history
        }
        val later = PrivateCopyRateLimit.decide(1_000L + PrivateCopyRateLimit.WINDOW_MILLIS + 10, "com.a", history)
        assertTrue(later.allowed)
        assertEquals(1, later.history.size)
    }

    @Test
    fun `a line that is not a record is dropped, and no source counts as one app`() {
        val decision = PrivateCopyRateLimit.decide(1_000L, null, listOf("garbage", "900|"))
        assertTrue(decision.allowed)
        assertEquals(listOf("900|", "1000|"), decision.history)
    }
}
