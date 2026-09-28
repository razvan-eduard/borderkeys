// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import com.borderkeys.data.theme.ParticleEffectsSettings
import com.borderkeys.data.theme.ParticleFillLayer
import com.borderkeys.data.theme.ParticleOutlineLayer
import com.borderkeys.data.theme.ParticleRegionSettings
import com.borderkeys.ime.fx.ParticleGeometry
import com.borderkeys.ime.fx.ParticlePreviewView
import com.borderkeys.ime.fx.applyParticleLayer

/**
 * The real particle engine, sized and positioned by the caller, usually behind the selected chip
 * in [com.borderkeys.settings.screen.EffectsScreen].
 *
 * [shape] must be the [Shape] the chip behind it is drawn with ([PickerChip]'s `shape`). It
 * becomes a [ParticleGeometry] once this view is measured, `null` until then. One of [outline]
 * and [fill] is non-null per call site; the other layer stays at `NONE`.
 */
@Composable
fun ParticleChipPreview(
    shape: Shape,
    modifier: Modifier = Modifier,
    outline: ParticleOutlineLayer? = null,
    fill: ParticleFillLayer? = null,
    /**
     * How far past the chip's bounds the preview may draw, on every side; the chip's geometry sits
     * at this offset inside the view.
     */
    bleed: Dp = 28.dp,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val bleedPx = with(density) { bleed.roundToPx() }
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val outlineGeometry = remember(shape, density, layoutDirection, sizePx, bleedPx) {
        val chipWidth = sizePx.width - 2 * bleedPx
        val chipHeight = sizePx.height - 2 * bleedPx
        if (chipWidth <= 0 || chipHeight <= 0) {
            null
        } else {
            shape.createOutline(IntSize(chipWidth, chipHeight).toSize(), layoutDirection, density)
                .toParticleGeometry(offsetX = bleedPx.toFloat(), offsetY = bleedPx.toFloat())
        }
    }
    AndroidView(
        factory = { context -> ParticlePreviewView(context) },
        update = { view ->
            val region = ParticleRegionSettings(
                enabled = true,
                outline = outline ?: ParticleOutlineLayer(type = ParticleEffectsSettings.OUTLINE_NONE),
                fill = fill ?: ParticleFillLayer(type = ParticleEffectsSettings.FILL_NONE),
            )
            applyParticleLayer(view.particles, region)
            view.geometry = outlineGeometry
            view.retrace()
        },
        // `bleed` larger than the chip on every side, with the chip in its middle; drawn on top of
        // the chip and hidden from accessibility services.
        modifier = modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints.fixed(constraints.maxWidth + 2 * bleedPx, constraints.maxHeight + 2 * bleedPx),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(-bleedPx, -bleedPx)
                }
            }
            .onSizeChanged { sizePx = it }
            .semantics { hideFromAccessibility() },
    )
}

/**
 * [Outline.Rectangle] and [Outline.Rounded] become [ParticleGeometry.RoundedRect]; anything else
 * becomes [ParticleGeometry.Exact], traced along its path, with particles spawned along its
 * bounding rectangle.
 */
private fun Outline.toParticleGeometry(offsetX: Float, offsetY: Float): ParticleGeometry = when (this) {
    is Outline.Rectangle -> rect.toRoundedRect(cornerRadiusPx = 0f, offsetX, offsetY)
    is Outline.Rounded -> roundRect.let { rr ->
        Rect(rr.left, rr.top, rr.right, rr.bottom).toRoundedRect(rr.topLeftCornerRadius.x, offsetX, offsetY)
    }
    is Outline.Generic -> ParticleGeometry.Exact(
        path = path.asAndroidPath().also { it.offset(offsetX, offsetY) },
        emitterFallback = bounds.toRoundedRect(cornerRadiusPx = 0f, offsetX, offsetY),
    )
}

private fun Rect.toRoundedRect(cornerRadiusPx: Float, offsetX: Float, offsetY: Float): ParticleGeometry.RoundedRect =
    ParticleGeometry.RoundedRect(left + offsetX, top + offsetY, right + offsetX, bottom + offsetY, cornerRadiusPx)
