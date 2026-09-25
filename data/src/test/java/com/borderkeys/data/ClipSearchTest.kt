// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.ClipEntry
import org.junit.Assert.assertEquals
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

    @Test
    fun `a blank query keeps everything as it was`() {
        assertEquals(entries, ClipSearch.filter(entries, "  "))
        assertEquals(entries, ClipSearch.rank(entries, ""))
        assertEquals(0, ClipSearch.count(entries, ""))
    }

    @Test
    fun `filter keeps the entries containing the query, case folded`() {
        assertEquals(listOf(1L, 4L), ClipSearch.filter(entries, "INVOICE").map { it.id })
        assertEquals(2, ClipSearch.count(entries, "invoice"))
    }

    @Test
    fun `accents fold on both sides`() {
        assertEquals(listOf(3L), ClipSearch.filter(entries, "resume").map { it.id })
        assertEquals(listOf(3L), ClipSearch.filter(entries, "RÉSUMÉ").map { it.id })
    }

    @Test
    fun `rank moves the matches first and keeps every entry`() {
        assertEquals(listOf(1L, 4L, 2L, 3L, 9L), ClipSearch.rank(entries, "invoice").map { it.id })
    }

    @Test
    fun `an image never matches`() {
        assertEquals(emptyList<ClipEntry>(), ClipSearch.filter(entries, "content"))
    }
}
