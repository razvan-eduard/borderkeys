// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import android.content.res.AssetManager
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The dictionaries that ship inside the application: word and pair counts from the Wortschatz
 * Leipzig corpora (CC BY 4.0; see `docs/licensing.md` section 2.1). Choosing one copies it out of
 * the APK through the same install path as a file the user picks.
 */
object BundledDictionaries {

    /**
     * One dictionary in the APK. [wordCount] and [sizeBytes] are the compiled pack's, for its
     * label; [contentCrc] tells an installed copy apart from it.
     */
    data class Entry(
        val tag: String,
        val displayName: String,
        val assetPath: String,
        val fileName: String,
        val wordCount: Int,
        val sizeBytes: Long,
    )

    val ALL: List<Entry> = listOf(
        // Read from the compiled headers; BundledPackMetadataTest checks them.
        Entry("ro-RO", "Romanian", "dict/ro_RO.bkd", "ro_RO.bkd", 108_246, 8_250_812),
        Entry("en-US", "English", "dict/en_US.bkd", "en_US.bkd", 119_062, 9_744_124),
        Entry("es-ES", "Spanish", "dict/es_ES.bkd", "es_ES.bkd", 97_406, 8_016_384),
        Entry("fr-FR", "French", "dict/fr_FR.bkd", "fr_FR.bkd", 93_966, 7_897_344),
        Entry("de-DE", "German", "dict/de_DE.bkd", "de_DE.bkd", 100_737, 9_184_772),
        Entry("it-IT", "Italian", "dict/it_IT.bkd", "it_IT.bkd", 95_592, 8_218_768),
    )

    /** Opens one for reading. The caller closes it; the install path copies and validates. */
    fun open(assets: AssetManager, entry: Entry) = assets.open(entry.assetPath)

    /**
     * The CRC a pack's header carries over everything past the header (`contentCrc32` in
     * `bkd_format.hpp`), read from the first bytes of [stream]. Null for a stream too short to be
     * a pack or one without the magic.
     */
    fun contentCrc(stream: InputStream): Int? {
        val header = ByteArray(HEADER_PREFIX_BYTES)
        var read = 0
        while (read < header.size) {
            val count = stream.read(header, read, header.size - read)
            if (count < 0) return null
            read += count
        }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getInt(0) != MAGIC) return null
        return buffer.getInt(CONTENT_CRC_OFFSET)
    }

    // The fixed head of BkdHeader: magic, version, header size, flags, file size (8), then
    // the content CRC. Nothing past it is needed here.
    private const val HEADER_PREFIX_BYTES = 32
    private const val CONTENT_CRC_OFFSET = 24
    private const val MAGIC = 0x31444B42
}
