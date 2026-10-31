// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PersonalModelReload] on fakes of the three places a learn passes through: the engine's queue
 * of learns not yet reported, the buffer, and the rows the load reads.
 */
class PersonalModelReloadTest {

    private val queued = mutableListOf("teh", "the")
    private val buffer = mutableListOf<String>()
    private val rows = mutableListOf<String>()
    private var loaded: List<String>? = null
    private val steps = mutableListOf<String>()

    private val reload = PersonalModelReload(
        settle = {
            steps += "settle"
            buffer += queued
            queued.clear()
        },
        flush = {
            steps += "flush"
            rows += buffer
            buffer.clear()
        },
        load = {
            steps += "load"
            loaded = rows.toList()
        },
        stale = { steps += "stale" },
        fresh = { steps += "fresh" },
    )

    @Test
    fun `the engine settles, the links go stale, the buffer is written, the rows are loaded, and once the engine settles again the links are fresh`() = runTest {
        reload.reload()
        assertEquals(listOf("settle", "stale", "flush", "load", "settle", "fresh"), steps)
    }

    @Test
    fun `a learn the engine had not yet reported is in the rows the load reads`() = runTest {
        reload.reload()
        assertEquals(listOf("teh", "the"), loaded)
    }

    @Test
    fun `what the caller puts between runs after the settle and before the flush`() = runTest {
        reload.reload(
            between = {
                steps += "between"
                buffer.remove("the")
            },
        )
        assertEquals(listOf("settle", "stale", "between", "flush", "load", "settle", "fresh"), steps)
        assertEquals(listOf("teh"), loaded)
    }
}
