// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One remembered clipboard item. [contentHash] is the first eight bytes of the content's SHA-256,
 * under a unique index.
 */
@Entity(
    tableName = "clip_entries",
    indices = [
        Index(value = ["contentHash"], unique = true),
        Index(value = ["createdAt"]),
    ],
)
data class ClipEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val content: String,
    val createdAt: Long,
    /** Null while the entry is subject to expiry. A pinned entry is never deleted on a timer. */
    val pinnedAt: Long? = null,
    val contentHash: Long,

    /** The content URI of a copied image, or null for text. */
    val uri: String? = null,

    /** The clip's MIME type, so the panel knows what it is looking at without guessing. */
    val mimeType: String? = null,
) {
    val isPinned: Boolean get() = pinnedAt != null

    /** True when this entry is an image rather than text. */
    val isImage: Boolean get() = uri != null && mimeType?.startsWith("image/") == true
}
