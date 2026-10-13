// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The explain panel's account of a word through [Pipeline], against the shipped packs. */
class ExplanationPipelineTest {

    @Test
    fun `an explanation names the pack its word came from after the languages change`() {
        Pipeline.require()
        val pipeline = Pipeline.open(ROMANIAN, ENGLISH)
        try {
            pipeline.swapLanguage(ROMANIAN, GERMAN)
            val explanation = checkNotNull(pipeline.explain(WORD, WORD))
            assertNotEquals(
                "the engine's slot is not the place in the enabled list",
                pipeline.languages.indexOf(ENGLISH), explanation.packIndex,
            )
            assertEquals(ENGLISH, explanation.packTag)
        } finally {
            pipeline.close()
        }
    }

    private companion object {
        const val ROMANIAN = "ro-RO"
        const val ENGLISH = "en-US"
        const val GERMAN = "de-DE"
        const val WORD = "because"
    }
}
