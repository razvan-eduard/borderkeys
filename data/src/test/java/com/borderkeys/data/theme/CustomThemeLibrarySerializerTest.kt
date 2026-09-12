// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import androidx.datastore.core.CorruptionException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CustomThemeLibrarySerializerTest {

    private fun write(library: CustomThemeLibrary): ByteArray = ByteArrayOutputStream().also { output ->
        kotlinx.coroutines.runBlocking { CustomThemeLibrarySerializer.writeTo(library, output) }
    }.toByteArray()

    private suspend fun read(bytes: ByteArray): CustomThemeLibrary =
        CustomThemeLibrarySerializer.readFrom(ByteArrayInputStream(bytes))

    @Test
    fun `a library survives a round trip unchanged`() = runTest {
        val original = CustomThemeLibrary(
            themes = listOf(
                CustomThemeEntry(id = "a", name = "First", theme = KeyboardTheme(), createdAt = 1L),
                CustomThemeEntry(
                    id = "b",
                    name = "Second",
                    theme = KeyboardTheme(accentColor = 0xFF112233.toInt()),
                    createdAt = 2L,
                ),
            ),
        )
        assertEquals(original, read(write(original)))
    }

    @Test
    fun `an empty file reads as an empty library rather than failing`() = runTest {
        assertEquals(CustomThemeLibrary(), read(ByteArray(0)))
    }

    @Test
    fun `garbage is reported as corruption, not as an arbitrary exception`() {
        assertThrows(CorruptionException::class.java) {
            kotlinx.coroutines.runBlocking { read("not json".encodeToByteArray()) }
        }
    }

    @Test
    fun `a library longer than the limit is trimmed on read`() = runTest {
        val tooMany = CustomThemeLibrary(
            themes = (0 until CustomThemeLibrary.MAX_CUSTOM_THEMES + 5).map {
                CustomThemeEntry(id = it.toString(), name = "Theme $it", theme = KeyboardTheme())
            },
        )
        val restored = read(write(tooMany))
        assertEquals(CustomThemeLibrary.MAX_CUSTOM_THEMES, restored.themes.size)
    }

    @Test
    fun `an entry name longer than the limit is trimmed on read`() = runTest {
        val long = "x".repeat(CustomThemeEntry.MAX_NAME_LENGTH + 20)
        val library = CustomThemeLibrary(
            themes = listOf(CustomThemeEntry(id = "a", name = long, theme = KeyboardTheme())),
        )
        val restored = read(write(library))
        assertEquals(CustomThemeEntry.MAX_NAME_LENGTH, restored.themes.single().name.length)
    }
}
