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

    @Test
    fun `a pattern from a future version is dropped rather than drawn`() {
        val fromLater = KeyboardTheme(backgroundPatterns = listOf(99)).sanitised()
        assertEquals(emptyList<Int>(), fromLater.backgroundPatterns)

        val known = KeyboardTheme(
            backgroundPatterns = listOf(KeyboardTheme.PATTERN_CHECKS, 99),
        ).sanitised()
        assertEquals(listOf(KeyboardTheme.PATTERN_CHECKS), known.backgroundPatterns)
    }

    @Test
    fun `patterns layer, but the same one twice is once`() {
        // They are drawn in the order given and each is one shader; the same tile twice would
        // be a second full-surface draw for a difference nobody can see.
        val doubled = KeyboardTheme(
            backgroundPatterns = listOf(
                KeyboardTheme.PATTERN_GRID,
                KeyboardTheme.PATTERN_DOTS,
                KeyboardTheme.PATTERN_GRID,
            ),
        ).sanitised()
        assertEquals(
            listOf(KeyboardTheme.PATTERN_GRID, KeyboardTheme.PATTERN_DOTS),
            doubled.backgroundPatterns,
        )
    }

    @Test
    fun `a picture name that could leave its directory is refused`() {
        // The name is joined to this application's own directory to find the file. A stored
        // value carrying a separator would be a path, and a path can point anywhere.
        assertEquals("", KeyboardTheme(backgroundImage = "../../databases/x").sanitised().backgroundImage)
        assertEquals("", KeyboardTheme(backgroundImage = "a\\b").sanitised().backgroundImage)
        assertEquals(
            "background-1.webp",
            KeyboardTheme(backgroundImage = "background-1.webp").sanitised().backgroundImage,
        )
    }

    @Test
    fun `the picture dim stays a fraction`() {
        assertEquals(0f, KeyboardTheme(backgroundImageDim = -3f).sanitised().backgroundImageDim, 0f)
        assertEquals(1f, KeyboardTheme(backgroundImageDim = 9f).sanitised().backgroundImageDim, 0f)
    }

    @Test
    fun `a pattern tile is clamped to a size that can be drawn`() {
        assertEquals(8f, KeyboardTheme(patternScaleDp = 0f).sanitised().patternScaleDp, 0f)
        assertEquals(64f, KeyboardTheme(patternScaleDp = 4000f).sanitised().patternScaleDp, 0f)
    }

    @Test
    fun `no second colour means the background does not fade into anything`() {
        val flat = KeyboardTheme(backgroundColor = 0xFF102030.toInt())
        assertEquals(flat.backgroundColor, flat.gradientEnd())

        val faded = flat.copy(backgroundGradientColor = 0xFF405060.toInt())
        assertEquals(0xFF405060.toInt(), faded.gradientEnd())
    }

    @Test
    fun `a theme written before patterns could layer keeps the one it had`() = runTest {
        val old = """{"backgroundColor":-16777216,"backgroundPattern":4}"""
        assertEquals(
            listOf(KeyboardTheme.PATTERN_CHECKS),
            read(old.encodeToByteArray()).backgroundPatterns,
        )
    }

    @Test
    fun `the older key is ignored once the newer one is present`() = runTest {
        val both = """{"backgroundPatterns":[1],"backgroundPattern":4}"""
        assertEquals(
            listOf(KeyboardTheme.PATTERN_DOTS),
            read(both.encodeToByteArray()).backgroundPatterns,
        )
    }
}
