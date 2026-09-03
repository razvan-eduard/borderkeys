// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.backup

import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardTheme
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What one keyboard can hand to another, as a file.
 *
 * The two builds are separate applications with separate private directories, and neither can
 * read the other's. The alternative to a file would be a content provider in one of them and a
 * signature permission in the other -- and then the second build requests a permission, which is
 * listed wherever it is published, and "no permissions at all" stops being true of it. A file
 * the user picks costs a few more taps and no claims.
 *
 * It doubles as the only backup this application has. System backup is switched off deliberately
 * (`allowBackup="false"`), because that is an automatic copy to somebody else's computer. This is
 * the opposite: nothing happens unless it is asked for, and it goes where the user says.
 */
@Serializable
data class BackupPayload(
    val preferences: KeyboardPreferences? = null,
    val theme: KeyboardTheme? = null,
    /** Which languages are on and how heavily they count. Not the packs; those are in the APK. */
    val packs: List<BackupPack> = emptyList(),
    val words: List<BackupWord> = emptyList(),
    val bigrams: List<BackupBigram> = emptyList(),
    val trigrams: List<BackupTrigram> = emptyList(),
    val blocked: List<String> = emptyList(),
    val clips: List<BackupClip> = emptyList(),
) {
    /**
     * Whether this carries anything that would be a loss to leave lying around.
     *
     * The dictionary is every word this device learned from what its owner typed, and the
     * clipboard is whatever they last copied. Both live in an encrypted database for that
     * reason, so a file carrying either is asked to be encrypted too. A theme is not.
     */
    val isSensitive: Boolean
        get() = words.isNotEmpty() || bigrams.isNotEmpty() || trigrams.isNotEmpty() ||
            clips.isNotEmpty()
}

@Serializable
data class BackupPack(val tag: String, val enabled: Boolean, val weight: Float)

@Serializable
data class BackupWord(val word: String, val locale: String, val count: Int)

@Serializable
data class BackupBigram(val previous: String, val word: String, val count: Int)

@Serializable
data class BackupTrigram(
    val previous2: String,
    val previous1: String,
    val word: String,
    val count: Int,
)

@Serializable
data class BackupClip(val content: String, val createdAt: Long, val pinned: Boolean)

/**
 * The envelope on disk: what this is, and how to read the rest of it.
 *
 * Self-describing on purpose. Somebody who finds one of these years from now should be able to
 * tell from the first line what it is and whether they need a passphrase, without the
 * application that wrote it.
 */
@Serializable
private data class Envelope(
    val format: Int,
    val application: String,
    val encrypted: Boolean,
    val salt: String = "",
    val iv: String = "",
    val iterations: Int = 0,
    /** The payload's JSON, base64 encoded; ciphertext when [encrypted]. */
    val payload: String,
)

/** Reading and writing the file. Everything here is pure; the caller owns the streams. */
object BackupFile {

    /** Why an import stopped, in the terms a person could be told. */
    enum class Failure { NOT_A_BACKUP, WRONG_PASSPHRASE, NEEDS_PASSPHRASE, DAMAGED }

    class Result private constructor(val payload: BackupPayload?, val failure: Failure?) {
        companion object {
            fun of(payload: BackupPayload) = Result(payload, null)
            fun failed(failure: Failure) = Result(null, failure)
        }
    }

    const val FORMAT = 1

    /**
     * The name the picker is offered, without an extension.
     *
     * The system appends one from the type, so anything ending in ".bkbackup" came back as
     * "borderkeys.bkbackup.json". The file really is JSON -- the envelope is readable even when
     * the payload is not -- so the honest name is the one the system would have given it, and
     * the base carries the identification instead.
     */
    const val SUGGESTED_NAME = "borderkeys-backup"
    const val MIME_TYPE = "application/json"

    private const val APPLICATION = "borderkeys"

    /**
     * Deliberately slow, and the reason is the passphrase.
     *
     * People choose short ones. The only defence against somebody trying every short one is to
     * make each attempt cost something, and 210,000 rounds of PBKDF2-HMAC-SHA256 is the figure
     * OWASP gives for that construction. It costs about a fifth of a second here and years to
     * anyone working through a dictionary of guesses.
     */
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Writes [payload], encrypting it when [passphrase] is not empty.
     *
     * The caller decides: a payload carrying the dictionary or the clipboard should not be
     * written without one, and [BackupPayload.isSensitive] is how it decides.
     */
    fun write(payload: BackupPayload, passphrase: String): String {
        val plain = json.encodeToString(BackupPayload.serializer(), payload).toByteArray()
        if (passphrase.isEmpty()) {
            return json.encodeToString(
                Envelope.serializer(),
                Envelope(
                    format = FORMAT,
                    application = APPLICATION,
                    encrypted = false,
                    payload = encode(plain),
                ),
            )
        }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyFrom(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        return json.encodeToString(
            Envelope.serializer(),
            Envelope(
                format = FORMAT,
                application = APPLICATION,
                encrypted = true,
                salt = encode(salt),
                iv = encode(iv),
                iterations = ITERATIONS,
                payload = encode(cipher.doFinal(plain)),
            ),
        )
    }

    /**
     * Reads a file back.
     *
     * Every way this can fail is a distinct answer, because "could not import" tells somebody
     * nothing about whether to try a different passphrase or a different file. A wrong
     * passphrase is told apart from a damaged file by the authentication tag: AES-GCM verifies
     * before it decrypts, so a bad key and a flipped bit both throw, and neither can hand back
     * plausible rubbish.
     */
    fun read(text: String, passphrase: String): Result {
        val envelope = runCatching { json.decodeFromString(Envelope.serializer(), text) }
            .getOrNull()
            ?: return Result.failed(Failure.NOT_A_BACKUP)
        if (envelope.application != APPLICATION || envelope.format > FORMAT) {
            return Result.failed(Failure.NOT_A_BACKUP)
        }
        val bytes = runCatching { decode(envelope.payload) }.getOrNull()
            ?: return Result.failed(Failure.DAMAGED)

        val plain = if (!envelope.encrypted) {
            bytes
        } else {
            if (passphrase.isEmpty()) {
                return Result.failed(Failure.NEEDS_PASSPHRASE)
            }
            val salt = runCatching { decode(envelope.salt) }.getOrNull()
                ?: return Result.failed(Failure.DAMAGED)
            val iv = runCatching { decode(envelope.iv) }.getOrNull()
                ?: return Result.failed(Failure.DAMAGED)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            runCatching {
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    keyFrom(passphrase, salt, envelope.iterations),
                    GCMParameterSpec(TAG_BITS, iv),
                )
                cipher.doFinal(bytes)
            }.getOrNull() ?: return Result.failed(Failure.WRONG_PASSPHRASE)
        }

        val payload = runCatching {
            json.decodeFromString(BackupPayload.serializer(), String(plain))
        }.getOrNull() ?: return Result.failed(Failure.DAMAGED)
        return Result.of(payload)
    }

    /** Whether a file will want a passphrase, so the screen can ask before it reads. */
    fun needsPassphrase(text: String): Boolean =
        runCatching { json.decodeFromString(Envelope.serializer(), text).encrypted }
            .getOrDefault(false)

    private fun keyFrom(passphrase: String, salt: ByteArray, iterations: Int = ITERATIONS) =
        SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(
                    PBEKeySpec(
                        passphrase.toCharArray(),
                        salt,
                        iterations.coerceIn(1, ITERATIONS),
                        KEY_BITS,
                    ),
                )
                .encoded,
            "AES",
        )

    // java.util rather than android.util: the platform's encoder is a stub outside a device,
    // which would make this class the one thing in the file that could not be tested. minSdk is
    // 30 and this has been in the language since 8.
    private fun encode(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)
}
