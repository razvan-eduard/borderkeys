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
 * Each one is word and pair counts from the Wortschatz Leipzig corpora (CC BY 4.0; what and how
 * much is recorded in `docs/licensing.md` section 2.1) compiled in this repository -- a licence
 * question with a written answer, which is what lets a corpus-sized pack be bundled at all.
 * Replacing one with a pack built from another corpus is an import away, and the entry
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
        // Recounted after tools/drop_foreign.py removed each list's foreign vocabulary: between
        // 4% and 6% of every pack was another language's words, counted as this one's because
        // the corpus they came from quotes them (see docs/dictionaries.md). Nothing the language
        // itself uses went with them -- loanwords like "mouse" and "weekend" stayed, and so did
        // every name. The counts here are read from the compiled headers, not estimated; they
        // have to match, because repairBundledPacks treats a pack whose recorded count or size
        // differs as stale and re-copies it on every start.
        Entry("ro-RO", "Romanian", "dict/ro_RO.bkd", "ro_RO.bkd", 105_802, 15_855_421),
        Entry("en-US", "English", "dict/en_US.bkd", "en_US.bkd", 127_139, 21_779_087),
        Entry("es-ES", "Spanish", "dict/es_ES.bkd", "es_ES.bkd", 96_699, 15_559_887),
        Entry("fr-FR", "French", "dict/fr_FR.bkd", "fr_FR.bkd", 96_604, 15_826_664),
        Entry("de-DE", "German", "dict/de_DE.bkd", "de_DE.bkd", 99_862, 21_207_228),
        Entry("it-IT", "Italian", "dict/it_IT.bkd", "it_IT.bkd", 99_086, 15_890_712),
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
