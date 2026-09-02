// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * One idea per card, titled.
 *
 * A long screen of switches separated only by rules reads as one list of unrelated things, and
 * the reader has to hold which heading they are still under. A card makes the grouping a shape
 * rather than a memory.
 *
 * The same shape as the Vox applications use, so someone who has seen one settings screen in
 * this family has seen them all.
 */
@Composable
fun SettingsSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Card rather than ElevatedCard because only this one takes a border; the elevated surface
    // and its shadow are then asked for explicitly, so the result is a raised card with a
    // traced edge rather than one or the other.
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        // A drawn edge as well as a lift. Elevation alone is a shadow, and a shadow disappears
        // on a dark theme against a dark surface -- the card then reads as a slightly different
        // rectangle rather than as a container.
        border = BorderStroke(
            BORDER_WIDTH,
            MaterialTheme.colorScheme.primary.copy(alpha = BORDER_ALPHA),
        ),
        colors = CardDefaults.elevatedCardColors(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = CARD_ELEVATION),
    ) {
        Column(
            modifier = Modifier.padding(vertical = CARD_PADDING),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            content()
        }
    }
}

/** The same inside every card and between every pair of them, so the rhythm is learned once. */
private val CARD_PADDING = 10.dp

/** Enough to trace the shape, not enough to be read before the contents. */
private val BORDER_WIDTH = 1.dp
private const val BORDER_ALPHA = 0.35f

/**
 * The halo. High enough to lift the card off the page, low enough that a column of them does
 * not look like a stack of floating tiles.
 */
private val CARD_ELEVATION = 2.dp
