// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A language model the user imported for the text assistant.
 *
 * There is at most one active at a time and usually zero: a model is hundreds of megabytes and
 * nothing here downloads anything, so having one is a deliberate act. The row exists so that
 * Settings can show what is installed, and so the hash can be re-checked before the file is
 * ever mapped and executed.
 */
@Entity(
    tableName = "assist_models",
    indices = [Index(value = ["fileName"], unique = true)],
)
data class AssistModelEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val displayName: String,
    /** File name inside the app's private models directory. Never a content:// URI. */
    val fileName: String,
    /** Lowercase hex. Matched against KnownAssistModels at import and re-checked before load. */
    val sha256: String,
    val sizeBytes: Long,
    val license: String,
    val source: String,
    val contextTokens: Int,
    val importedAt: Long,
    val active: Boolean,
    /** Set when a load-time hash check failed, so Settings can say what happened. */
    val integrityFailedAt: Long? = null,
)
