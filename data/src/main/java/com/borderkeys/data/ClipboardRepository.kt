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
    /**
     * The live history, re-evaluated whenever the retention setting changes.
     *
     * The cutoff is computed when the flow is collected rather than baked in once, so shortening
     * the retention window in Settings takes effect on the next emission instead of at the next
     * process start.
     */
    @Suppress("OPT_IN_USAGE")
    val entries: Flow<List<ClipEntry>> = preferences.flatMapLatest { settings ->
        dao.observeLive(expiryCutoff(settings))
    }

    /**
     * Records something the user copied. Returns false when clipboard history is switched off,
     * or when the caller is in private mode and should not have called at all.
     *
     * Re-copying an existing entry moves it to the top instead of duplicating it. The unique
     * index on the hash is what guarantees that, and the insert is attempted first so that the
     * common case is one statement rather than a lookup followed by one.
     */
    suspend fun remember(content: String): Boolean {
        val settings = preferences.first()
        if (!settings.clipboardEnabled || content.isEmpty()) {
            return false
        }
        val timestamp = now()
        val hash = contentHash(content)
        val existing = dao.findByHash(hash)
        if (existing != null) {
            dao.touch(existing.id, timestamp)
        } else {
            dao.insert(
                ClipEntry(content = content, createdAt = timestamp, contentHash = hash),
            )
        }
        dao.trimUnpinnedTo(settings.clipboardMaxEntries)
        return true
    }

    /**
     * The most recent entries, newest first, for the keyboard's own strip.
     *
     * A one-shot read rather than the flow the settings screen collects: the strip is answering
     * a button press, not tracking the clipboard, and a subscription that outlives the press
     * would keep the database open for a row that has already been replaced by suggestions.
     */
    suspend fun recent(limit: Int): List<String> =
        entries.first().take(limit).map { it.content }

    suspend fun setPinned(id: Long, pinned: Boolean) {
        dao.setPinned(id, if (pinned) now() else null)
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun deleteAll() = dao.deleteAll()

    /**
     * Deletes what the retention window has expired.
     *
     * Filtering in the query is what the user sees; this is what actually removes the bytes. Run
     * on a timer and at `onFinishInput`, so an expired password does not sit in the file for the
     * hours between one keyboard session and the next.
     */
    suspend fun purgeExpired(): Int {
        val settings = preferences.first()
        return dao.deleteExpired(expiryCutoff(settings))
    }

    private fun expiryCutoff(settings: KeyboardPreferences): Long =
        now() - settings.clipboardRetentionMinutes * 60_000L

    companion object {
        /**
         * The first eight bytes of the SHA-256 of the content, as a signed long.
         *
         * SHA-256 truncated rather than [String.hashCode]: a collision here is not a wrong
         * answer, it is a clipboard entry that silently never gets stored because the unique
         * index thinks it is already there. String.hashCode collides on short inputs often
         * enough to hit that in normal use.
         */
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
