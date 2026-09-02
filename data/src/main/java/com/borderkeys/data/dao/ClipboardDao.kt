// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: ClipEntry): Long

    @Query("UPDATE clip_entries SET createdAt = :createdAt WHERE id = :id")
    suspend fun touch(id: Long, createdAt: Long)

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
