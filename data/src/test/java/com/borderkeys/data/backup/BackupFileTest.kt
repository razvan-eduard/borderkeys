// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.backup

import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The backup file: its payload, its encryption, and each way reading it can fail. */
class BackupFileTest {

    private val sensitive = BackupPayload(
        words = listOf(BackupWord("cana", "ro-RO", 12)),
        bigrams = listOf(BackupBigram("o", "cana", 4)),
    )

    private val settingsOnly = BackupPayload(
        preferences = KeyboardPreferences(numberRow = true),
        theme = KeyboardTheme(backgroundColor = 0xFF102030.toInt()),
        customThemes = listOf(
            BackupCustomTheme(
                id = "abc",
                name = "Sunset Two",
                theme = KeyboardTheme(accentColor = 0xFF112233.toInt()),
                createdAt = 1700000000000L,
            ),
        ),
        models = listOf(BackupModel(fileName = "model.gguf", sha256 = "abc123", active = true)),
    )

    @Test
    fun `settings survive a round trip unencrypted`() {
        val text = BackupFile.write(settingsOnly, passphrase = "")
        assertFalse("nothing private is in it", settingsOnly.isSensitive)
        assertFalse(BackupFile.needsPassphrase(text))

        val payload = BackupFile.read(text, passphrase = "").payload
        assertNotNull(payload)
        assertEquals(true, payload?.preferences?.numberRow)
        assertEquals(0xFF102030.toInt(), payload?.theme?.backgroundColor)
        assertEquals(
            listOf(
                BackupCustomTheme(
                    "abc",
                    "Sunset Two",
                    KeyboardTheme(accentColor = 0xFF112233.toInt()),
                    1700000000000L,
                ),
            ),
            payload?.customThemes,
        )
        // Which model was active, not the model itself.
        assertEquals(listOf(BackupModel("model.gguf", "abc123", true)), payload?.models)
    }

    @Test
    fun `a dictionary survives a round trip through a passphrase`() {
        assertTrue("a dictionary is private", sensitive.isSensitive)
        val text = BackupFile.write(sensitive, passphrase = "correct horse")
        assertTrue(BackupFile.needsPassphrase(text))
        // The words must not be sitting in the file for anyone who opens it in an editor.
        assertFalse("the plaintext is in the file", text.contains("cana"))

        val payload = BackupFile.read(text, passphrase = "correct horse").payload
        assertEquals(listOf(BackupWord("cana", "ro-RO", 12)), payload?.words)
        assertEquals(listOf(BackupBigram("o", "cana", 4)), payload?.bigrams)
    }

    @Test
    fun `a wrong passphrase is told apart from a damaged file`() {
        val text = BackupFile.write(sensitive, passphrase = "correct horse")
        val result = BackupFile.read(text, passphrase = "incorrect horse")
        assertNull(result.payload)
        assertEquals(BackupFile.Failure.WRONG_PASSPHRASE, result.failure)
    }

    @Test
    fun `an encrypted file asks for a passphrase rather than failing`() {
        val text = BackupFile.write(sensitive, passphrase = "correct horse")
        val result = BackupFile.read(text, passphrase = "")
        assertEquals(BackupFile.Failure.NEEDS_PASSPHRASE, result.failure)
    }

    @Test
    fun `something that is not a backup is refused as one`() {
        assertEquals(
            BackupFile.Failure.NOT_A_BACKUP,
            BackupFile.read("this is not json", passphrase = "").failure,
        )
        assertEquals(
            BackupFile.Failure.NOT_A_BACKUP,
            BackupFile.read(
                """{"format":1,"application":"other","encrypted":false,"payload":"e30="}""",
                passphrase = "",
            ).failure,
        )
    }

    @Test
    fun `a file from a later format is refused rather than half read`() {
        val text = BackupFile.write(settingsOnly, passphrase = "")
            .replace("\"format\":2", "\"format\":99")
        assertEquals(BackupFile.Failure.NOT_A_BACKUP, BackupFile.read(text, "").failure)
    }

    @Test
    fun `a tampered payload is caught rather than decrypted into rubbish`() {
        val text = BackupFile.write(sensitive, passphrase = "correct horse")
        // One character flipped inside the ciphertext.
        val start = text.indexOf("\"payload\":\"") + 11
        val tampered = text.substring(0, start) +
            (if (text[start] == 'A') 'B' else 'A') + text.substring(start + 1)
        assertNull(BackupFile.read(tampered, "correct horse").payload)
    }

    @Test
    fun `an empty backup is still a backup`() {
        val text = BackupFile.write(BackupPayload(), passphrase = "")
        val payload = BackupFile.read(text, "").payload
        assertNotNull(payload)
        assertFalse(payload!!.isSensitive)
        assertNull(payload.preferences)
    }

    @Test
    fun `a new file declares the current format and round count`() {
        val text = BackupFile.write(sensitive, passphrase = "correct horse")
        assertTrue(text.contains("\"format\":2"))
        assertTrue(text.contains("\"iterations\":600000"))
    }

    @Test
    fun `a file written at the earlier round count still opens`() {
        val text = javaClass.getResource("/backup-format1-210k.json")!!.readText()
        assertTrue(text.contains("\"format\":1"))
        assertTrue(text.contains("\"iterations\":210000"))
        val payload = BackupFile.read(text, passphrase = "correct horse").payload
        assertEquals(listOf(BackupWord("cana", "ro-RO", 12)), payload?.words)
    }

    @Test
    fun `a round count above the cap is refused before anything is derived`() {
        val text = BackupFile.write(sensitive, passphrase = "correct horse")
            .replace("\"iterations\":600000", "\"iterations\":5000001")
        assertEquals(BackupFile.Failure.DAMAGED, BackupFile.read(text, "correct horse").failure)
    }

    @Test
    fun `an encrypted file without a round count is damaged`() {
        val text = BackupFile.write(sensitive, passphrase = "correct horse")
            .replace("\"iterations\":600000,", "")
        assertEquals(BackupFile.Failure.DAMAGED, BackupFile.read(text, "correct horse").failure)
    }

    @Test
    fun `the key derivation matches the published vectors`() {
        // RFC 7914, section 11.
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            BackupFile.deriveKey("passwd", "salt".toByteArray(), 1, 512).hex(),
        )
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56" +
                "a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            BackupFile.deriveKey("Password", "NaCl".toByteArray(), 80_000, 512).hex(),
        )
    }

    @Test
    fun `a changed header is refused the way a wrong passphrase is`() {
        val text = BackupFile.write(sensitive, passphrase = "correct horse")
        val downgraded = text.replace("\"format\":2", "\"format\":1")
        val result = BackupFile.read(downgraded, "correct horse")
        assertNull(result.payload)
        assertEquals(BackupFile.Failure.WRONG_PASSPHRASE, result.failure)
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun `a private clip keeps its flag through a file, and an older file reads as not private`() {
        val clips = BackupPayload(
            clips = listOf(
                BackupClip("a card number", 1L, false, private = true),
                BackupClip("a note", 2L, true),
            ),
        )
        val text = BackupFile.write(clips, passphrase = "correct horse")
        val read = BackupFile.read(text, "correct horse").payload!!.clips
        assertEquals(listOf(true, false), read.map { it.private })

        val older = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(
            BackupPayload.serializer(),
            """{"clips":[{"content":"a note","createdAt":2,"pinned":true}]}""",
        )
        assertFalse(older.clips.single().private)
    }

    @Test
    fun `the clipboard counts as private too`() {
        val clips = BackupPayload(clips = listOf(BackupClip("a card number", 1L, false)))
        assertTrue(clips.isSensitive)
    }

    @Test
    fun `a saved custom theme is not sensitive, same as the active theme`() {
        val customThemes = BackupPayload(
            customThemes = listOf(
                BackupCustomTheme("id", "Name", KeyboardTheme(), 0L),
            ),
        )
        assertFalse(customThemes.isSensitive)
    }
}
