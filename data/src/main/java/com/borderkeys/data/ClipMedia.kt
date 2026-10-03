// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import java.security.MessageDigest

/**
 * The names, limits and headers of the images the clipboard keeps as bytes; nothing here touches
 * a file or a bitmap. A stored image is named by its SHA-256 and its extension, under one of
 * [PARTITIONS] directories.
 */
object ClipMedia {

    const val DIRECTORY = "clipboard_media"
    const val PARTITIONS = 1000

    /** The thumbnail's side, in pixels, and the most bytes it may take. */
    const val THUMBNAIL_PX = 80
    const val THUMBNAIL_MAX_BYTES = 10 * 1024

    /** A stored image's name: `partition/sha256.extension`. */
    private val name = Regex("""^\d{3}/[0-9a-f]{64}\.[a-z0-9]{2,5}$""")

    private val extensions = mapOf(
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/bmp" to "bmp",
        "image/heic" to "heic",
        "image/heif" to "heif",
        "image/avif" to "avif",
    )

    private val hex = "0123456789abcdef".toCharArray()

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = CharArray(digest.size * 2)
        for ((index, byte) in digest.withIndex()) {
            out[index * 2] = hex[(byte.toInt() shr 4) and 0xF]
            out[index * 2 + 1] = hex[byte.toInt() and 0xF]
        }
        return String(out)
    }

    /** The extension for [mimeType], or null for a type that is not kept. */
    fun extensionFor(mimeType: String): String? = extensions[mimeType.lowercase().substringBefore(';').trim()]

    /** The type a stored name was kept under, from its extension. */
    fun mimeTypeFor(name: String): String? {
        val extension = name.substringAfterLast('.', "")
        return extensions.entries.firstOrNull { it.value == extension }?.key
    }

    /** `partition/sha256.extension`, the partition the hash's first three hex digits mod [PARTITIONS]. */
    fun nameFor(sha256: String, extension: String): String {
        val partition = sha256.take(3).toInt(16) % PARTITIONS
        return "%03d/%s.%s".format(partition, sha256, extension)
    }

    /** Whether [name] is a stored image's name and nothing else: no path above the directory. */
    fun isConfined(name: String): Boolean = this.name.matches(name)

    /** The most bytes an image may have at [maxMegabytes]. */
    fun capBytes(maxMegabytes: Int): Long = maxMegabytes.toLong() * 1024L * 1024L

    /** The power of two that brings a [width] by [height] picture to at most [THUMBNAIL_PX] on its longer side, times two. */
    fun thumbnailSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > THUMBNAIL_PX * 2) {
            sample *= 2
        }
        return sample
    }

    /**
     * Whether the bytes are an animated GIF (a NETSCAPE2.0 application extension) or an animated
     * WebP (the VP8X chunk's animation flag).
     */
    fun isAnimated(bytes: ByteArray): Boolean {
        if (bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte()
        ) {
            val needle = "NETSCAPE2.0".encodeToByteArray()
            val limit = minOf(bytes.size, 64 * 1024) - needle.size
            for (start in 0..limit) {
                var matched = true
                for (offset in needle.indices) {
                    if (bytes[start + offset] != needle[offset]) {
                        matched = false
                        break
                    }
                }
                if (matched) {
                    return true
                }
            }
            return false
        }
        if (bytes.size >= 21 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() &&
            bytes[12] == 'V'.code.toByte() && bytes[13] == 'P'.code.toByte() &&
            bytes[14] == '8'.code.toByte() && bytes[15] == 'X'.code.toByte()
        ) {
            return (bytes[20].toInt() and 0x02) != 0
        }
        return false
    }
}
