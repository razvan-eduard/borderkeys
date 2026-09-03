// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The version line, where the feature can destroy work.
 *
 * Every one of these is a rule a user was promised: the original is always reachable, an edit
 * never costs you a version, and running the model from the middle costs you exactly the
 * versions that came after it and no others.
 */
class ComposerTest {

    private fun runFrom(composer: Composer, text: String, result: String) {
        composer.captureBeforeRun(text)
        composer.addResult(result)
    }

    @Test
    fun `nothing exists until the model has been asked`() {
        val composer = Composer()
        assertTrue(composer.isEmpty())
        assertFalse("a line of one node is not a line", composer.hasHistory)
        assertNull(composer.current())
        assertNull("there is nowhere to flip to", composer.flip())
    }

    @Test
    fun `the first run keeps what was there as the original`() {
        val composer = Composer()
        runFrom(composer, "ce faci", "Ce faci?")

        assertEquals(2, composer.size)
        assertEquals("ce faci", composer.versionAt(0))
        assertEquals("Ce faci?", composer.current())
        assertTrue(composer.hasHistory)
        assertFalse(composer.atOriginal)
    }

    @Test
    fun `walking back and forward does not change anything`() {
        val composer = Composer()
        runFrom(composer, "one", "two")
        runFrom(composer, "two", "three")

        assertEquals("three", composer.current())
        assertEquals("two", composer.back())
        assertEquals("one", composer.back())
        assertNull("there is nothing before the original", composer.back())
        assertEquals("two", composer.forward())
        assertEquals("three", composer.forward())
        assertNull(composer.forward())
        assertEquals(3, composer.size)
    }

    @Test
    fun `running the model from the middle discards what came after`() {
        val composer = Composer()
        runFrom(composer, "one", "two")
        runFrom(composer, "two", "three")
        runFrom(composer, "three", "four")
        assertEquals(4, composer.size)

        composer.back()
        composer.back()
        assertEquals("two", composer.current())

        runFrom(composer, "two", "different")
        assertEquals("the versions after the one we ran from should be gone", 3, composer.size)
        assertEquals("one", composer.versionAt(0))
        assertEquals("two", composer.versionAt(1))
        assertEquals("different", composer.versionAt(2))
        assertEquals("different", composer.current())
    }

    @Test
    fun `editing by hand costs nothing`() {
        val composer = Composer()
        runFrom(composer, "one", "two")
        runFrom(composer, "two", "three")

        composer.back()
        composer.updateCurrent("two, fixed")

        assertEquals("an edit must not discard a version", 3, composer.size)
        assertEquals("two, fixed", composer.current())
        assertEquals("three", composer.versionAt(2))
        assertEquals("three", composer.forward())
    }

    @Test
    fun `the flip returns to where it was pressed, not to the newest`() {
        val composer = Composer()
        runFrom(composer, "v0", "v1")
        runFrom(composer, "v1", "v2")
        runFrom(composer, "v2", "v3")

        composer.back()
        assertEquals("v2", composer.current())

        assertEquals("v0", composer.flip())
        assertTrue(composer.atOriginal)
        assertEquals("flipping back must not jump to the newest", "v2", composer.flip())
    }

    @Test
    fun `flipping from the newest returns to the newest`() {
        val composer = Composer()
        runFrom(composer, "v0", "v1")
        runFrom(composer, "v1", "v2")

        assertEquals("v0", composer.flip())
        assertEquals("v2", composer.flip())
    }

    @Test
    fun `walking away from the original cancels the flip`() {
        val composer = Composer()
        runFrom(composer, "v0", "v1")
        runFrom(composer, "v1", "v2")

        composer.flip()
        assertTrue(composer.atOriginal)
        // Stepping forward by hand is not "come back from the flip"; the next flip should behave
        // as if it were pressed here.
        assertEquals("v1", composer.forward())
        assertEquals("v0", composer.flip())
        assertEquals("v1", composer.flip())
    }

    @Test
    fun `the original survives a long chain`() {
        val composer = Composer()
        composer.captureBeforeRun("the original")
        repeat(Composer.MAX_VERSIONS * 2) { composer.addResult("version $it") }

        assertEquals(Composer.MAX_VERSIONS, composer.size)
        assertEquals("the original must never be dropped", "the original", composer.versionAt(0))
        assertEquals("version ${Composer.MAX_VERSIONS * 2 - 1}", composer.current())
        assertEquals("the original", composer.flip())
    }

    @Test
    fun `a tap on the line goes to that node`() {
        val composer = Composer()
        runFrom(composer, "v0", "v1")
        runFrom(composer, "v1", "v2")

        assertEquals("v1", composer.goTo(1))
        assertEquals(1, composer.index)
        assertEquals("a node that does not exist is not a node", "v1", composer.goTo(99))
        assertEquals(1, composer.index)
    }

    @Test
    fun `a prompt is named by what it is for`() {
        assertEquals("polite refusal", Composer.suggestedName("rewrite this as a polite refusal"))
        assertEquals("bullet list", Composer.suggestedName("Make it a bullet list"))
        assertEquals("shorter subject line", Composer.suggestedName("turn it into a shorter subject line please"))
    }

    @Test
    fun `a name is never empty and never longer than a button`() {
        assertTrue(Composer.suggestedName("rewrite the text").isNotEmpty())
        assertTrue(Composer.suggestedName("a").isNotEmpty())
        assertTrue(
            Composer.suggestedName("rewrite ".repeat(40)).length <= Composer.MAX_NAME_CHARS,
        )
    }
}
