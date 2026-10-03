// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the clipboard's images are kept, as files, and what is kept about one. */
interface ClipMediaFiles {
    class Stored(val name: String, val sizeBytes: Long, val thumbnail: ByteArray?)

    /** Keeps [bytes] under their hash; the same bytes again return the same name. Null when the type is not kept. */
    fun store(bytes: ByteArray, mimeType: String): Stored?

    /** The bytes of [name], or null when it is not a stored image or cannot be read. */
    fun read(name: String): ByteArray?

    /** Deletes every stored image whose name is not in [referenced]. */
    fun sweep(referenced: Set<String>)
}

/**
 * The clipboard's images under `files/clipboard_media`, each encrypted with AES-256-GCM under a
 * key the Android Keystore holds: twelve bytes of nonce, then the ciphertext. Names come from
 * [ClipMedia]; a name that is not confined to the directory is refused.
 */
class ClipMediaStore(context: Context) : ClipMediaFiles {

    private val context = context.applicationContext
    private val directory: File get() = File(context.filesDir, ClipMedia.DIRECTORY)

    override fun store(bytes: ByteArray, mimeType: String): ClipMediaFiles.Stored? {
        val extension = ClipMedia.extensionFor(mimeType) ?: return null
        val name = ClipMedia.nameFor(ClipMedia.sha256(bytes), extension)
        val file = fileFor(name) ?: return null
        if (!file.isFile) {
            file.parentFile?.mkdirs()
            val written = runCatching {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key())
                file.outputStream().use { out ->
                    out.write(cipher.iv)
                    out.write(cipher.doFinal(bytes))
                }
            }.isSuccess
            if (!written) {
                file.delete()
                return null
            }
        }
        return ClipMediaFiles.Stored(name, bytes.size.toLong(), thumbnailOf(bytes))
    }

    override fun read(name: String): ByteArray? {
        val file = fileFor(name) ?: return null
        if (!file.isFile) {
            return null
        }
        return runCatching {
            val stored = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, key(),
                GCMParameterSpec(TAG_BITS, stored, 0, NONCE_BYTES),
            )
            cipher.doFinal(stored, NONCE_BYTES, stored.size - NONCE_BYTES)
        }.getOrNull()
    }

    override fun sweep(referenced: Set<String>) {
        val root = directory
        root.listFiles()?.forEach { partition ->
            partition.listFiles()?.forEach { file ->
                if ("${partition.name}/${file.name}" !in referenced) {
                    file.delete()
                }
            }
            if (partition.list()?.isEmpty() == true) {
                partition.delete()
            }
        }
    }

    /** Deletes every stored image. */
    fun deleteAll() {
        directory.deleteRecursively()
    }

    /** The file [name] resolves to, or null when the name is not confined to the directory. */
    private fun fileFor(name: String): File? =
        if (ClipMedia.isConfined(name)) File(directory, name) else null

    /**
     * An [ClipMedia.THUMBNAIL_PX] square WebP of the picture's centre, re-encoded at lower quality
     * until it fits [ClipMedia.THUMBNAIL_MAX_BYTES]; null when the bytes do not decode.
     */
    private fun thumbnailOf(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = ClipMedia.thumbnailSampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(
            decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side,
        )
        val scaled = Bitmap.createScaledBitmap(square, ClipMedia.THUMBNAIL_PX, ClipMedia.THUMBNAIL_PX, true)
        var quality = 80
        var encoded: ByteArray
        do {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
            encoded = out.toByteArray()
            quality -= 20
        } while (encoded.size > ClipMedia.THUMBNAIL_MAX_BYTES && quality > 0)
        if (scaled !== square) scaled.recycle()
        if (square !== decoded) square.recycle()
        decoded.recycle()
        return encoded.takeIf { it.size <= ClipMedia.THUMBNAIL_MAX_BYTES }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "borderkeys_clip_media"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
