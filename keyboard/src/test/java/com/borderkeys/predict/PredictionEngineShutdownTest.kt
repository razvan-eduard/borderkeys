// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PredictionEngine.shutdown] on the host library, its main and prediction threads stepped by hand:
 * the verdict requests still queued on the prediction thread are answered, and the thread stops.
 */
class PredictionEngineShutdownTest {

    @Test
    fun `a verdict request still queued at shutdown is answered every word learnable, once, and the prediction thread stops`() {
        Pipeline.require()
        val main = SteppedThread()
        val prediction = SteppedThread()
        val engine = PredictionEngine(main) { prediction }
        try {
            assertTrue(engine.start())
            val answers = mutableListOf<List<Boolean>>()
            engine.learnableWords(WORDS) { answers += it }
            assertEquals("the request waits on the prediction thread", emptyList<List<Boolean>>(), answers)

            engine.shutdown()
            assertEquals(listOf(ALL_LEARNABLE), answers)

            prediction.drain()
            main.drain()
            assertEquals(listOf(ALL_LEARNABLE), answers)
            assertTrue("the prediction thread has stopped", prediction.stopped)
        } finally {
            engine.shutdown()
            prediction.drain()
        }
    }

    companion object {
        val WORDS = listOf("the", "zqx")

        /** Every word of [WORDS] learnable. */
        val ALL_LEARNABLE = listOf(true, true)
    }
}
