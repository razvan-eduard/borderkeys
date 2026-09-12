// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The macro-specific half of the quick-action bar: expanding one into the steps that actually
 * run, and refusing one that would refer back to itself. [KeyboardPreferencesTest] covers the
 * sanitising this backs (dropping an ineligible or cyclic step from a persisted file); this is
 * the logic itself, on data [KeyboardPreferences.sanitised] never has to construct.
 */
class QuickActionBarTest {

    @Test
    fun `flatten expands a macro's steps in order`() {
        val macro = CustomQuickAction(
            id = 1000,
            name = "select and cut",
            steps = listOf(QuickAction.SELECT_ALL.id, QuickAction.CUT.id),
        )
        assertEquals(
            listOf(QuickAction.SELECT_ALL, QuickAction.CUT),
            QuickActionBar.flatten(macro, emptyList()),
        )
    }

    @Test
    fun `flatten expands a macro that refers to another macro`() {
        val inner = CustomQuickAction(id = 1000, name = "select all", steps = listOf(QuickAction.SELECT_ALL.id))
        val outer = CustomQuickAction(id = 1001, name = "select and cut", steps = listOf(inner.id, QuickAction.CUT.id))
        assertEquals(
            listOf(QuickAction.SELECT_ALL, QuickAction.CUT),
            QuickActionBar.flatten(outer, listOf(inner, outer)),
        )
    }

    @Test
    fun `flatten drops a step naming neither a known action nor a known macro`() {
        val macro = CustomQuickAction(id = 1000, name = "stale", steps = listOf(QuickAction.CUT.id, 999_999))
        assertEquals(listOf(QuickAction.CUT), QuickActionBar.flatten(macro, emptyList()))
    }

    @Test
    fun `flatten does not recurse forever on a cycle that reached it anyway`() {
        // hasCycle is what normally keeps this out of a saved file -- this is the defence for
        // the file already existing with one anyway (hand-edited, or written before hasCycle).
        val a = CustomQuickAction(id = 1000, name = "a", steps = listOf(1001))
        val b = CustomQuickAction(id = 1001, name = "b", steps = listOf(1000))
        val result = QuickActionBar.flatten(a, listOf(a, b))
        assertTrue("a pure reference cycle expands to no runnable steps", result.isEmpty())
    }

    @Test
    fun `hasCycle is false for a macro that only refers forward to plain actions`() {
        val macro = CustomQuickAction(id = 1000, name = "a", steps = listOf(QuickAction.CUT.id))
        assertFalse(QuickActionBar.hasCycle(macro, emptyList()))
    }

    @Test
    fun `hasCycle catches a macro that would refer to itself`() {
        val macro = CustomQuickAction(id = 1000, name = "a", steps = listOf(1000))
        assertTrue(QuickActionBar.hasCycle(macro, emptyList()))
    }

    @Test
    fun `hasCycle catches an indirect cycle through a second macro`() {
        val b = CustomQuickAction(id = 1001, name = "b", steps = listOf(1000))
        // Candidate: editing 1000 to now also refer to 1001, which already refers back to 1000.
        val candidateA = CustomQuickAction(id = 1000, name = "a", steps = listOf(1001))
        assertTrue(QuickActionBar.hasCycle(candidateA, listOf(b)))
    }

    @Test
    fun `hasCycle checks the candidate's new steps, not its old ones`() {
        // b existing in the list is the OLD version of the id being edited; hasCycle must judge
        // the candidate replacing it, not find a false cycle against the entry it is replacing.
        val oldA = CustomQuickAction(id = 1000, name = "a", steps = emptyList())
        val candidateA = CustomQuickAction(id = 1000, name = "a", steps = listOf(QuickAction.CUT.id))
        assertFalse(QuickActionBar.hasCycle(candidateA, listOf(oldA)))
    }

    @Test
    fun `sanitisedSteps drops a step that is not macro-eligible`() {
        val steps = listOf(QuickAction.CUT.id, QuickAction.SETTINGS.id, QuickAction.COMPOSE.id)
        assertEquals(
            listOf(QuickAction.CUT.id),
            QuickActionBar.sanitisedSteps(selfId = 1000, steps = steps, customActions = emptyList()),
        )
    }

    @Test
    fun `sanitisedSteps drops a step naming its own action's id`() {
        val steps = listOf(QuickAction.CUT.id, 1000)
        assertEquals(
            listOf(QuickAction.CUT.id),
            QuickActionBar.sanitisedSteps(selfId = 1000, steps = steps, customActions = emptyList()),
        )
    }

    @Test
    fun `sanitisedSteps clears the whole list when it would still close a cycle`() {
        val b = CustomQuickAction(id = 1001, name = "b", steps = listOf(1000))
        val steps = listOf(1001, QuickAction.CUT.id)
        assertEquals(
            "a cycle is cleared entirely rather than partially patched",
            emptyList<Int>(),
            QuickActionBar.sanitisedSteps(selfId = 1000, steps = steps, customActions = listOf(b)),
        )
    }

    @Test
    fun `macroEligible excludes exactly the actions that are not a plain edit on the field`() {
        val ineligible = setOf(
            QuickAction.CLIPBOARD_HISTORY, QuickAction.SWITCH_LAYOUT,
            QuickAction.SETTINGS, QuickAction.COMPOSE,
        )
        for (action in QuickAction.entries) {
            assertEquals(action.name, action !in ineligible, action.macroEligible)
        }
    }

    @Test
    fun `resolve mixes builtin and custom ids in order and drops an unknown one`() {
        val custom = CustomQuickAction(id = 1000, name = "select and cut", steps = listOf(QuickAction.CUT.id))
        val resolved = QuickActionBar.resolve(
            ids = listOf(QuickAction.SELECT_ALL.id, custom.id, 999_999),
            customActions = listOf(custom),
        )
        assertEquals(
            listOf(
                QuickActionBarItem.Builtin(QuickAction.SELECT_ALL),
                QuickActionBarItem.Custom(custom),
            ),
            resolved,
        )
    }
}
