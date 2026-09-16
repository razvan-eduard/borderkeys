// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.ParticleEffectsSettings
import com.borderkeys.data.theme.ParticleFillLayer
import com.borderkeys.data.theme.ParticleOutlineLayer
import com.borderkeys.data.theme.ParticleRegionSettings
import com.borderkeys.data.theme.matchesPreset
import com.borderkeys.settings.ColourRow
import com.borderkeys.settings.DefaultableSlider
import com.borderkeys.settings.PickerChip
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.SuggestionStripPreview
import com.borderkeys.settings.SwitchRow
import com.borderkeys.settings.rememberParticleEffectsUpdater
import com.borderkeys.settings.rememberThemeUpdater
import kotlin.math.roundToInt

/**
 * Bursts and glows, ported from a calendar app's own "today" decoration and retuned for a
 * keyboard: one card per surface, each with its own master switch and its own independent
 * Outline (traces the surface's own border or arc) and Fill (the original "inside particles"
 * ambient/burst) layers.
 *
 * Neither layer's own controls are hidden behind the region's master switch -- the whole point
 * is dialling in a look and only then deciding whether to switch the region on, the same
 * reasoning the live preview on the Suggestion Strip's own card already followed in the first
 * release.
 */
@Composable
fun EffectsScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val repository = remember { DataGraph.themes }
    val updateTheme = rememberThemeUpdater()
    val updateParticleEffects = rememberParticleEffectsUpdater()
    val appearance by repository.appearance
        .collectAsStateWithLifecycle(initialValue = remember { repository.currentAppearance() })
    val (theme, _, _, particleEffects) = appearance

    val onCustomColoursChange: (String, List<Int>) -> Unit = { key, colours ->
        updateTheme { t -> t.copy(customColours = t.customColours + (key to colours)) }
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        RegionCard(
            title = strings[Keys.PARTICLE_EFFECTS_ON_KEYBOARD],
            regionKey = "keyboard",
            region = particleEffects.keyboard,
            customColours = theme.customColours,
            onCustomColoursChange = onCustomColoursChange,
            onRegionChange = { region -> updateParticleEffects { it.copy(keyboard = region) } },
        )
        RegionCard(
            title = strings[Keys.PARTICLE_EFFECTS_ON_RADIAL],
            regionKey = "radial",
            region = particleEffects.radial,
            customColours = theme.customColours,
            onCustomColoursChange = onCustomColoursChange,
            onRegionChange = { region -> updateParticleEffects { it.copy(radial = region) } },
        )
        RegionCard(
            title = strings[Keys.PARTICLE_EFFECTS_ON_STRIP],
            regionKey = "strip",
            region = particleEffects.strip,
            customColours = theme.customColours,
            onCustomColoursChange = onCustomColoursChange,
            onRegionChange = { region -> updateParticleEffects { it.copy(strip = region) } },
            preview = {
                SuggestionStripPreview(
                    appearance,
                    Modifier.padding(vertical = 8.dp),
                    previewParticles = particleEffects.strip,
                )
            },
        )
        RegionCard(
            title = strings[Keys.PARTICLE_EFFECTS_ON_LANGUAGE_REVERT],
            regionKey = "language_revert",
            region = particleEffects.languageRevert,
            customColours = theme.customColours,
            onCustomColoursChange = onCustomColoursChange,
            onRegionChange = { region -> updateParticleEffects { it.copy(languageRevert = region) } },
        )
        RegionCard(
            title = strings[Keys.PARTICLE_EFFECTS_ON_QUICK_ACTIONS],
            regionKey = "quick_actions",
            region = particleEffects.quickActions,
            customColours = theme.customColours,
            onCustomColoursChange = onCustomColoursChange,
            onRegionChange = { region -> updateParticleEffects { it.copy(quickActions = region) } },
        )
    }
}

/**
 * One surface's whole card: its own master switch, an optional live [preview] (only the
 * Suggestion Strip's card passes one), and its two independent layers.
 *
 * [regionKey] names this region in the custom-colour-swatch keys [ColourRow] persists under --
 * see [particleColourKey].
 */
@Composable
private fun RegionCard(
    title: String,
    regionKey: String,
    region: ParticleRegionSettings,
    customColours: Map<String, List<Int>>,
    onCustomColoursChange: (String, List<Int>) -> Unit,
    onRegionChange: (ParticleRegionSettings) -> Unit,
    preview: @Composable (() -> Unit)? = null,
) {
    val strings = LocalStrings.current
    SettingsSectionCard(title) {
        SwitchRow(
            title = strings[Keys.PARTICLE_EFFECTS_ENABLE],
            subtitle = strings[Keys.PARTICLE_EFFECTS_ENABLE_NOTE],
            checked = region.enabled,
        ) { value -> onRegionChange(region.copy(enabled = value)) }

        preview?.invoke()

        OutlineLayerSection(
            layer = region.outline,
            regionKey = regionKey,
            customColours = customColours,
            onCustomColoursChange = onCustomColoursChange,
            onChange = { layer -> onRegionChange(region.copy(outline = layer)) },
        )
        FillLayerSection(
            layer = region.fill,
            regionKey = regionKey,
            customColours = customColours,
            onCustomColoursChange = onCustomColoursChange,
            onChange = { layer -> onRegionChange(region.copy(fill = layer)) },
        )
    }
}

/** The section label, with a "Custom" badge trailing it exactly when [custom] -- shared shape
 *  for both [OutlineLayerSection] and [FillLayerSection] rather than written out twice. */
@Composable
private fun LayerSectionHeader(title: String, custom: Boolean) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (custom) {
            Text(
                strings[Keys.PARTICLE_EFFECTS_CUSTOM],
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Outline's own type picklist (None plus its three styles), and -- only while a real style is
 * chosen, since None has nothing to colour or pace -- its colours, speed, density and width.
 *
 * Picking a type is a plain `.copy(type = ...)`, never resetting the rest: colours/speed/
 * density/width already dialled in survive a round trip through a different type, which is
 * exactly what makes "Custom" ([ParticleOutlineLayer.matchesPreset]) a real, sticky state rather
 * than something a type change quietly clears.
 */
@Composable
private fun OutlineLayerSection(
    layer: ParticleOutlineLayer,
    regionKey: String,
    customColours: Map<String, List<Int>>,
    onCustomColoursChange: (String, List<Int>) -> Unit,
    onChange: (ParticleOutlineLayer) -> Unit,
) {
    val strings = LocalStrings.current
    val isNone = layer.type == ParticleEffectsSettings.OUTLINE_NONE
    val custom = !isNone && !layer.matchesPreset()

    LayerSectionHeader(strings[Keys.PARTICLE_EFFECTS_OUTLINE], custom)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PickerChip(
            strings[Keys.PARTICLE_OUTLINE_NONE],
            isNone,
        ) { onChange(layer.copy(type = ParticleEffectsSettings.OUTLINE_NONE)) }
        PickerChip(
            strings[Keys.PARTICLE_OUTLINE_COMET],
            !custom && layer.type == ParticleEffectsSettings.OUTLINE_COMET,
        ) { onChange(layer.copy(type = ParticleEffectsSettings.OUTLINE_COMET)) }
        PickerChip(
            strings[Keys.PARTICLE_OUTLINE_PULSE],
            !custom && layer.type == ParticleEffectsSettings.OUTLINE_PULSE,
        ) { onChange(layer.copy(type = ParticleEffectsSettings.OUTLINE_PULSE)) }
        PickerChip(
            strings[Keys.PARTICLE_OUTLINE_SPARKLE],
            !custom && layer.type == ParticleEffectsSettings.OUTLINE_SPARKLE,
        ) { onChange(layer.copy(type = ParticleEffectsSettings.OUTLINE_SPARKLE)) }
    }

    if (isNone) {
        return
    }

    val primaryKey = particleColourKey(regionKey, "outline", "primary")
    val secondaryKey = particleColourKey(regionKey, "outline", "secondary")
    ColourRow(
        strings[Keys.PARTICLE_EFFECTS_PRIMARY_COLOUR],
        layer.primaryColor,
        customColours = customColours[primaryKey] ?: emptyList(),
        onCustomColoursChange = { onCustomColoursChange(primaryKey, it) },
    ) { onChange(layer.copy(primaryColor = it)) }
    ColourRow(
        strings[Keys.PARTICLE_EFFECTS_SECONDARY_COLOUR],
        layer.secondaryColor,
        customColours = customColours[secondaryKey] ?: emptyList(),
        onCustomColoursChange = { onCustomColoursChange(secondaryKey, it) },
    ) { onChange(layer.copy(secondaryColor = it)) }
    DefaultableSlider(
        label = strings.getString(Keys.PARTICLE_EFFECTS_SPEED, "%.1f".format(layer.speed)),
        value = layer.speed,
        range = ParticleEffectsSettings.MIN_SPEED..ParticleEffectsSettings.MAX_SPEED,
        default = ParticleEffectsSettings.DEFAULT_SPEED,
        steps = PARTICLE_SLIDER_STEPS,
    ) { onChange(layer.copy(speed = it)) }
    DefaultableSlider(
        label = strings.getString(Keys.PARTICLE_EFFECTS_DENSITY, "%.1f".format(layer.density)),
        value = layer.density,
        range = ParticleEffectsSettings.MIN_DENSITY..ParticleEffectsSettings.MAX_DENSITY,
        default = ParticleEffectsSettings.DEFAULT_DENSITY,
        steps = PARTICLE_SLIDER_STEPS,
    ) { onChange(layer.copy(density = it)) }
    DefaultableSlider(
        label = strings.getString(Keys.PARTICLE_EFFECTS_WIDTH, "%.1f".format(layer.width)),
        value = layer.width,
        range = ParticleEffectsSettings.MIN_WIDTH..ParticleEffectsSettings.MAX_WIDTH,
        default = ParticleEffectsSettings.DEFAULT_WIDTH,
        steps = PARTICLE_SLIDER_STEPS,
    ) { onChange(layer.copy(width = it)) }
}

/**
 * Fill's own type picklist -- the original five presets, no `None`: unlike Outline, Fill has no
 * "off" value of its own, only the region's own master switch turns it off. Otherwise the same
 * shape as [OutlineLayerSection], minus the width axis Fill has no equivalent for.
 */
@Composable
private fun FillLayerSection(
    layer: ParticleFillLayer,
    regionKey: String,
    customColours: Map<String, List<Int>>,
    onCustomColoursChange: (String, List<Int>) -> Unit,
    onChange: (ParticleFillLayer) -> Unit,
) {
    val strings = LocalStrings.current
    val custom = !layer.matchesPreset()

    LayerSectionHeader(strings[Keys.PARTICLE_EFFECTS_FILL], custom)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PickerChip(
            strings[Keys.PARTICLE_PRESET_FIRE],
            !custom && layer.type == ParticleEffectsSettings.FILL_FIRE,
        ) { onChange(layer.copy(type = ParticleEffectsSettings.FILL_FIRE)) }
        PickerChip(
            strings[Keys.PARTICLE_PRESET_GLOW],
            !custom && layer.type == ParticleEffectsSettings.FILL_GLOW,
        ) { onChange(layer.copy(type = ParticleEffectsSettings.FILL_GLOW)) }
        PickerChip(
            strings[Keys.PARTICLE_PRESET_WAVES],
            !custom && layer.type == ParticleEffectsSettings.FILL_WAVES,
        ) { onChange(layer.copy(type = ParticleEffectsSettings.FILL_WAVES)) }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PickerChip(
            strings[Keys.PARTICLE_PRESET_RAINBOW],
            !custom && layer.type == ParticleEffectsSettings.FILL_RAINBOW,
        ) { onChange(layer.copy(type = ParticleEffectsSettings.FILL_RAINBOW)) }
        PickerChip(
            strings[Keys.PARTICLE_PRESET_NEON],
            !custom && layer.type == ParticleEffectsSettings.FILL_NEON,
        ) { onChange(layer.copy(type = ParticleEffectsSettings.FILL_NEON)) }
    }

    val primaryKey = particleColourKey(regionKey, "fill", "primary")
    val secondaryKey = particleColourKey(regionKey, "fill", "secondary")
    ColourRow(
        strings[Keys.PARTICLE_EFFECTS_PRIMARY_COLOUR],
        layer.primaryColor,
        customColours = customColours[primaryKey] ?: emptyList(),
        onCustomColoursChange = { onCustomColoursChange(primaryKey, it) },
    ) { onChange(layer.copy(primaryColor = it)) }
    ColourRow(
        strings[Keys.PARTICLE_EFFECTS_SECONDARY_COLOUR],
        layer.secondaryColor,
        customColours = customColours[secondaryKey] ?: emptyList(),
        onCustomColoursChange = { onCustomColoursChange(secondaryKey, it) },
    ) { onChange(layer.copy(secondaryColor = it)) }
    DefaultableSlider(
        label = strings.getString(Keys.PARTICLE_EFFECTS_SPEED, "%.1f".format(layer.speed)),
        value = layer.speed,
        range = ParticleEffectsSettings.MIN_SPEED..ParticleEffectsSettings.MAX_SPEED,
        default = ParticleEffectsSettings.DEFAULT_SPEED,
        steps = PARTICLE_SLIDER_STEPS,
    ) { onChange(layer.copy(speed = it)) }
    DefaultableSlider(
        label = strings.getString(Keys.PARTICLE_EFFECTS_DENSITY, "%.1f".format(layer.density)),
        value = layer.density,
        range = ParticleEffectsSettings.MIN_DENSITY..ParticleEffectsSettings.MAX_DENSITY,
        default = ParticleEffectsSettings.DEFAULT_DENSITY,
        steps = PARTICLE_SLIDER_STEPS,
    ) { onChange(layer.copy(density = it)) }
}

/** One region+layer+slot's own custom-colour-swatch key --
 *  [com.borderkeys.data.theme.KeyboardTheme.customColours] is a flat, generically-keyed map
 *  shared by every colour picker in the app, not something this screen owns, so a key needs
 *  building rather than a pre-declared constant per region. */
private fun particleColourKey(regionKey: String, layerKey: String, slot: String): String =
    "particle_${regionKey}_${layerKey}_$slot"

/** Every speed/density/width slider shares the same 0.5..2 range in 0.25 stops -- five stops
 *  between the ends, the same formula the first release's single speed slider already used. */
private val PARTICLE_SLIDER_STEPS =
    ((ParticleEffectsSettings.MAX_SPEED - ParticleEffectsSettings.MIN_SPEED) / 0.25f).roundToInt() - 1
