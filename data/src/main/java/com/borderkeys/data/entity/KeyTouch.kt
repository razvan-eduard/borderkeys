// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.entity

import androidx.room.Entity

/**
 * Where taps land on one letter key of one bucket (the orientation, position mode and layout):
 * weighted totals of their offsets from the key's centre, in key units (x in key widths, y in key
 * heights), each tap weighing less as it ages. Recorded only while Learning and the Heatmap are
 * on, never in a private field, and left out of backups.
 */
@Entity(tableName = "key_touches", primaryKeys = ["bucket", "code"])
data class KeyTouch(
    val bucket: String,
    /** The letter the key types. */
    val code: Int,
    /** The taps' total weight. */
    val taps: Double,
    val sumX: Double,
    val sumY: Double,
    val sumXX: Double,
    val sumYY: Double,
    val sumXY: Double,
    /** The key's size in pixels and the display's density at the last tap. */
    val keyWidthPx: Float,
    val keyHeightPx: Float,
    val density: Float,
    val lastUsedAt: Long,
)
