// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The span [FieldRestore.diff] hands back is what an undo/redo step is allowed to touch. A wide
 * span here is a paragraph rewritten for a one-word edit; these are the shapes that matter.
 */
class FieldRestoreTest {

    @Test
    fun `identical strings touch nothing`() {
        // The whole string is the shared prefix, so deleteFrom lands at its end -- what matters
        // is that nothing is deleted and nothing is inserted, not exactly where the no-op sits.
        val span = FieldRestore.diff("hello world", "hello world")
        assertEquals(0, span.deleteCount)
        assertEquals("", span.insert)
    }

    @Test
    fun `a pure append only inserts at the end`() {
        val span = FieldRestore.diff("hello", "hello world")
        assertEquals(5, span.deleteFrom)
        assertEquals(0, span.deleteCount)
        assertEquals(" world", span.insert)
    }

    @Test
    fun `a pure prepend only inserts at the start`() {
        val span = FieldRestore.diff("world", "hello world")
        assertEquals(0, span.deleteFrom)
        assertEquals(0, span.deleteCount)
        assertEquals("hello ", span.insert)
    }

    @Test
    fun `a change in the middle leaves both edges alone`() {
        val span = FieldRestore.diff("the cat sat on the mat", "the dog sat on the mat")
        assertEquals("the ", "the cat sat on the mat".substring(0, span.deleteFrom))
        assertEquals("cat", "the cat sat on the mat".substring(span.deleteFrom, span.deleteFrom + span.deleteCount))
        assertEquals("dog", span.insert)
    }

    @Test
    fun `no shared prefix or suffix replaces the whole thing`() {
        val span = FieldRestore.diff("abc", "xyz")
        assertEquals(0, span.deleteFrom)
        assertEquals(3, span.deleteCount)
        assertEquals("xyz", span.insert)
    }

    @Test
    fun `restoring an empty field only inserts`() {
        val span = FieldRestore.diff("", "hello")
        assertEquals(0, span.deleteFrom)
        assertEquals(0, span.deleteCount)
        assertEquals("hello", span.insert)
    }

    @Test
    fun `clearing a field only deletes`() {
        val span = FieldRestore.diff("hello", "")
        assertEquals(0, span.deleteFrom)
        assertEquals(5, span.deleteCount)
        assertEquals("", span.insert)
    }

    @Test
    fun `a repeated character does not let prefix and suffix overlap`() {
        // "aa" -> "aaa": scanned independently, the prefix and suffix passes could each claim
        // more of the string than is actually left once the other's claim is subtracted, which
        // would produce a negative delete count. maxPrefix/maxSuffix are what cap them apart.
        val span = FieldRestore.diff("aa", "aaa")
        assertEquals(0, span.deleteCount)
        assertEquals("a", span.insert)
    }
}
