// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import androidx.datastore.core.CorruptionException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class KeyboardThemeSerializerTest {

    private fun write(theme: KeyboardTheme): ByteArray = ByteArrayOutputStream().also { output ->
        kotlinx.coroutines.runBlocking { KeyboardThemeSerializer.writeTo(theme, output) }
    }.toByteArray()

    private suspend fun read(bytes: ByteArray): KeyboardTheme =
        KeyboardThemeSerializer.readFrom(ByteArrayInputStream(bytes))

    @Test
    fun `a theme survives a round trip unchanged`() = runTest {
        val original = KeyboardTheme(
            backgroundColor = 0xFF102030.toInt(),
            keyColor = 0xFF405060.toInt(),
            keyPressedColor = 0xFF708090.toInt(),
            modifierKeyColor = 0xFF0A0B0C.toInt(),
            textColor = 0xFFFFFFFF.toInt(),
            secondaryTextColor = 0xFF808080.toInt(),
            accentColor = 0xFF00FF88.toInt(),
            keyCornerRadiusDp = 12f,
            keyGapDp = 5.5f,
            rowHeightDp = 58f,
            labelTextSizeSp = 22f,
            showKeyBorders = true,
            pressedElevation = 3f,
            swipeTrailColor = 0x8800FF88.toInt(),
            swipeTrailWidthDp = 6f,
        )
        assertEquals(original, read(write(original)))
    }

    @Test
    fun `negative and alpha-heavy colours survive as exact ints`() = runTest {
        // Packed ARGB is signed once the top bit is set, and a serializer that went through a
        // float or a string would quietly lose it. The whole point of storing an Int is that
        // Paint.setColor gets the value back untouched.
        val original = KeyboardTheme(backgroundColor = -1, swipeTrailColor = Int.MIN_VALUE)
        val restored = read(write(original))
        assertEquals(-1, restored.backgroundColor)
        assertEquals(Int.MIN_VALUE, restored.swipeTrailColor)
    }

    @Test
    fun `an empty file reads as the default rather than failing`() = runTest {
        // DataStore hands the serializer an empty stream for a file that does not exist yet,
        // which is the first launch of the application.
        assertEquals(KeyboardTheme(), read(ByteArray(0)))
    }

    @Test
    fun `a truncated file is reported as corruption, not as an arbitrary exception`() {
        // Only a CorruptionException reaches DataStore's replace handler. Anything else escapes
        // to whoever was collecting the flow -- on the UI thread, while showing a keyboard.
        val truncated = write(KeyboardTheme()).copyOfRange(0, 20)
        assertThrows(CorruptionException::class.java) {
            kotlinx.coroutines.runBlocking { read(truncated) }
        }
    }

    @Test
    fun `garbage and invalid UTF-8 are both reported as corruption`() {
        assertThrows(CorruptionException::class.java) {
            kotlinx.coroutines.runBlocking { read("this is not json".encodeToByteArray()) }
        }
        assertThrows(CorruptionException::class.java) {
            kotlinx.coroutines.runBlocking {
                read(byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D))
            }
        }
    }

    @Test
    fun `a file written by a newer build is read, not rejected`() = runTest {
        // ignoreUnknownKeys is what stops a downgrade, or a settings screen from a newer
        // version, from making the keyboard unable to read its own theme.
        val fromTheFuture = """{"backgroundColor":-16777216,"someFieldFromLater":42}"""
        val restored = read(fromTheFuture.encodeToByteArray())
        assertEquals(-16777216, restored.backgroundColor)
        // Fields that were not in the file fall back to their defaults.
        assertEquals(KeyboardTheme().labelTextSizeSp, restored.labelTextSizeSp, 0.001f)
    }

    @Test
    fun `an out-of-range dimension is clamped on read`() = runTest {
        // A theme file that parses is not a theme file that makes sense. A row height of 40000
        // does not throw -- it produces a keyboard taller than the screen, with the settings
        // that would fix it behind it.
        val absurd = """{"rowHeightDp":40000.0,"keyGapDp":-5.0,"labelTextSizeSp":900.0}"""
        val restored = read(absurd.encodeToByteArray())
        assertTrue(restored.rowHeightDp in 28f..96f)
        assertTrue(restored.keyGapDp in 0f..16f)
        assertTrue(restored.labelTextSizeSp in 8f..40f)
        assertNotEquals(40000f, restored.rowHeightDp)
    }

    @Test
    fun `preferences round trip and clamp the same way`() = runTest {
        val original = KeyboardPreferences(
            clipboardRetentionMinutes = 15,
            clipboardEnabled = false,
            perAppLanguageMemory = true,
        )
        val bytes = ByteArrayOutputStream().also { output ->
            kotlinx.coroutines.runBlocking {
                KeyboardPreferencesSerializer.writeTo(original, output)
            }
        }.toByteArray()
        assertEquals(original, KeyboardPreferencesSerializer.readFrom(ByteArrayInputStream(bytes)))

        val absurd = """{"clipboardRetentionMinutes":-1,"clipboardMaxEntries":100000}"""
        val clamped = KeyboardPreferencesSerializer.readFrom(
            ByteArrayInputStream(absurd.encodeToByteArray()),
        )
        assertTrue(clamped.clipboardRetentionMinutes >= 1)
        assertTrue(clamped.clipboardMaxEntries <= 1000)
    }

    @Test
    fun `the privacy-sensitive default is off`() {
        // Per-app language memory stores a behavioural profile, however small and however local.
        // If this default ever flips, it should flip in a diff that touches this test.
        assertEquals(false, KeyboardPreferences().perAppLanguageMemory)
        assertEquals(true, KeyboardPreferences().clipboardEnabled)
        assertEquals(60, KeyboardPreferences().clipboardRetentionMinutes)
    }
}
