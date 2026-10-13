// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** [PredictionEngine.shutdown] answers the verdict requests still queued on the prediction thread. */
@RunWith(SharedNativeLibraryRunner::class)
@Config(sdk = [PredictionEngineShutdownTest.SDK])
class PredictionEngineShutdownTest {

    @Test
    fun `a verdict request still queued at shutdown is answered every word learnable, once`() {
        Pipeline.require()
        val engine = PredictionEngine()
        val release = CountDownLatch(1)
        try {
            assertTrue(engine.start())
            val held = CountDownLatch(1)
            var worker: Thread? = null
            engine.learnableWords(WORDS) {
                worker = Thread.currentThread()
                held.countDown()
                release.await(WAIT_SECONDS, TimeUnit.SECONDS)
            }
            assertTrue("the prediction thread is held", held.await(WAIT_SECONDS, TimeUnit.SECONDS))
            val answers = CopyOnWriteArrayList<List<Boolean>>()
            engine.learnableWords(WORDS) { answers += it }

            engine.shutdown()
            assertEquals(listOf(ALL_LEARNABLE), answers.toList())

            release.countDown()
            worker!!.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            assertEquals(listOf(ALL_LEARNABLE), answers.toList())
        } finally {
            release.countDown()
            engine.shutdown()
        }
    }

    companion object {
        /** The newest Android Robolectric runs. */
        const val SDK = 35

        /** How long the test waits for the prediction thread at most. */
        const val WAIT_SECONDS = 10L

        val WORDS = listOf("the", "zqx")

        /** Every word of [WORDS] learnable. */
        val ALL_LEARNABLE = listOf(true, true)
    }
}
