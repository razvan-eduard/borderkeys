// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import com.borderkeys.data.theme.ParticleEffectsSettings
import com.borderkeys.data.theme.ParticleRegionSettings

/**
 * Pushes one region's stored settings onto its own fill/outline [ParticleField] pair -- the one
 * function [com.borderkeys.ime.BorderKeysService]'s real wiring and
 * [com.borderkeys.settings.SuggestionStripPreview]'s settings-screen preview both call, so the
 * preview can never quietly drift from what the keyboard itself would actually show.
 *
 * Outline is additionally gated on its own type not being [ParticleEffectsSettings.OUTLINE_NONE]
 * -- that is this feature's "off" value for the layer, not a fourth real style, so a region can
 * be `enabled` with Fill running and Outline still dark.
 */
fun applyParticleLayer(fill: ParticleField, outline: ParticleField, region: ParticleRegionSettings) {
    fill.enabled = region.enabled
    fill.preset = ParticleEffectPresets.forSetting(region.fill.type)
    fill.speedMultiplier = region.fill.speed
    fill.densityMultiplier = region.fill.density
    fill.primaryColor = region.fill.primaryColor
    fill.secondaryColor = region.fill.secondaryColor

    outline.enabled = region.enabled && region.outline.type != ParticleEffectsSettings.OUTLINE_NONE
    outline.preset = ParticleOutlineStylePresets.forSetting(region.outline.type)
    outline.speedMultiplier = region.outline.speed
    outline.densityMultiplier = region.outline.density
    outline.widthMultiplier = region.outline.width
    outline.primaryColor = region.outline.primaryColor
    outline.secondaryColor = region.outline.secondaryColor
}
