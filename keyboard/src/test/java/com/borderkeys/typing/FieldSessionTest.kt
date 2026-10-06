// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldSessionTest {

    private fun session(types: List<String>, private: Boolean = false) = FieldSession(
        generation = 1,
        policy = FieldPolicy.of(
            passwordField = false,
            privateField = private,
            learningEnabled = true,
            heatmapEnabled = true,
        ),
        addressField = false,
        terminalField = false,
        contentMimeTypes = types,
    )

    @Test
    fun `a field that takes any image takes a photo and a screenshot`() {
        val field = session(listOf("image/*"))
        assertTrue(field.takesPhoto("image/jpeg"))
        assertTrue(field.takesPhoto("image/png"))
    }

    @Test
    fun `a field that declares no content types takes no photo`() {
        assertFalse(session(emptyList()).takesPhoto("image/png"))
    }

    @Test
    fun `a field takes only the image types it declares`() {
        val field = session(listOf("image/gif"))
        assertTrue(field.takesPhoto("image/gif"))
        assertFalse(field.takesPhoto("image/png"))
    }

    @Test
    fun `a field that takes anything takes a photo`() {
        assertTrue(session(listOf("*/*")).takesPhoto("image/png"))
    }

    @Test
    fun `a private field takes no photo, whatever it declares`() {
        assertFalse(session(listOf("image/*"), private = true).takesPhoto("image/png"))
    }
}
