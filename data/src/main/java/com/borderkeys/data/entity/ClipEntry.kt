// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One remembered clipboard item.
 *
 * The most sensitive table in the application: whatever the user copied, which on a phone is
 * routinely a password, a two-factor code or an address. It lives in the SQLCipher database like
 * everything else, it is excluded from every backup transport, and unpinned entries are deleted
 * on a timer rather than kept until something happens to notice them.
 *
 * [contentHash] is the first eight bytes of the SHA-256 of the content, and it carries a unique
 * index. Copying the same thing twice should move it to the top of the list, not add a second
 * copy of it -- and the index is what makes that a database constraint rather than a query the
 * caller has to remember to run first.
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

    /**
     * The content URI of a copied image, or null for text.
     *
     * A URI rather than the image: a clipboard image belongs to the application that produced
     * it, the read permission we were granted with the clip is temporary, and copying hundreds
     * of kilobytes into an encrypted database on every screenshot would be a worse trade than
     * a thumbnail that sometimes cannot be loaded any more. When the grant is gone the entry
     * still shows, and says so.
     */
    val uri: String? = null,

    /** The clip's MIME type, so the panel knows what it is looking at without guessing. */
    val mimeType: String? = null,
) {
    val isPinned: Boolean get() = pinnedAt != null

    /** True when this entry is an image rather than text. */
    val isImage: Boolean get() = uri != null && mimeType?.startsWith("image/") == true
}
