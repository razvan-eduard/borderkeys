// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.theme.KeyboardPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitSpaceTest {

    private fun swallows(
        composingEmpty: Boolean = true,
        pendingAutoSpace: Boolean = true,
        habit: Int = KeyboardPreferences.AUTO_SPACE_SWALLOW_FIRST,
        before: Char? = ' ',
    ) = HabitSpace.swallows(composingEmpty, pendingAutoSpace, habit) { before }

    @Test
    fun `the keyboard's own space, typed again, is dropped`() {
        assertTrue(swallows())
    }

    @Test
    fun `a word in progress means the space ends it rather than repeating one`() {
        assertFalse(swallows(composingEmpty = false))
    }

    @Test
    fun `nothing is dropped when the keyboard did not add a space`() {
        assertFalse(swallows(pendingAutoSpace = false))
    }

    @Test
    fun `the keep setting never drops one`() {
        assertFalse(swallows(habit = KeyboardPreferences.AUTO_SPACE_KEEP))
    }

    /** The flag is still armed, but the character before the caret is not the space. */
    @Test
    fun `a remembered space that is no longer behind the caret is not dropped`() {
        assertFalse("an emoji was committed over it", swallows(before = '\uD83D'))
        assertFalse("the caret sits after a letter", swallows(before = 'o'))
        assertFalse("a pasted full stop", swallows(before = '.'))
        assertFalse("the caret moved to the start of the field", swallows(before = null))
        assertFalse("a line break, not a space", swallows(before = '\n'))
    }

    @Test
    fun `swallow-all keeps dropping, but still only behind a real space`() {
        val all = KeyboardPreferences.AUTO_SPACE_SWALLOW_ALL
        assertTrue(swallows(habit = all))
        assertTrue("the flag is not cleared, so the next one goes too", HabitSpace.staysArmed(all))
        assertFalse("and still never where the space is gone", swallows(habit = all, before = 'x'))
    }

    @Test
    fun `only swallow-all stays armed for the space after this one`() {
        assertFalse(HabitSpace.staysArmed(KeyboardPreferences.AUTO_SPACE_SWALLOW_FIRST))
        assertFalse(HabitSpace.staysArmed(KeyboardPreferences.AUTO_SPACE_KEEP))
        assertTrue(HabitSpace.staysArmed(KeyboardPreferences.AUTO_SPACE_SWALLOW_ALL))
    }
}
