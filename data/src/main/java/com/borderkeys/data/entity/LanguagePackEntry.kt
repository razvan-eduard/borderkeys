// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A language pack the user has imported, and what is known about it.
 *
 * Nothing is ever downloaded, so every row here got there because someone chose a file. That is
 * also why [sha256] exists and is re-checked at every start for enabled packs: the file lives in
 * the app's private storage, but "private" is a statement about other apps, not about a
 * filesystem that can be corrupted, a restore that can substitute a file, or a rooted device.
 * A pack whose hash no longer matches disables itself and says so, rather than being mapped.
 *
 * [licenseNote] is required at import and is not decorative. Many lexical corpora are not free,
 * and docs/licensing.md can only be kept honest if the provenance is recorded at the moment the
 * user knows it -- nobody can reconstruct it later from the bytes.
 */
@Entity(
    tableName = "language_packs",
    indices = [
        Index(value = ["tag"]),
        Index(value = ["fileName"], unique = true),
    ],
)
data class LanguagePackEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** BCP-47, as written into the pack header. */
    val tag: String,
    val displayName: String,
    /** File name inside the app's private packs directory. Never a content:// URI. */
    val fileName: String,
    val formatVersion: Int,
    val wordCount: Int,
    val sizeBytes: Long,
    /** Lowercase hex, 64 characters. Recomputed at start for every enabled pack. */
    val sha256: String,
    val importedAt: Long,
    val enabled: Boolean,
    /** Relative weight against the other active packs, before runtime adaptation. */
    val weight: Float,
    val licenseNote: String,
    /** Set when a start-up hash check failed, so the UI can explain why it switched itself off. */
    val integrityFailedAt: Long? = null,
)
