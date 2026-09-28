// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.backup

import com.borderkeys.data.theme.KeyboardPlacement
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardTheme
import com.borderkeys.data.theme.ParticleEffectsSettings
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What one keyboard can hand to another, as a file the user picks; also the application's only
 * backup.
 */
@Serializable
data class BackupPayload(
    val preferences: KeyboardPreferences? = null,
    val theme: KeyboardTheme? = null,
    /**
     * Named themes the user saved, each a
     * [CustomThemeEntry][com.borderkeys.data.theme.CustomThemeEntry]; importing adds to those
     * already saved.
     */
    val customThemes: List<BackupCustomTheme> = emptyList(),
    val particleEffects: ParticleEffectsSettings? = null,
    val sizeAndPosition: BackupSizeAndPosition? = null,
    /** Which languages are on and how heavily they count. Not the packs; those are in the APK. */
    val packs: List<BackupPack> = emptyList(),
    val words: List<BackupWord> = emptyList(),
    val bigrams: List<BackupBigram> = emptyList(),
    val trigrams: List<BackupTrigram> = emptyList(),
    val blocked: List<String> = emptyList(),
    val clips: List<BackupClip> = emptyList(),
    /**
     * Which imported text-assistant model was active, not the model itself; a restore reactivates
     * a model the device already has.
     */
    val models: List<BackupModel> = emptyList(),
) {
    /** Whether this carries the dictionary or the clipboard, which ask for encryption. */
    val isSensitive: Boolean
        get() = words.isNotEmpty() || bigrams.isNotEmpty() || trigrams.isNotEmpty() ||
            clips.isNotEmpty()
}

@Serializable
data class BackupCustomTheme(val id: String, val name: String, val theme: KeyboardTheme, val createdAt: Long)

/**
 * Both orientations' size and position, as [KeyboardPreferences.placementFor] gives them,
 * carried apart from [BackupPayload.preferences].
 */
@Serializable
data class BackupSizeAndPosition(val portrait: KeyboardPlacement, val landscape: KeyboardPlacement)

@Serializable
data class BackupPack(val tag: String, val enabled: Boolean, val weight: Float)

/**
 * One learned word. [lastUsedAt], [deliberateCapitals] and [asserted] default to "unknown" (0)
 * so a file without them still reads: a restore then stamps the word as used now. See
 * `UserWord.deliberateCapitals` and `UserWord.asserted`.
 */
@Serializable
data class BackupWord(
    val word: String,
    val locale: String,
    val count: Int,
    val lastUsedAt: Long = 0L,
    val deliberateCapitals: Int = 0,
    val asserted: Int = 0,
)

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

@Serializable
data class BackupModel(val fileName: String, val sha256: String, val active: Boolean)

/** The envelope on disk: what this is, and how to read the rest of it. */
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

    /** The name the picker is offered, without an extension; the system adds ".json". */
    const val SUGGESTED_NAME = "borderkeys-backup"
    const val MIME_TYPE = "application/json"

    private const val APPLICATION = "borderkeys"

    /** PBKDF2-HMAC-SHA256 rounds, the figure OWASP gives for it. */
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
     * Writes [payload], encrypted when [passphrase] is not empty; [BackupPayload.isSensitive] tells
     * the caller when to ask for one.
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

    /** Reads a file back; each way it can fail is its own [Failure]. */
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

    // java.util's encoder, which also runs off a device.
    private fun encode(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)
}
