// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.dao

import androidx.room.Dao
import androidx.room.Query
import com.borderkeys.data.entity.ClipEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardDao {

    /**
     * Everything still live: pinned entries always, unpinned ones only until they expire.
     *
     * The expiry is applied here, in the query, *and* by [deleteExpired] on a timer. Filtering
     * alone would leave the content sitting in the database indefinitely, visible to anything
     * that got hold of the file; deleting alone would show a stale entry in the fraction of a
     * second between it expiring and the sweep running. Doing both means what is on screen and
     * what is on disk agree.
     */
    @Query(
        """
        SELECT * FROM clip_entries
        WHERE pinnedAt IS NOT NULL OR createdAt >= :expiryCutoff
        ORDER BY pinnedAt IS NULL, COALESCE(pinnedAt, createdAt) DESC
        """,
    )
    fun observeLive(expiryCutoff: Long): Flow<List<ClipEntry>>

    @Query("SELECT * FROM clip_entries WHERE contentHash = :contentHash LIMIT 1")
    suspend fun findByHash(contentHash: Long): ClipEntry?

    /**
     * Inserts, or -- if something with this [contentHash] is already there -- just touches it.
     *
     * One atomic statement rather than [findByHash] followed by a separate insert-or-update: two
     * callers remembering the same content within the same moment (a clipboard listener and a
     * quick action's own copy, in close succession) can both pass a lookup's null check before
     * either has written anything, and a second unconditional insert would then hit the unique
     * index on [contentHash] and throw. This can't observe that gap -- SQLite resolves the
     * conflict inside the same statement that would have caused it, not after.
     *
     * Only `createdAt` is written on conflict. `pinnedAt` is left alone, because remembering
     * something again is not a reason to unpin it, and the row's `id` never changes underneath a
     * caller holding one.
     */
    @Query(
        """
        INSERT INTO clip_entries (content, createdAt, contentHash, uri, mimeType)
        VALUES (:content, :createdAt, :contentHash, :uri, :mimeType)
        ON CONFLICT(contentHash) DO UPDATE SET createdAt = :createdAt
        """,
    )
    suspend fun upsert(
        content: String,
        createdAt: Long,
        contentHash: Long,
        uri: String?,
        mimeType: String?,
    )

    /**
     * Inserts, or does nothing at all if something with this [contentHash] is already there.
     *
     * For backup restore, which wants the opposite of [upsert]'s touch-on-conflict: an entry
     * already present should be left exactly as it is, not have its timestamp moved to whatever
     * the backup happened to store. `INSERT OR IGNORE` makes that atomic the same way `upsert`
     * is -- the caller's own existence check is only for its own restored-count bookkeeping, not
     * what makes this safe to call from two restores racing each other.
     */
    @Query(
        """
        INSERT OR IGNORE INTO clip_entries (content, createdAt, pinnedAt, contentHash)
        VALUES (:content, :createdAt, :pinnedAt, :contentHash)
        """,
    )
    suspend fun insertIfAbsent(content: String, createdAt: Long, pinnedAt: Long?, contentHash: Long)

    @Query("UPDATE clip_entries SET pinnedAt = :pinnedAt WHERE id = :id")
    suspend fun setPinned(id: Long, pinnedAt: Long?)

    @Query("DELETE FROM clip_entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM clip_entries WHERE pinnedAt IS NULL AND createdAt < :expiryCutoff")
    suspend fun deleteExpired(expiryCutoff: Long): Int

    /** Everything, pinned included. The "clear clipboard history" button. */
    @Query("DELETE FROM clip_entries")
    suspend fun deleteAll()

    /** Drops every remembered image, pinned or not: switching the feature off means off. */
    @Query("DELETE FROM clip_entries WHERE uri IS NOT NULL")
    suspend fun deleteImages(): Int

    /** Everything that is not pinned, whatever its age. Used when the keyboard closes. */
    @Query("DELETE FROM clip_entries WHERE pinnedAt IS NULL")
    suspend fun deleteUnpinned(): Int

    @Query("SELECT COUNT(*) FROM clip_entries")
    suspend fun count(): Int

    /**
     * Drops the oldest unpinned entries once the history grows past [keep].
     *
     * A cap as well as a timer, because a retention of "forever" is a setting the user can
     * choose and an unbounded table in an encrypted database is still an unbounded table.
     */
    @Query(
        """
        DELETE FROM clip_entries WHERE id IN (
            SELECT id FROM clip_entries WHERE pinnedAt IS NULL
            ORDER BY createdAt DESC LIMIT -1 OFFSET :keep
        )
        """,
    )
    suspend fun trimUnpinnedTo(keep: Int): Int
}
