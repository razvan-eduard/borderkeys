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

/**
 * The file somebody's dictionary travels in.
 *
 * Worth testing at this level because every failure here is quiet on a device: a file that
 * imports as rubbish, a passphrase that is not really protecting anything, and a wrong
 * passphrase reported as a damaged file all look the same from the outside.
 */
class BackupFileTest {

    private val sensitive = BackupPayload(
        words = listOf(BackupWord("cana", "ro-RO", 12)),
        bigrams = listOf(BackupBigram("o", "cana", 4)),
    )

    private val settingsOnly = BackupPayload(
        preferences = KeyboardPreferences(numberRow = true),
        theme = KeyboardTheme(backgroundColor = 0xFF102030.toInt()),
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
        // Which model was active, not the model itself -- a hash and a name are not a body of
        // learned or copied text, so this travels with settings rather than needing its own
        // sensitivity check.
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
            .replace("\"format\":1", "\"format\":99")
        assertEquals(BackupFile.Failure.NOT_A_BACKUP, BackupFile.read(text, "").failure)
    }

    @Test
    fun `a tampered payload is caught rather than decrypted into rubbish`() {
        val text = BackupFile.write(sensitive, passphrase = "correct horse")
        // One character flipped inside the ciphertext. AES-GCM authenticates before it
        // decrypts, so this has to fail rather than produce something that parses.
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
    fun `the clipboard counts as private too`() {
        val clips = BackupPayload(clips = listOf(BackupClip("a card number", 1L, false)))
        assertTrue(clips.isSensitive)
    }
}
