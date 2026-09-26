// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.ClipEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipSearchTest {

    private fun text(id: Long, content: String) =
        ClipEntry(id = id, content = content, createdAt = id, contentHash = id)

    private val image = ClipEntry(
        id = 9, content = "content://x/1", createdAt = 9, contentHash = 9,
        uri = "content://x/1", mimeType = "image/png",
    )

    private val entries = listOf(
        text(1, "Invoice 2026-09"),
        text(2, "hunter2"),
        text(3, "Résumé attached"),
        image,
        text(4, "the invoice is paid"),
    )

    private fun ids(found: List<ClipEntry>) = found.map { it.id }

    private fun query(text: String, mode: ClipSearch.Mode) = ClipSearch.Query.of(text, mode)

    @Test
    fun `a blank query keeps everything as it was`() {
        assertEquals(entries, ClipSearch.filter(entries, "  "))
        assertEquals(entries, ClipSearch.rank(entries, ""))
        assertEquals(0, ClipSearch.count(entries, ""))
        for (mode in ClipSearch.Mode.entries) {
            assertTrue(mode.name, query("  ", mode).empty)
            assertEquals(mode.name, entries, ClipSearch.filter(entries, query("", mode)))
        }
    }

    @Test
    fun `filter keeps the entries containing the query, case folded`() {
        assertEquals(listOf(1L, 4L), ids(ClipSearch.filter(entries, "INVOICE")))
        assertEquals(2, ClipSearch.count(entries, "invoice"))
    }

    @Test
    fun `accents fold on both sides`() {
        assertEquals(listOf(3L), ids(ClipSearch.filter(entries, "resume")))
        assertEquals(listOf(3L), ids(ClipSearch.filter(entries, "RÉSUMÉ")))
    }

    @Test
    fun `rank moves the matches first and keeps every entry`() {
        assertEquals(listOf(1L, 4L, 2L, 3L, 9L), ids(ClipSearch.rank(entries, "invoice")))
    }

    @Test
    fun `an image never matches`() {
        assertEquals(emptyList<ClipEntry>(), ClipSearch.filter(entries, "content"))
        assertEquals(emptyList<ClipEntry>(), ClipSearch.filter(entries, query("content*", ClipSearch.Mode.WILDCARDS)))
        assertEquals(emptyList<ClipEntry>(), ClipSearch.filter(entries, query("content.*", ClipSearch.Mode.REGEX)))
    }

    @Test
    fun `wildcards stand for any run of characters and for one character`() {
        assertEquals(listOf(4L), ids(ClipSearch.filter(entries, query("inv*paid", ClipSearch.Mode.WILDCARDS))))
        assertEquals(listOf(2L), ids(ClipSearch.filter(entries, query("?unter2", ClipSearch.Mode.WILDCARDS))))
        assertEquals(listOf(1L, 4L), ids(ClipSearch.filter(entries, query("INVOICE", ClipSearch.Mode.WILDCARDS))))
        assertEquals(listOf(3L), ids(ClipSearch.filter(entries, query("r?sum?", ClipSearch.Mode.WILDCARDS))))
    }

    @Test
    fun `in wildcard mode every other character is itself`() {
        assertEquals(listOf(1L), ids(ClipSearch.filter(entries, query("2026-09", ClipSearch.Mode.WILDCARDS))))
        assertEquals(emptyList<Long>(), ids(ClipSearch.filter(entries, query("20.6-09", ClipSearch.Mode.WILDCARDS))))
        assertEquals(emptyList<Long>(), ids(ClipSearch.filter(entries, query("(invoice)", ClipSearch.Mode.WILDCARDS))))
    }

    @Test
    fun `a regular expression matches anywhere, ignoring case and accents`() {
        assertEquals(listOf(1L), ids(ClipSearch.filter(entries, query("""\d{4}-\d{2}""", ClipSearch.Mode.REGEX))))
        assertEquals(listOf(1L, 4L), ids(ClipSearch.filter(entries, query("^(the )?invoice", ClipSearch.Mode.REGEX))))
        assertEquals(listOf(3L), ids(ClipSearch.filter(entries, query("resume", ClipSearch.Mode.REGEX))))
        assertEquals(2, ClipSearch.count(entries, query("invoice", ClipSearch.Mode.REGEX)))
    }

    @Test
    fun `an expression that cannot be read is flagged and matches nothing`() {
        val broken = query("(invoice", ClipSearch.Mode.REGEX)
        assertTrue(broken.invalid)
        assertFalse(broken.empty)
        assertEquals(emptyList<ClipEntry>(), ClipSearch.filter(entries, broken))
        assertEquals(0, ClipSearch.count(entries, broken))
        assertFalse(query("(invoice", ClipSearch.Mode.WILDCARDS).invalid)
    }
}
