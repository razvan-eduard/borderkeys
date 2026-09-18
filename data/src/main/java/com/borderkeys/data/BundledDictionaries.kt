// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import android.content.res.AssetManager
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The dictionaries that ship inside the application.
 *
 * They are in the APK, not on a server: this application has no `INTERNET` permission and a
 * build gate that fails the build if one ever appears, so "download a language" cannot mean what
 * it means elsewhere. Choosing a language here copies a file out of the APK into private
 * storage, which is the same install path a file the user picked goes through -- the pack is
 * validated by the same native code, recorded in the same table, and can be weighted, disabled
 * and removed the same way.
 *
 * They are also deliberately small. A starter dictionary written by this project is a licence
 * question with an answer; a large corpus is one without, which is the whole reason nothing was
 * bundled before. Replacing one with a proper compiled corpus is an import away, and the entry
 * disappears from the list once its language is installed.
 */
object BundledDictionaries {

    /**
     * One dictionary in the APK.
     *
     * [wordCount] is recorded here rather than read from the pack: the list is shown before
     * anything is opened, and opening a pack to fill in a label would checksum a megabyte to
     * draw a line of text. The build compiles these from the `.tsv` files in `dictionaries`, so a number that
     * drifted would be a stale constant rather than a wrong pack.
     *
     * [sizeBytes] is the compiled size, for the same label. It used to be what told an
     * installed copy apart from the pack this build ships, until two lists whose words only
     * gained or lost name flags compiled to the same count and, by section alignment, the same
     * size; [contentCrc] is that check now. The build prints both numbers; they are copied
     * here together.
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
        Entry("ro-RO", "Romanian", "dict/ro_RO.bkd", "ro_RO.bkd", 108_980, 15_962_657),
        Entry("en-US", "English", "dict/en_US.bkd", "en_US.bkd", 129_071, 21_847_639),
        Entry("es-ES", "Spanish", "dict/es_ES.bkd", "es_ES.bkd", 100_005, 15_669_683),
        Entry("fr-FR", "French", "dict/fr_FR.bkd", "fr_FR.bkd", 99_083, 15_906_382),
        Entry("de-DE", "German", "dict/de_DE.bkd", "de_DE.bkd", 102_374, 21_292_316),
        Entry("it-IT", "Italian", "dict/it_IT.bkd", "it_IT.bkd", 102_135, 15_991_754),
    )

    /** Opens one for reading. The caller closes it; the install path copies and validates. */
    fun open(assets: AssetManager, entry: Entry) = assets.open(entry.assetPath)

    /**
     * The CRC a pack's header carries over everything past the header (`contentCrc32` in
     * `bkd_format.hpp`), read from the first bytes of [stream] without opening the pack. This
     * is what tells an installed copy apart from the pack this build ships: two editions of a
     * word list can compile to the same word count and the same size -- the German and Italian
     * lists did, when only name flags changed -- and never to the same CRC. Null for a stream
     * too short to be a pack or one without the magic; the install path validates properly.
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
