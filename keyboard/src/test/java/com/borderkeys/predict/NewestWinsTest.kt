// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewestWinsTest {

    @Test
    fun `a request is the newest until the next one is issued`() {
        val requests = NewestWins()
        val first = requests.issue()
        assertTrue(requests.isNewest(first))
        val second = requests.issue()
        assertFalse(requests.isNewest(first))
        assertTrue(requests.isNewest(second))
    }

    @Test
    fun `a cancel makes every request issued so far stale`() {
        val requests = NewestWins()
        val first = requests.issue()
        requests.cancel()
        assertFalse(requests.isNewest(first))
        assertTrue(requests.isNewest(requests.issue()))
    }

    @Test
    fun `numbers keep increasing across cancels`() {
        val requests = NewestWins()
        val seen = ArrayList<Int>()
        repeat(4) {
            seen += requests.issue()
            requests.cancel()
        }
        assertTrue(seen.zipWithNext().all { (earlier, later) -> later > earlier })
    }
}
