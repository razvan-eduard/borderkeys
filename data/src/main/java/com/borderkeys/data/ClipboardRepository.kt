// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.dao.ClipboardDao
import com.borderkeys.data.entity.ClipEntry
import com.borderkeys.data.theme.KeyboardPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import java.security.MessageDigest

class ClipboardRepository internal constructor(
    private val dao: ClipboardDao,
    private val preferences: Flow<KeyboardPreferences>,
    /** Where a copied image's bytes are kept; none in a test. */
    private val media: ClipMediaFiles? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The live history, its cutoff recomputed whenever the retention setting changes. */
    @Suppress("OPT_IN_USAGE")
    val entries: Flow<List<ClipEntry>> = preferences.flatMapLatest { settings ->
        dao.observeLive(expiryCutoff(settings))
    }

    /** Why a copied image was not remembered. */
    enum class ImageRefusal { OFF, TOO_LARGE, NOT_AN_IMAGE }

    /** The most bytes a copied image may have, by the settings. */
    suspend fun imageCapBytes(): Long = ClipMedia.capBytes(preferences.first().clipboardImageMaxMb)

    /**
     * Remembers a copied image, or a screenshot when [fromScreenshot], by its bytes, deduplicated
     * by their hash: the same picture twice is one entry moved to the top. Null when remembered,
     * else why not.
     */
    suspend fun rememberImageBytes(bytes: ByteArray, mimeType: String, fromScreenshot: Boolean = false): ImageRefusal? {
        val settings = preferences.first()
        val kept = if (fromScreenshot) settings.screenshotsRemembered else settings.photosRemembered
        if (!settings.clipboardEnabled || !kept) {
            return ImageRefusal.OFF
        }
        if (bytes.isEmpty() || bytes.size.toLong() > ClipMedia.capBytes(settings.clipboardImageMaxMb)) {
            return ImageRefusal.TOO_LARGE
        }
        val stored = media?.store(bytes, mimeType) ?: return ImageRefusal.NOT_AN_IMAGE
        dao.upsert(
            content = "",
            createdAt = now(),
            contentHash = contentHash(bytes),
            uri = null,
            mimeType = mimeType,
            mediaFile = stored.name,
            sizeBytes = stored.sizeBytes,
            thumbnail = stored.thumbnail,
            fromScreenshot = fromScreenshot,
        )
        dao.trimUnpinnedTo(settings.clipboardMaxEntries)
        sweepMedia()
        return null
    }

    /** The bytes of the stored image [mediaFile], or null. */
    fun imageBytes(mediaFile: String): ByteArray? = media?.read(mediaFile)

    /** The entry keeping the stored image [mediaFile], or null. */
    suspend fun entryForMedia(mediaFile: String): ClipEntry? = dao.findByMediaFile(mediaFile)

    /** Deletes every stored image no entry refers to. */
    suspend fun sweepMedia() {
        media?.sweep(dao.mediaFiles().toSet())
    }

    /**
     * Records something the user copied; false when clipboard history is off. Re-copying an entry
     * moves it to the top instead of duplicating it.
     */
    suspend fun remember(content: String): Boolean {
        val settings = preferences.first()
        if (!settings.clipboardEnabled || content.isEmpty()) {
            return false
        }
        dao.upsert(
            content = content,
            createdAt = now(),
            contentHash = contentHash(content),
            uri = null,
            mimeType = null,
        )
        dao.trimUnpinnedTo(settings.clipboardMaxEntries)
        return true
    }

    /**
     * Keeps [content] privately: never on the system clipboard, with history on or off, exempt
     * from the retention window, the history limit and clearing on close; [sourcePackage] is the
     * app it was taken from. The same text kept again stays private. False for empty text.
     */
    suspend fun rememberPrivately(content: String, sourcePackage: String?): Boolean {
        if (content.isEmpty()) {
            return false
        }
        dao.upsert(
            content = content,
            createdAt = now(),
            contentHash = contentHash(content),
            uri = null,
            mimeType = null,
            isPrivate = true,
            sourcePackage = sourcePackage?.takeIf { it.isNotBlank() },
        )
        return true
    }

    /** The most recent entries, newest first, read once. */
    suspend fun recent(limit: Int): List<ClipEntry> = entries.first().take(limit)

    suspend fun setPinned(id: Long, pinned: Boolean) {
        dao.setPinned(id, if (pinned) now() else null)
    }

    suspend fun delete(id: Long) {
        dao.delete(id)
        sweepMedia()
    }

    /**
     * Rewrites a text entry. Another entry already holding the new text is removed first, so
     * the history keeps one row per content. False for empty text, an image or an unknown id.
     */
    suspend fun update(id: Long, content: String): Boolean {
        if (content.isEmpty()) {
            return false
        }
        val hash = contentHash(content)
        val other = dao.findByHash(hash)
        if (other != null && other.id != id) {
            dao.delete(other.id)
            sweepMedia()
        }
        return dao.updateContent(id, content, hash) > 0
    }

    /**
     * Deletes the entry matching [content], found by its hash, unless it is pinned. Returns
     * whether it was deleted.
     */
    suspend fun deleteIfUnpinned(content: String): Boolean {
        val entry = dao.findByHash(contentHash(content)) ?: return false
        if (entry.isPinned) {
            return false
        }
        dao.delete(entry.id)
        sweepMedia()
        return true
    }

    suspend fun deleteAll() {
        dao.deleteAll()
        sweepMedia()
    }

    /** Forgets every remembered copied image. Called when Remember photos is turned off. */
    suspend fun deleteCopiedImages(): Int {
        val deleted = dao.deleteCopiedImages()
        sweepMedia()
        return deleted
    }

    /** Forgets every kept screenshot. Called when Remember screenshots is turned off. */
    suspend fun deleteScreenshots(): Int {
        val deleted = dao.deleteScreenshots()
        sweepMedia()
        return deleted
    }

    /** Forgets everything unpinned, whatever its age. Called when the keyboard closes. */
    suspend fun deleteUnpinned(): Int {
        val deleted = dao.deleteUnpinned()
        sweepMedia()
        return deleted
    }

    /** Deletes what the retention window has expired. */
    suspend fun purgeExpired(): Int {
        val settings = preferences.first()
        val deleted = dao.deleteExpired(expiryCutoff(settings))
        sweepMedia()
        return deleted
    }

    private fun expiryCutoff(settings: KeyboardPreferences): Long =
        now() - settings.clipboardRetentionMinutes * 60_000L

    companion object {
        /** The first eight bytes of the content's SHA-256, as a signed long: the unique key. */
        fun contentHash(content: String): Long = contentHash(content.encodeToByteArray())

        /** The first eight bytes of the bytes' SHA-256, as a signed long: the unique key. */
        fun contentHash(content: ByteArray): Long {
            val digest = MessageDigest.getInstance("SHA-256").digest(content)
            var value = 0L
            for (index in 0 until 8) {
                value = (value shl 8) or (digest[index].toLong() and 0xFF)
            }
            return value
        }
    }
}
