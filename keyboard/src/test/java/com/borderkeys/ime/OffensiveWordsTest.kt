// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class OffensiveWordsTest {

    @Test
    fun `comments and blank lines are skipped and every word is folded`() {
        val parsed = OffensiveWords.parse("# a header\n\nFirst\n  Șecond \n# comment\nfirst\n")
        assertEquals(setOf("first", "second"), parsed)
    }

    @Test
    fun `merging keeps one copy of a word two languages share`() {
        val merged = OffensiveWords.merge(listOf(setOf("a", "b"), setOf("b", "c"), emptySet()))
        assertEquals(setOf("a", "b", "c"), merged)
    }

    /**
     * The shipped lists, read from the repository: one per bundled language, single words,
     * written in lower case, none listed twice once folded. A word with a space in it could
     * never match a candidate, and a duplicate is a line somebody will edit in one place only.
     */
    @Test
    fun `every bundled language ships a well-formed list`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val directory = File(root, "keyboard/src/main/assets/offensive")
        for (tag in listOf("en-US", "ro-RO", "de-DE", "fr-FR", "es-ES", "it-IT")) {
            val file = File(directory, "$tag.txt")
            assertTrue("$tag has a list", file.isFile)
            val text = file.readText()
            // REUSE-IgnoreStart -- the literal below is the tag this test looks for, not this
            // file's own licence, and `reuse lint` reads every occurrence in the tree alike.
            assertTrue("$tag carries its licence header", text.startsWith("# SPDX-License-Identifier:"))
            // REUSE-IgnoreEnd
            val entries = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            assertTrue("$tag lists at least forty words", entries.size >= 40)
            for (entry in entries) {
                assertFalse("$tag: '$entry' is a single word", entry.any { it.isWhitespace() })
                assertEquals("$tag: '$entry' is in lower case", entry.lowercase(Locale.ROOT), entry)
            }
            assertEquals("$tag lists no word twice once folded", entries.size, OffensiveWords.parse(text).size)
        }
    }
}
