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
     * Everything still live: pinned entries always, unpinned ones until they expire.
     * [deleteExpired] removes the expired rows.
     */
    @Query(
        """
        SELECT * FROM clip_entries
        WHERE pinnedAt IS NOT NULL OR isPrivate = 1 OR createdAt >= :expiryCutoff
        ORDER BY pinnedAt IS NULL, COALESCE(pinnedAt, createdAt) DESC
        """,
    )
    fun observeLive(expiryCutoff: Long): Flow<List<ClipEntry>>

    @Query("SELECT * FROM clip_entries WHERE contentHash = :contentHash LIMIT 1")
    suspend fun findByHash(contentHash: Long): ClipEntry?

    /** Rewrites a text entry's content and hash. An image entry is left alone. */
    @Query(
        """
        UPDATE clip_entries SET content = :content, contentHash = :contentHash
        WHERE id = :id AND uri IS NULL AND mediaFile IS NULL
        """,
    )
    suspend fun updateContent(id: Long, content: String, contentHash: Long): Int

    /**
     * Inserts, or, when something with this [contentHash] is already there, sets its `createdAt`,
     * keeps it private if either is, and fills a missing source, in one statement. `pinnedAt` and
     * the row's `id` are kept.
     */
    @Query(
        """
        INSERT INTO clip_entries (content, createdAt, contentHash, uri, mimeType, mediaFile, sizeBytes, thumbnail, isPrivate, sourcePackage)
        VALUES (:content, :createdAt, :contentHash, :uri, :mimeType, :mediaFile, :sizeBytes, :thumbnail, :isPrivate, :sourcePackage)
        ON CONFLICT(contentHash) DO UPDATE SET
            createdAt = :createdAt,
            isPrivate = MAX(isPrivate, :isPrivate),
            sourcePackage = COALESCE(sourcePackage, :sourcePackage)
        """,
    )
    suspend fun upsert(
        content: String,
        createdAt: Long,
        contentHash: Long,
        uri: String?,
        mimeType: String?,
        mediaFile: String? = null,
        sizeBytes: Long = 0L,
        thumbnail: ByteArray? = null,
        isPrivate: Boolean = false,
        sourcePackage: String? = null,
    )

    /** The stored images' names, for the sweep. */
    @Query("SELECT mediaFile FROM clip_entries WHERE mediaFile IS NOT NULL")
    suspend fun mediaFiles(): List<String>

    @Query("SELECT * FROM clip_entries WHERE mediaFile = :mediaFile LIMIT 1")
    suspend fun findByMediaFile(mediaFile: String): ClipEntry?

    /**
     * Inserts, or does nothing when something with this [contentHash] is already there, in one
     * statement. For backup restore.
     */
    @Query(
        """
        INSERT OR IGNORE INTO clip_entries (content, createdAt, pinnedAt, contentHash, isPrivate)
        VALUES (:content, :createdAt, :pinnedAt, :contentHash, :isPrivate)
        """,
    )
    suspend fun insertIfAbsent(
        content: String,
        createdAt: Long,
        pinnedAt: Long?,
        contentHash: Long,
        isPrivate: Boolean = false,
    )

    @Query("UPDATE clip_entries SET pinnedAt = :pinnedAt WHERE id = :id")
    suspend fun setPinned(id: Long, pinnedAt: Long?)

    @Query("DELETE FROM clip_entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM clip_entries WHERE pinnedAt IS NULL AND isPrivate = 0 AND createdAt < :expiryCutoff")
    suspend fun deleteExpired(expiryCutoff: Long): Int

    /** Everything, pinned included. The "clear clipboard history" button. */
    @Query("DELETE FROM clip_entries")
    suspend fun deleteAll()

    /** Drops every remembered image, pinned or not: switching the feature off means off. */
    @Query("DELETE FROM clip_entries WHERE uri IS NOT NULL OR mediaFile IS NOT NULL")
    suspend fun deleteImages(): Int

    /** Everything neither pinned nor private, whatever its age. Used when the keyboard closes. */
    @Query("DELETE FROM clip_entries WHERE pinnedAt IS NULL AND isPrivate = 0")
    suspend fun deleteUnpinned(): Int

    @Query("SELECT COUNT(*) FROM clip_entries")
    suspend fun count(): Int

    /** Drops the oldest entries neither pinned nor private once the history grows past [keep]. */
    @Query(
        """
        DELETE FROM clip_entries WHERE id IN (
            SELECT id FROM clip_entries WHERE pinnedAt IS NULL AND isPrivate = 0
            ORDER BY createdAt DESC LIMIT -1 OFFSET :keep
        )
        """,
    )
    suspend fun trimUnpinnedTo(keep: Int): Int
}
