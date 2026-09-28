// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The task list, whose ids cross the process boundary. */
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
    fun `every task's floor and ratio are values the native budget arithmetic can use`() {
        // The inputs of TextAssist::run's clamp(needed * outputRatio, minOutputTokens,
        // MAX_OUTPUT_TOKENS).
        for (task in AssistTask.entries) {
            assertTrue("${task.name}'s ratio is not positive", task.outputRatio > 0f)
            assertTrue(
                "${task.name}'s floor is above the shared ceiling",
                task.minOutputTokens <= AssistTask.MAX_OUTPUT_TOKENS,
            )
        }
    }

    @Test
    fun `a written instruction is carried, never replaced`() {
        val whole = AssistTask.customInstruction("  make it a bullet list  ")
        assertTrue("the user's words were lost", whole.contains("make it a bullet list"))
        assertTrue("the wrapper was lost", whole.contains("Change only the text"))
        assertTrue("the wrapper was lost", whole.contains("and nothing else"))
        // The wrapper comes first.
        assertTrue(
            "the user's words came before the instruction that frames them",
            whole.indexOf("Change only the text") < whole.indexOf("make it a bullet list"),
        )
    }

    @Test
    fun `the custom task's own instruction is the prefix the wrapper uses`() {
        // The enum entry carries the prefix and nothing else.
        assertTrue(
            AssistTask.customInstruction("make it shorter")
                .startsWith(AssistTask.CUSTOM.instruction),
        )
    }

    @Test
    fun `only the two tasks that need the whole text stay unchunkable`() {
        // Only SUMMARISE and CUSTOM are not chunked.
        val unchunkable = AssistTask.entries.filterNot { it.isChunkable }
        assertEquals(setOf(AssistTask.SUMMARISE, AssistTask.CUSTOM), unchunkable.toSet())
    }

    @Test
    fun `only the two tasks that must answer shorter than the input stay off the real ceiling`() {
        // Only SUMMARISE and SHORTEN stop at outputRatio's budget.
        val boundedByRatio = AssistTask.entries.filterNot { it.usesRemainingContext }
        assertEquals(setOf(AssistTask.SUMMARISE, AssistTask.SHORTEN), boundedByRatio.toSet())
    }

    @Test
    fun `the size floors rank the way the tasks do, and only shortening kinds have one`() {
        assertTrue(AssistTask.SUMMARISE.minWords > AssistTask.SHORTEN.minWords)
        assertTrue(AssistTask.SHORTEN.minWords > AssistTask.REWRITE_FORMAL.minWords)
        assertEquals(AssistTask.REWRITE_FORMAL.minWords, AssistTask.REWRITE_CASUAL.minWords)
        assertEquals(AssistTask.REWRITE_FORMAL.minWords, AssistTask.REWRITE_DIRECT.minWords)
        assertEquals(1, AssistTask.CORRECT.minWords)
        assertEquals(1, AssistTask.CUSTOM.minWords)
        for (task in AssistTask.entries) {
            assertTrue("${task.name}'s floor is below one word", task.minWords >= 1)
            if (task.name.startsWith("TRANSLATE_")) {
                assertEquals("${task.name} should run on a single word", 1, task.minWords)
            }
        }
    }

    @Test
    fun `only the translations are their own model category`() {
        val translating = AssistTask.entries.filter { it.category == AssistCategory.TRANSLATE }
        assertEquals(
            setOf(
                AssistTask.TRANSLATE_TO_ENGLISH, AssistTask.TRANSLATE_TO_ROMANIAN,
                AssistTask.TRANSLATE_TO_GERMAN, AssistTask.TRANSLATE_TO_SPANISH,
                AssistTask.TRANSLATE_TO_FRENCH, AssistTask.TRANSLATE_TO_ITALIAN,
            ),
            translating.toSet(),
        )
        assertTrue(
            "everything else is on the write side",
            AssistTask.entries.filter { it.category == AssistCategory.WRITE }
                .containsAll(listOf(AssistTask.CORRECT, AssistTask.SUMMARISE, AssistTask.CUSTOM)),
        )
    }
}
