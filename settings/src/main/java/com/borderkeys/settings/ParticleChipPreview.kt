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
 * The real particle engine, sized and positioned by the caller -- typically to sit directly
 * behind whichever chip is currently selected in [com.borderkeys.settings.screen.EffectsScreen],
 * so a style reads as itself rather than as a name and a colour swatch someone has to imagine
 * moving. The same [SuggestionStripPreview] already embeds the real `SuggestionStripView` rather
 * than a second, separately-drawn approximation, one level down: [ParticlePreviewView] is that
 * same idea, scaled to chip size.
 *
 * [shape] must be the exact same [Shape] value the real chip behind this preview is drawn with
 * -- [PickerChip]'s own [shape] parameter -- not a guessed corner radius: an unstyled Material3
 * chip's real shape has changed under this preview once already (see [PickerChip]'s own doc),
 * and reading whichever [Shape] the chip is *actually* using, rather than assuming a number that
 * matched it once, is what stops the two from being able to disagree again. [Outline
 * .toParticleGeometry] converts whatever that shape resolves to (rectangle, rounded rectangle,
 * or something this app has no closed-form model for) into a [ParticleGeometry] once this view's
 * own measured size is known -- `null` (nothing drawn yet) until then.
 *
 * Exactly one of [outline]/[fill] is expected non-null per call site: an outline style traces
 * this shape's own perimeter, a fill style spawns inside it, and the other layer is left at its
 * own `NONE` so nothing extra draws behind a chip that is not previewing it.
 */
@Composable
fun ParticleChipPreview(
    shape: Shape,
    modifier: Modifier = Modifier,
    outline: ParticleOutlineLayer? = null,
    fill: ParticleFillLayer? = null,
    /**
     * How far past the chip's own bounds the preview may draw, on every side. Every outline
     * style radiates *outward* from the element (see `ParticleOutlineStylePresets`), so a
     * preview sized exactly to the chip would show none of it -- the whole effect happens just
     * outside the shape it traces. The view is laid out this much larger than the chip and the
     * chip's geometry is placed at the matching offset inside it.
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
        // Laid out `bleed` larger than the chip on every side and placed so the chip sits in its
        // middle -- see the parameter's own doc. Purely decorative, drawn on top of the chip it
        // belongs to so fill particles are actually visible -- without the semantics below, a
        // real View covering a Compose sibling's bounds reads to an accessibility service as
        // occluding it, and the chip's own label would stop being announced for whichever style
        // is currently selected.
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
 * [Outline.Rectangle]/[Outline.Rounded] have a closed form [com.borderkeys.ime.fx
 * .ParticleSimulation] already knows how to trace a stroke around *and* spawn particles along --
 * [ParticleGeometry.RoundedRect]. Anything else ([Outline.Generic] -- a shape this app has no
 * closed-form model for) still traces exactly via the real [Path][androidx.compose.ui.graphics
 * .Path], with particles falling back to spawning along its own bounding rectangle instead of
 * nowhere -- see [ParticleGeometry.Exact].
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
