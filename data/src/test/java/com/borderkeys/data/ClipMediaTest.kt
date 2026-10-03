// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The names, limits and headers of the clipboard's stored images. */
class ClipMediaTest {

    private val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `the hash is the hex SHA-256`() {
        assertEquals(sha, ClipMedia.sha256("abc".encodeToByteArray()))
    }

    @Test
    fun `a stored image is named by its hash under a partition the hash picks`() {
        val name = ClipMedia.nameFor(sha, "png")
        assertEquals("${"%03d".format(0xba7 % ClipMedia.PARTITIONS)}/$sha.png", name)
        assertTrue(ClipMedia.isConfined(name))
    }

    @Test
    fun `nothing but a stored name is confined`() {
        assertFalse(ClipMedia.isConfined("../$sha.png"))
        assertFalse(ClipMedia.isConfined("000/../../x.png"))
        assertFalse(ClipMedia.isConfined("000/abc.png"))
        assertFalse(ClipMedia.isConfined("0000/$sha.png"))
        assertFalse(ClipMedia.isConfined("000/$sha"))
        assertFalse(ClipMedia.isConfined("000/$sha.PNG"))
    }

    @Test
    fun `the extension follows the type, and the type is read back from it`() {
        assertEquals("jpg", ClipMedia.extensionFor("image/jpeg"))
        assertEquals("png", ClipMedia.extensionFor("IMAGE/PNG; charset=binary"))
        assertNull(ClipMedia.extensionFor("text/plain"))
        assertNull(ClipMedia.extensionFor("image/svg+xml"))
        assertEquals("image/jpeg", ClipMedia.mimeTypeFor("123/$sha.jpg"))
        assertNull(ClipMedia.mimeTypeFor("123/$sha.exe"))
    }

    @Test
    fun `the cap is the setting in megabytes`() {
        assertEquals(10L * 1024 * 1024, ClipMedia.capBytes(10))
        assertEquals(1L * 1024 * 1024, ClipMedia.capBytes(1))
    }

    @Test
    fun `the sample size brings the longer side within twice the thumbnail`() {
        assertEquals(1, ClipMedia.thumbnailSampleSize(100, 80))
        assertEquals(1, ClipMedia.thumbnailSampleSize(160, 160))
        assertEquals(2, ClipMedia.thumbnailSampleSize(161, 100))
        assertEquals(16, ClipMedia.thumbnailSampleSize(1080, 1920))
    }

    @Test
    fun `an animated GIF or WebP is told from its header`() {
        val still = "GIF89a".encodeToByteArray() + ByteArray(64)
        val looping = "GIF89a".encodeToByteArray() + ByteArray(13) + byteArrayOf(0x21, 0xFF.toByte(), 0x0B) + "NETSCAPE2.0".encodeToByteArray()
        assertFalse(ClipMedia.isAnimated(still))
        assertTrue(ClipMedia.isAnimated(looping))

        val webp = ("RIFF" + "\u0000\u0000\u0000\u0000" + "WEBPVP8X" + "\u0000\u0000\u0000\u0000")
            .toByteArray(Charsets.ISO_8859_1) + ByteArray(10)
        assertFalse(ClipMedia.isAnimated(webp))
        val animated = webp.copyOf().also { it[20] = 0x02 }
        assertTrue(ClipMedia.isAnimated(animated))
        assertFalse(ClipMedia.isAnimated("\u0089PNG".toByteArray(Charsets.ISO_8859_1) + ByteArray(32)))
    }
}
