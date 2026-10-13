// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictRequestsTest {

    private val requests = VerdictRequests()
    private val answers = mutableListOf<List<Boolean>>()
    private val posted = mutableListOf<Runnable>()
    private var judged = 0

    /** Submits [WORDS]; the post takes the work when [accepted], and the judge answers [VERDICTS]. */
    private fun submit(accepted: Boolean = true) {
        requests.submit(
            WORDS,
            { answers += it },
            post = { work -> if (accepted) posted += work; accepted },
        ) {
            judged++
            VERDICTS
        }
    }

    @Test
    fun `a request that runs is answered with the verdicts, once`() {
        submit()
        posted.single().run()
        assertEquals(listOf(VERDICTS), answers)
        assertEquals(1, judged)
    }

    @Test
    fun `a request whose post is refused is answered every word learnable at once, unjudged`() {
        submit(accepted = false)
        assertEquals(listOf(ALL_LEARNABLE), answers)
        assertEquals(0, judged)
        requests.close()
        assertEquals(listOf(ALL_LEARNABLE), answers)
    }

    @Test
    fun `a request whose post closes the holder and then refuses is answered once`() {
        requests.submit(
            WORDS,
            { answers += it },
            post = {
                requests.close()
                false
            },
        ) {
            judged++
            VERDICTS
        }
        assertEquals(listOf(ALL_LEARNABLE), answers)
    }

    @Test
    fun `a request posted but not run is answered every word learnable at close, once`() {
        submit()
        requests.close()
        assertEquals(listOf(ALL_LEARNABLE), answers)
        posted.single().run()
        assertEquals(listOf(ALL_LEARNABLE), answers)
        assertEquals(0, judged)
    }

    @Test
    fun `a request after close is answered every word learnable at once and nothing is posted`() {
        requests.close()
        submit()
        assertEquals(listOf(ALL_LEARNABLE), answers)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun `a request that already ran is not answered again at close`() {
        submit()
        posted.single().run()
        requests.close()
        assertEquals(listOf(VERDICTS), answers)
    }

    private companion object {
        val WORDS = listOf("the", "zqx")

        /** The judge's verdicts on [WORDS]. */
        val VERDICTS = listOf(true, false)

        /** Every word of [WORDS] learnable. */
        val ALL_LEARNABLE = listOf(true, true)
    }
}
