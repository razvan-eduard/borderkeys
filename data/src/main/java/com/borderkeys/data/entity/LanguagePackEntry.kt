// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A language pack the user has imported. [sha256] is re-checked at every start for enabled packs;
 * [licenseNote], the pack's provenance, is required at import.
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
