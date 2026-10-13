// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.borderkeys.geometry.bothSides
import com.borderkeys.geometry.half

/** One idea per card, its title on the card's top outline at the start, the outline cut around it. */
@Composable
fun SettingsSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = MaterialTheme.typography.titleLarge
    val shape = MaterialTheme.shapes.medium
    val outlineColour = MaterialTheme.colorScheme.primary.copy(alpha = CARD_BORDER_ALPHA)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = SPACE_XLARGE, vertical = SPACE_SMALL)) {
        // The title as the Text below lays it out; the card starts at the middle of its first line.
        val titleInset = with(density) { SPACE_GUTTER.roundToPx() }
        val titleLayout = measurer.measure(
            text = title,
            style = style,
            constraints = Constraints(maxWidth = (constraints.maxWidth - bothSides(titleInset)).coerceAtLeast(0)),
        )
        val lineCentre = half(titleLayout.getLineBottom(FIRST_LINE))
        val cut = titleCut(
            width = constraints.maxWidth.toFloat(),
            inset = titleInset.toFloat(),
            titleWidth = titleLayout.size.width.toFloat(),
            lineLeft = titleLayout.getLineLeft(FIRST_LINE),
            lineRight = titleLayout.getLineRight(FIRST_LINE),
            margin = with(density) { SPACE_XSMALL.toPx() },
            rtl = rtl,
        )
        // The elevated card's colours and elevation, its outline drawn over it with the cut left open.
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = with(density) { lineCentre.toDp() })
                .drawWithContent {
                    drawContent()
                    val stroke = BORDER_WIDTH.toPx()
                    clipRect(
                        left = cut.start,
                        top = 0f,
                        right = cut.endInclusive,
                        bottom = stroke,
                        clipOp = ClipOp.Difference,
                    ) {
                        inset(half(stroke)) {
                            val outline = shape.createOutline(size, layoutDirection, this)
                            drawOutline(outline, outlineColour, style = Stroke(stroke))
                        }
                    }
                },
            shape = shape,
            colors = CardDefaults.elevatedCardColors(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = CARD_ELEVATION),
        ) {
            Column(
                modifier = Modifier.padding(
                    top = with(density) { (titleLayout.size.height - lineCentre).toDp() } + SPACE_MEDIUM,
                    bottom = CARD_PADDING,
                ),
                verticalArrangement = Arrangement.spacedBy(SPACE_XSMALL),
            ) {
                content()
            }
        }
        Text(
            text = title,
            style = style,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = SPACE_GUTTER),
        )
    }
}

/**
 * The span of a card's top outline left open around its title: the title's first line, from
 * [lineLeft] to [lineRight] within the title's [titleWidth], [margin] past each end. The title sits
 * [inset] in from the start of a card [width] wide, the start being the right when [rtl].
 */
internal fun titleCut(
    width: Float,
    inset: Float,
    titleWidth: Float,
    lineLeft: Float,
    lineRight: Float,
    margin: Float,
    rtl: Boolean,
): ClosedFloatingPointRange<Float> {
    val titleLeft = if (rtl) width - inset - titleWidth else inset
    return (titleLeft + lineLeft - margin)..(titleLeft + lineRight + margin)
}

/** The title's line the outline passes through. */
private const val FIRST_LINE = 0

/** The padding below every card's content. */
private val CARD_PADDING = 10.dp
