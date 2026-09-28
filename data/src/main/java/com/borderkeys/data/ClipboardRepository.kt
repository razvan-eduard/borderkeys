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
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The live history, its cutoff recomputed whenever the retention setting changes. */
    @Suppress("OPT_IN_USAGE")
    val entries: Flow<List<ClipEntry>> = preferences.flatMapLatest { settings ->
        dao.observeLive(expiryCutoff(settings))
    }

    /** Remembers a copied image by its URI, not its bytes. */
    suspend fun rememberImage(uri: String, mimeType: String): Boolean {
        if (uri.isEmpty()) {
            return false
        }
        val settings = preferences.first()
        if (!settings.clipboardEnabled || !settings.clipboardImages) {
            return false
        }
        dao.upsert(
            content = uri,
            createdAt = now(),
            contentHash = contentHash(uri),
            uri = uri,
            mimeType = mimeType,
        )
        dao.trimUnpinnedTo(settings.clipboardMaxEntries)
        return true
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

    /** The most recent entries, newest first, read once. */
    suspend fun recent(limit: Int): List<ClipEntry> = entries.first().take(limit)

    suspend fun setPinned(id: Long, pinned: Boolean) {
        dao.setPinned(id, if (pinned) now() else null)
    }

    suspend fun delete(id: Long) = dao.delete(id)

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
        return true
    }

    suspend fun deleteAll() = dao.deleteAll()

    /** Forgets every remembered image. Called when the images switch is turned off. */
    suspend fun deleteImages(): Int = dao.deleteImages()

    /** Forgets everything unpinned, whatever its age. Called when the keyboard closes. */
    suspend fun deleteUnpinned(): Int = dao.deleteUnpinned()

    /** Deletes what the retention window has expired. */
    suspend fun purgeExpired(): Int {
        val settings = preferences.first()
        return dao.deleteExpired(expiryCutoff(settings))
    }

    private fun expiryCutoff(settings: KeyboardPreferences): Long =
        now() - settings.clipboardRetentionMinutes * 60_000L

    companion object {
        /** The first eight bytes of the content's SHA-256, as a signed long: the unique key. */
        fun contentHash(content: String): Long {
            val digest = MessageDigest.getInstance("SHA-256").digest(content.encodeToByteArray())
            var value = 0L
            for (index in 0 until 8) {
                value = (value shl 8) or (digest[index].toLong() and 0xFF)
            }
            return value
        }
    }
}
