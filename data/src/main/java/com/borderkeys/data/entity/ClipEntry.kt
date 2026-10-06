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

    /** The content URI a copied image was remembered by before its bytes were kept, or null. */
    val uri: String? = null,

    /** The clip's MIME type, so the panel knows what it is looking at without guessing. */
    val mimeType: String? = null,

    /** The stored image's name in the media store, `partition/sha256.extension`, or null. */
    val mediaFile: String? = null,

    /** The stored image's size in bytes; 0 for text. */
    val sizeBytes: Long = 0L,

    /** The stored image's thumbnail, a small WebP, or null. */
    val thumbnail: ByteArray? = null,

    /**
     * Kept privately: never on the system clipboard, and exempt from the retention window, the
     * history limit and clearing on close.
     */
    val isPrivate: Boolean = false,

    /** The package the private copy was made in, or null. */
    val sourcePackage: String? = null,

    /** A screenshot the keyboard kept, not a copied image; one also copied counts as copied. */
    val fromScreenshot: Boolean = false,
) {
    val isPinned: Boolean get() = pinnedAt != null

    /** True when this entry is an image rather than text: its bytes are kept, or a URI was. */
    val isImage: Boolean
        get() = (mediaFile != null || uri != null) && mimeType?.startsWith("image/") == true

    /** True when the image's bytes are kept. */
    val hasMedia: Boolean get() = mediaFile != null
}
