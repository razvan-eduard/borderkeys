// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The task list is a wire format.
 *
 * The id crosses a process boundary and is written into nothing else, so a duplicate or a
 * renumbering is a request that runs the wrong instruction on somebody's text. These tests are
 * cheap and they are the only thing standing between that and a careless edit.
 */
class AssistTaskTest {

    @Test
    fun `every id is unique`() {
        val ids = AssistTask.entries.map { it.id }
        assertEquals("two tasks share an id", ids.size, ids.toSet().size)
    }

    @Test
    fun `every task survives a round trip through its id`() {
        for (task in AssistTask.entries) {
            assertEquals(task, AssistTask.fromId(task.id))
        }
    }

    @Test
    fun `an id from a later build is refused rather than guessed`() {
        assertEquals(null, AssistTask.fromId(9999))
        assertEquals(null, AssistTask.fromId(0))
        assertEquals(null, AssistTask.fromId(-1))
    }

    @Test
    fun `every instruction says to reply with the text and nothing else`() {
        // Without that clause a small model answers "Sure! Here is your text:" and the preamble
        // ends up in somebody's message. It is the one thing every instruction must carry.
        for (task in AssistTask.entries) {
            assertTrue(
                "${task.name} does not tell the model to reply with the result alone",
                task.instruction.contains("and nothing else"),
            )
        }
    }

    @Test
    fun `shortening asks for less than it was given`() {
        // The only ratio under 1.0, and the difference between shortening and summarising.
        assertTrue(AssistTask.SHORTEN.outputRatio < 1f)
        assertTrue(AssistTask.SUMMARISE.outputRatio < AssistTask.SHORTEN.outputRatio)
        assertNotEquals(AssistTask.SHORTEN.instruction, AssistTask.SUMMARISE.instruction)
        assertTrue(AssistTask.SHORTEN.instruction.contains("Do not summarise"))
    }

    @Test
    fun `the budget stays between the floor and the ceiling`() {
        for (task in AssistTask.entries) {
            assertEquals(
                "${task.name} ignores its floor for an empty input",
                task.minOutputTokens,
                task.outputTokenBudget(0),
            )
            assertEquals(
                "${task.name} runs past the ceiling for a long input",
                AssistTask.MAX_OUTPUT_TOKENS,
                task.outputTokenBudget(1_000_000),
            )
        }
    }

    @Test
    fun `a written instruction is carried, never replaced`() {
        val whole = AssistTask.customInstruction("  make it a bullet list  ")
        assertTrue("the user's words were lost", whole.contains("make it a bullet list"))
        assertTrue("the wrapper was lost", whole.contains("Change only the text"))
        assertTrue("the wrapper was lost", whole.contains("and nothing else"))
        // The wrapper comes first, so text arriving from a clipboard reads as material rather
        // than as orders.
        assertTrue(
            "the user's words came before the instruction that frames them",
            whole.indexOf("Change only the text") < whole.indexOf("make it a bullet list"),
        )
    }

    @Test
    fun `the custom task's own instruction is the prefix the wrapper uses`() {
        // The enum entry carries the prefix and nothing else, so anything reading the task list
        // to show a user what is sent shows the same words the wrapper actually sends.
        assertTrue(
            AssistTask.customInstruction("make it shorter")
                .startsWith(AssistTask.CUSTOM.instruction),
        )
    }

    @Test
    fun `only the two tasks that need the whole text stay unchunkable`() {
        // SUMMARISE: three summaries of three chunks read as a summary repeating itself, not one
        // summary of the whole. CUSTOM: an instruction someone wrote by hand could easily be a
        // summarising one, and nothing here can tell the two apart to know it should refuse
        // chunking too. Every other task transforms close to sentence by sentence already, so
        // chunking it changes nothing about what the answer says.
        val unchunkable = AssistTask.entries.filterNot { it.isChunkable }
        assertEquals(setOf(AssistTask.SUMMARISE, AssistTask.CUSTOM), unchunkable.toSet())
    }

    @Test
    fun `only the two tasks that must answer shorter than the input stay off the real ceiling`() {
        // SUMMARISE and SHORTEN are correct only when the answer is shorter than what was given,
        // so outputRatio's guess is the ceiling generation should actually stop at. Every other
        // task -- including CUSTOM, where a handwritten instruction could ask for anything -- can
        // legitimately need more room than a length-based guess predicted, so the real space left
        // in the context window governs instead.
        val boundedByRatio = AssistTask.entries.filterNot { it.usesRemainingContext }
        assertEquals(setOf(AssistTask.SUMMARISE, AssistTask.SHORTEN), boundedByRatio.toSet())
    }
}
