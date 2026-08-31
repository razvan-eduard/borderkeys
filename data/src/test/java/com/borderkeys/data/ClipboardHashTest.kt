// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ClipboardHashTest {

    @Test
    fun `the same content always hashes the same`() {
        assertEquals(
            ClipboardRepository.contentHash("hunter2"),
            ClipboardRepository.contentHash("hunter2"),
        )
    }

    @Test
    fun `content that differs by one character hashes differently`() {
        assertNotEquals(
            ClipboardRepository.contentHash("hunter2"),
            ClipboardRepository.contentHash("hunter3"),
        )
    }

    @Test
    fun `inputs that collide under String hashCode do not collide here`() {
        // "Aa" and "BB" are the canonical String.hashCode collision. Under a hashCode-based
        // unique index the second one would silently never be stored -- the user copies
        // something and it does not appear in the history, with nothing anywhere saying why.
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(
            ClipboardRepository.contentHash("Aa"),
            ClipboardRepository.contentHash("BB"),
        )
    }

    @Test
    fun `unicode content hashes stably`() {
        assertEquals(
            ClipboardRepository.contentHash("mașina țării"),
            ClipboardRepository.contentHash("mașina țării"),
        )
        assertNotEquals(
            ClipboardRepository.contentHash("mașina"),
            ClipboardRepository.contentHash("masina"),
        )
    }
}
