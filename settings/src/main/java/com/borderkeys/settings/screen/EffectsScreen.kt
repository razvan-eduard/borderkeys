// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.BuiltInEffectsPreset
import com.borderkeys.data.theme.BuiltInEffectsPresets
import com.borderkeys.data.theme.CustomEffectsPresetEntry
import com.borderkeys.data.theme.ParticleEffectsSettings
import com.borderkeys.data.theme.ParticleFillLayer
import com.borderkeys.data.theme.ParticleOutlineLayer
import com.borderkeys.data.theme.ParticleRegionSettings
import com.borderkeys.data.theme.ParticleRegionLook
import com.borderkeys.data.theme.withPresetType
import com.borderkeys.settings.ColourRow
import com.borderkeys.settings.DefaultableSlider
import com.borderkeys.settings.ParticleChipPreview
import com.borderkeys.settings.PickerChip
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.AdvancedSection
import com.borderkeys.settings.SuggestionStripPreview
import com.borderkeys.settings.SwitchRow
import com.borderkeys.settings.rememberParticleEffectsUpdater
import com.borderkeys.settings.rememberPreferencesUpdater
import com.borderkeys.settings.rememberThemeUpdater
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Particle effects: [MyPresetsCard], whose presets apply to every region at once, then one card
 * per surface with its own switch and its Outline and Fill layers.
 */
@Composable
fun EffectsScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val repository = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    val updateTheme = rememberThemeUpdater()
    val updateParticleEffects = rememberParticleEffectsUpdater()
    val updatePreferences = rememberPreferencesUpdater()
    val appearance by repository.appearance
        .collectAsStateWithLifecycle(initialValue = remember { repository.currentAppearance() })
    val (theme, _, _, particleEffects) = appearance

    val onCustomColoursChange: (String, List<Int>) -> Unit = { key, colours ->
        updateTheme { t -> t.copy(customColours = t.customColours + (key to colours)) }
    }

    val customEffectsPresets by repository.customEffectsPresets
        .collectAsStateWithLifecycle(initialValue = remember { repository.currentCustomEffectsPresets() })

    // Which save, rename or delete dialog is open.
    var savingEffectsPreset by remember { mutableStateOf(false) }
    var renamingEffectsPreset by remember { mutableStateOf<CustomEffectsPresetEntry?>(null) }
    var deletingEffectsPreset by remember { mutableStateOf<CustomEffectsPresetEntry?>(null) }
    var presetNotice by remember { mutableStateOf("") }

    val baseline = baselineFor(particleEffects, customEffectsPresets)
    // Off applied and unchanged: every region card is greyed out and takes no input.
    val locked = baseline.id == BuiltInEffectsPresets.OFF.id && baseline.matches(particleEffects)

    val presetActions = EffectsPresetActions(
        saved = customEffectsPresets,
        onApplyCustom = { entry -> updateParticleEffects { entry.appliedTo(it) } },
        onApplyBuiltIn = { preset -> updateParticleEffects { preset.appliedTo(it) } },
        onSave = { savingEffectsPreset = true },
        onRename = { entry -> renamingEffectsPreset = entry },
        onDelete = { entry -> deletingEffectsPreset = entry },
    )

    val regionSlots = listOf(
        RegionSlot("keyboard", strings[Keys.PARTICLE_EFFECTS_ON_KEYBOARD], particleEffects.keyboard),
        RegionSlot("radial", strings[Keys.PARTICLE_EFFECTS_ON_RADIAL], particleEffects.radial),
        RegionSlot("strip", strings[Keys.PARTICLE_EFFECTS_ON_STRIP], particleEffects.strip),
        RegionSlot(
            "language_revert", strings[Keys.PARTICLE_EFFECTS_ON_LANGUAGE_REVERT], particleEffects.languageRevert,
        ),
        RegionSlot("quick_actions", strings[Keys.PARTICLE_EFFECTS_ON_QUICK_ACTIONS], particleEffects.quickActions),
    )

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Event effects, above the particle cards.
        EventEffectsSection(
            effects = appearance.preferences.effects,
            customColours = theme.customColours,
            onCustomColoursChange = onCustomColoursChange,
            onChange = { change ->
                updatePreferences { it.copy(effects = change(it.effects)) }
            },
        )
        MyPresetsCard(
            particleEffects = particleEffects,
            baseline = baseline,
            locked = locked,
            presetActions = presetActions,
        )
        RegionCard(
            title = strings[Keys.PARTICLE_EFFECTS_ON_KEYBOARD],
            regionKey = "keyboard",
            region = particleEffects.keyboard,
            baseline = baseline.lookFor("keyboard"),
            locked = locked,
            customColours = theme.customColours,
            onCustomColoursChange = onCustomColoursChange,
            onRegionChange = { change -> updateParticleEffects { it.copy(keyboard = change(it.keyboard)) } },
        )
        RegionCard(
            title = strings[Keys.PARTICLE_EFFECTS_ON_RADIAL],
            regionKey = "radial",
            region = particleEffects.radial,
            baseline = baseline.lookFor("radial"),
            locked = locked,
            customColours = theme.customColours,
            onCustomColoursChange = onCustomColoursChange,
            onRegionChange = { change -> updateParticleEffects { it.copy(radial = change(it.radial)) } },
        )
        RegionCard(
            title = strings[Keys.PARTICLE_EFFECTS_ON_STRIP],
            regionKey = "strip",
            region = particleEffects.strip,
            baseline = baseline.lookFor("strip"),
            locked = locked,
            customColours = theme.customColours,
            onCustomColoursChange = onCustomColoursChange,
            onRegionChange = { change -> updateParticleEffects { it.copy(strip = change(it.strip)) } },
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
            baseline = baseline.lookFor("language_revert"),
            locked = locked,
            customColours = theme.customColours,
            onCustomColoursChange = onCustomColoursChange,
            onRegionChange = { change -> updateParticleEffects { it.copy(languageRevert = change(it.languageRevert)) } },
        )
        RegionCard(
            title = strings[Keys.PARTICLE_EFFECTS_ON_QUICK_ACTIONS],
            regionKey = "quick_actions",
            region = particleEffects.quickActions,
            baseline = baseline.lookFor("quick_actions"),
            locked = locked,
            customColours = theme.customColours,
            onCustomColoursChange = onCustomColoursChange,
            onRegionChange = { change -> updateParticleEffects { it.copy(quickActions = change(it.quickActions)) } },
        )
        if (presetNotice.isNotEmpty()) {
            Text(
                presetNotice,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }

    if (savingEffectsPreset) {
        ParticleSavePresetDialog(
            regionSlots = regionSlots,
            onDismiss = { savingEffectsPreset = false },
            onConfirm = { name, outline, fill ->
                savingEffectsPreset = false
                scope.launch {
                    val id = repository.saveCustomEffectsPreset(name, outline, fill)
                    if (id == null) {
                        presetNotice = strings[Keys.PARTICLE_EFFECTS_PRESET_LIMIT_REACHED]
                    } else {
                        // The preset just saved becomes the applied one.
                        repository.markEffectsPresetApplied(id)
                    }
                }
            },
        )
    }
    renamingEffectsPreset?.let { entry ->
        ParticlePresetNameDialog(
            title = strings[Keys.PARTICLE_EFFECTS_RENAME_PRESET],
            confirmLabel = strings[Keys.THEME_SAVE],
            maxLength = CustomEffectsPresetEntry.MAX_NAME_LENGTH,
            initial = entry.name,
            onDismiss = { renamingEffectsPreset = null },
            onConfirm = { name ->
                renamingEffectsPreset = null
                scope.launch { repository.renameCustomEffectsPreset(entry.id, name) }
            },
        )
    }
    deletingEffectsPreset?.let { entry ->
        ParticlePresetDeleteDialog(
            name = entry.name,
            onDismiss = { deletingEffectsPreset = null },
            onConfirm = {
                deletingEffectsPreset = null
                scope.launch { repository.deleteCustomEffectsPreset(entry.id) }
            },
        )
    }
}

/**
 * The preset everything on the screen is measured against: the chip shown selected, the
 * unsaved-changes notice, and each layer's "Custom" badge.
 */
private sealed class Baseline(val id: String) {
    class BuiltIn(val preset: BuiltInEffectsPreset) : Baseline(preset.id)
    class Saved(val entry: CustomEffectsPresetEntry) : Baseline(entry.id)

    fun matches(settings: ParticleEffectsSettings): Boolean = when (this) {
        is BuiltIn -> preset.matches(settings)
        is Saved -> entry.matches(settings)
    }

    /** What this preset gave the region [key]. */
    fun lookFor(key: String): ParticleRegionLook = when (this) {
        is Saved -> ParticleRegionLook(entry.outline, entry.fill)
        is BuiltIn -> preset.lookFor(key)
    }
}

/**
 * [ParticleEffectsSettings.appliedPresetId] resolved to its preset; when none is applied, or it
 * was deleted, a preset the regions match exactly, and failing that Off.
 */
private fun baselineFor(settings: ParticleEffectsSettings, saved: List<CustomEffectsPresetEntry>): Baseline {
    val applied = settings.appliedPresetId
    BuiltInEffectsPresets.ALL.firstOrNull { it.id == applied }?.let { return Baseline.BuiltIn(it) }
    saved.firstOrNull { it.id == applied }?.let { return Baseline.Saved(it) }
    BuiltInEffectsPresets.ALL.firstOrNull { it.matches(settings) }?.let { return Baseline.BuiltIn(it) }
    saved.firstOrNull { it.matches(settings) }?.let { return Baseline.Saved(it) }
    return Baseline.BuiltIn(BuiltInEffectsPresets.OFF)
}

/** One region's current (outline, fill), for the save dialog's region selector. */
private class RegionSlot(val key: String, val title: String, val region: ParticleRegionSettings)

/**
 * [MyPresetsCard]'s saved presets and actions: [onApplyCustom] writes one pair to every region,
 * [onApplyBuiltIn] a built-in preset's five, and [onSave] opens [ParticleSavePresetDialog].
 */
private class EffectsPresetActions(
    val saved: List<CustomEffectsPresetEntry>,
    val onApplyCustom: (CustomEffectsPresetEntry) -> Unit,
    val onApplyBuiltIn: (BuiltInEffectsPreset) -> Unit,
    val onSave: () -> Unit,
    val onRename: (CustomEffectsPresetEntry) -> Unit,
    val onDelete: (CustomEffectsPresetEntry) -> Unit,
)

/** The catalogue key of a [BuiltInEffectsPreset.id]'s display name. */
private fun builtInPresetNameKey(id: String): String = when (id) {
    "off" -> Keys.PARTICLE_EFFECTS_BUILTIN_OFF
    "ice" -> Keys.PARTICLE_EFFECTS_BUILTIN_ICE
    "sand" -> Keys.PARTICLE_EFFECTS_BUILTIN_SAND
    "forest" -> Keys.PARTICLE_EFFECTS_BUILTIN_FOREST
    "neon" -> Keys.PARTICLE_EFFECTS_BUILTIN_NEON
    else -> Keys.PARTICLE_EFFECTS_BUILTIN_FIRE
}

/**
 * The top card: [BuiltInEffectsPresets.ALL], then the user's saved presets, each applying to
 * every region. The selected chip is [baseline]; while the regions differ from it, a pulsing
 * notice names it.
 */
@Composable
private fun MyPresetsCard(
    particleEffects: ParticleEffectsSettings,
    baseline: Baseline,
    locked: Boolean,
    presetActions: EffectsPresetActions,
) {
    val strings = LocalStrings.current

    SettingsSectionCard(strings[Keys.PARTICLE_EFFECTS_MY_PRESETS]) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (preset in BuiltInEffectsPresets.ALL) {
                val name = strings[builtInPresetNameKey(preset.id)]
                PickerChip(name, preset.id == baseline.id) { presetActions.onApplyBuiltIn(preset) }
            }
            for (entry in presetActions.saved) {
                PickerChip(entry.name, entry.id == baseline.id) { presetActions.onApplyCustom(entry) }
            }
        }

        val active = (baseline as? Baseline.Saved)?.entry
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = presetActions.onSave) { Text(strings[Keys.PARTICLE_EFFECTS_SAVE_PRESET]) }
            if (active != null) {
                TextButton(onClick = { presetActions.onRename(active) }) { Text(strings[Keys.THEME_RENAME]) }
                TextButton(onClick = { presetActions.onDelete(active) }) {
                    Text(strings[Keys.THEME_DELETE], color = MaterialTheme.colorScheme.error)
                }
            }
            // The notice, beside the save button.
            if (!baseline.matches(particleEffects)) {
                val name = when (baseline) {
                    is Baseline.BuiltIn -> strings[builtInPresetNameKey(baseline.preset.id)]
                    is Baseline.Saved -> baseline.entry.name
                }
                PulsingUnsavedText(
                    strings.getString(Keys.PARTICLE_EFFECTS_UNSAVED_CHANGES, name),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (locked) {
            Text(
                strings[Keys.PARTICLE_EFFECTS_OFF_LOCKED_NOTE],
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
    }
}

/** "Unsaved changes since Fire", fading in and out. */
@Composable
private fun PulsingUnsavedText(text: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition()
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error.copy(alpha = alpha),
        modifier = modifier,
    )
}

/**
 * One surface's card: its switch, an optional live [preview], and its two layers. [regionKey]
 * names the region in the custom-colour keys; see [particleColourKey].
 */
@Composable
private fun RegionCard(
    title: String,
    regionKey: String,
    region: ParticleRegionSettings,
    baseline: ParticleRegionLook,
    locked: Boolean,
    customColours: Map<String, List<Int>>,
    onCustomColoursChange: (String, List<Int>) -> Unit,
    onRegionChange: ((ParticleRegionSettings) -> ParticleRegionSettings) -> Unit,
    preview: @Composable (() -> Unit)? = null,
) {
    // "Custom": not what the applied preset gave this region.
    val outlineCustom = region.outline != baseline.outline
    val fillCustom = region.fill != baseline.fill
    Box {
        Box(modifier = Modifier.alpha(if (locked) LOCKED_ALPHA else 1f)) {
            RegionCardContent(
                title, region, outlineCustom, fillCustom, regionKey, customColours, onCustomColoursChange,
                onRegionChange, preview,
            )
        }
        if (locked) {
            // Takes taps over the whole card, not drags, without a ripple.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            )
        }
    }
}

/** How dim a locked region card is drawn: Material's disabled-content alpha. */
private const val LOCKED_ALPHA = 0.38f

@Composable
private fun RegionCardContent(
    title: String,
    region: ParticleRegionSettings,
    outlineCustom: Boolean,
    fillCustom: Boolean,
    regionKey: String,
    customColours: Map<String, List<Int>>,
    onCustomColoursChange: (String, List<Int>) -> Unit,
    onRegionChange: ((ParticleRegionSettings) -> ParticleRegionSettings) -> Unit,
    preview: @Composable (() -> Unit)?,
) {
    // Every change below transforms what is stored when the write runs, not this composition's
    // copy of the region.
    val strings = LocalStrings.current
    SettingsSectionCard(title) {
        SwitchRow(
            title = strings[Keys.PARTICLE_EFFECTS_ENABLE],
            subtitle = strings[Keys.PARTICLE_EFFECTS_ENABLE_NOTE],
            checked = region.enabled,
        ) { value -> onRegionChange { it.copy(enabled = value) } }

        // Dimmed and not taking input while the region is off; the switch stays live.
        Box {
            Box(modifier = Modifier.alpha(if (region.enabled) 1f else LOCKED_ALPHA)) {
                Column {
                    preview?.invoke()

                    OutlineLayerSection(
                        layer = region.outline,
                        custom = outlineCustom,
                        regionKey = regionKey,
                        customColours = customColours,
                        onCustomColoursChange = onCustomColoursChange,
                        onChange = { change -> onRegionChange { it.copy(outline = change(it.outline)) } },
                    )
                    FillLayerSection(
                        layer = region.fill,
                        custom = fillCustom,
                        regionKey = regionKey,
                        customColours = customColours,
                        onCustomColoursChange = onCustomColoursChange,
                        onChange = { change -> onRegionChange { it.copy(fill = change(it.fill)) } },
                    )
                }
            }
            if (!region.enabled) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {},
                )
            }
        }
    }
}

/** A layer's section label, with a "Custom" badge when [custom]. */
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
 * [layer]'s outline style traced live around the chip while it is the selected one, drawn over
 * the chip in [FilterChipDefaults.shape]; the tap still reaches the chip.
 */
@Composable
private fun OutlineTypeChip(label: String, layer: ParticleOutlineLayer, selected: Boolean, onClick: () -> Unit) {
    PreviewedChip(label, selected, onClick) { shape ->
        // Not clipped: outline styles draw just outside the chip.
        ParticleChipPreview(shape = shape, outline = layer, modifier = Modifier.matchParentSize())
    }
}

/** See [OutlineTypeChip] -- the same idea for [ParticleFillLayer]. */
@Composable
private fun FillTypeChip(label: String, layer: ParticleFillLayer, selected: Boolean, onClick: () -> Unit) {
    PreviewedChip(label, selected, onClick) { shape ->
        ParticleChipPreview(shape = shape, fill = layer, modifier = Modifier.matchParentSize())
    }
}

/**
 * A [PickerChip] whose layout box is exactly its visible surface, without Material's 48dp minimum
 * touch target, with [preview] laid over it when [selected]. Every chip in these rows uses it.
 */
@Composable
private fun PreviewedChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    highlighted: Boolean = selected,
    preview: @Composable BoxScope.(Shape) -> Unit,
) {
    val shape = FilterChipDefaults.shape
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        if (!selected) {
            PickerChip(label, highlighted, shape, onClick)
            return@CompositionLocalProvider
        }
        Box(contentAlignment = Alignment.Center) {
            PickerChip(label, true, shape, onClick)
            preview(shape)
        }
    }
}

/**
 * Outline's type picklist and, while a style other than None is chosen, its colours, speed,
 * density and width. [custom] shows the "Custom" badge.
 */
@Composable
private fun OutlineLayerSection(
    layer: ParticleOutlineLayer,
    custom: Boolean,
    regionKey: String,
    customColours: Map<String, List<Int>>,
    onCustomColoursChange: (String, List<Int>) -> Unit,
    onChange: ((ParticleOutlineLayer) -> ParticleOutlineLayer) -> Unit,
) {
    val strings = LocalStrings.current
    val isNone = layer.type == ParticleEffectsSettings.OUTLINE_NONE

    LayerSectionHeader(strings[Keys.PARTICLE_EFFECTS_OUTLINE], custom)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PreviewedChip(strings[Keys.PARTICLE_OUTLINE_NONE], selected = false, onClick = {
            onChange { it.copy(type = ParticleEffectsSettings.OUTLINE_NONE) }
        }, highlighted = isNone) {}
        OutlineTypeChip(
            strings[Keys.PARTICLE_OUTLINE_COMET],
            layer,
            layer.type == ParticleEffectsSettings.OUTLINE_COMET,
        ) { onChange { it.withPresetType(ParticleEffectsSettings.OUTLINE_COMET) } }
        OutlineTypeChip(
            strings[Keys.PARTICLE_OUTLINE_PULSE],
            layer,
            layer.type == ParticleEffectsSettings.OUTLINE_PULSE,
        ) { onChange { it.withPresetType(ParticleEffectsSettings.OUTLINE_PULSE) } }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlineTypeChip(
            strings[Keys.PARTICLE_OUTLINE_SPARKLE],
            layer,
            layer.type == ParticleEffectsSettings.OUTLINE_SPARKLE,
        ) { onChange { it.withPresetType(ParticleEffectsSettings.OUTLINE_SPARKLE) } }
        OutlineTypeChip(
            strings[Keys.PARTICLE_OUTLINE_FIRE],
            layer,
            layer.type == ParticleEffectsSettings.OUTLINE_FIRE,
        ) { onChange { it.withPresetType(ParticleEffectsSettings.OUTLINE_FIRE) } }
        OutlineTypeChip(
            strings[Keys.PARTICLE_OUTLINE_WIND],
            layer,
            layer.type == ParticleEffectsSettings.OUTLINE_WIND,
        ) { onChange { it.withPresetType(ParticleEffectsSettings.OUTLINE_WIND) } }
    }

    if (isNone) {
        return
    }

    val primaryKey = particleColourKey(regionKey, "outline", "primary")
    val secondaryKey = particleColourKey(regionKey, "outline", "secondary")
    AdvancedSection(strings[Keys.EFFECTS_ADVANCED_OUTLINE_NOTE]) {
    ColourRow(
        strings[Keys.PARTICLE_EFFECTS_PRIMARY_COLOUR],
        layer.primaryColor,
        customColours = customColours[primaryKey] ?: emptyList(),
        onCustomColoursChange = { onCustomColoursChange(primaryKey, it) },
    ) { value -> onChange { it.copy(primaryColor = value) } }
    ColourRow(
        strings[Keys.PARTICLE_EFFECTS_SECONDARY_COLOUR],
        layer.secondaryColor,
        customColours = customColours[secondaryKey] ?: emptyList(),
        onCustomColoursChange = { onCustomColoursChange(secondaryKey, it) },
    ) { value -> onChange { it.copy(secondaryColor = value) } }
    DefaultableSlider(
        label = strings.getString(Keys.PARTICLE_EFFECTS_SPEED, "%.1f".format(layer.speed)),
        value = layer.speed,
        range = ParticleEffectsSettings.MIN_SPEED..ParticleEffectsSettings.MAX_SPEED,
        default = ParticleEffectsSettings.DEFAULT_SPEED,
        steps = PARTICLE_SLIDER_STEPS,
    ) { value -> onChange { it.copy(speed = value) } }
    DefaultableSlider(
        label = strings.getString(Keys.PARTICLE_EFFECTS_DENSITY, "%.1f".format(layer.density)),
        value = layer.density,
        range = ParticleEffectsSettings.MIN_DENSITY..ParticleEffectsSettings.MAX_DENSITY,
        default = ParticleEffectsSettings.DEFAULT_DENSITY,
        steps = PARTICLE_SLIDER_STEPS,
    ) { value -> onChange { it.copy(density = value) } }
    DefaultableSlider(
        label = strings.getString(Keys.PARTICLE_EFFECTS_WIDTH, "%.1f".format(layer.width)),
        value = layer.width,
        range = ParticleEffectsSettings.MIN_WIDTH..ParticleEffectsSettings.MAX_WIDTH,
        default = ParticleEffectsSettings.DEFAULT_WIDTH,
        steps = PARTICLE_SLIDER_STEPS,
    ) { value -> onChange { it.copy(width = value) } }
    }
}

/**
 * Fill's type picklist and, while a style other than None is chosen, its colours, speed and
 * density.
 */
@Composable
private fun FillLayerSection(
    layer: ParticleFillLayer,
    custom: Boolean,
    regionKey: String,
    customColours: Map<String, List<Int>>,
    onCustomColoursChange: (String, List<Int>) -> Unit,
    onChange: ((ParticleFillLayer) -> ParticleFillLayer) -> Unit,
) {
    val strings = LocalStrings.current
    val isNone = layer.type == ParticleEffectsSettings.FILL_NONE

    LayerSectionHeader(strings[Keys.PARTICLE_EFFECTS_FILL], custom)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PreviewedChip(strings[Keys.PARTICLE_PRESET_NONE], selected = false, onClick = {
            onChange { it.copy(type = ParticleEffectsSettings.FILL_NONE) }
        }, highlighted = isNone) {}
        FillTypeChip(
            strings[Keys.PARTICLE_PRESET_FIRE],
            layer,
            layer.type == ParticleEffectsSettings.FILL_FIRE,
        ) { onChange { it.withPresetType(ParticleEffectsSettings.FILL_FIRE) } }
        FillTypeChip(
            strings[Keys.PARTICLE_PRESET_GLOW],
            layer,
            layer.type == ParticleEffectsSettings.FILL_GLOW,
        ) { onChange { it.withPresetType(ParticleEffectsSettings.FILL_GLOW) } }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FillTypeChip(
            strings[Keys.PARTICLE_PRESET_WAVES],
            layer,
            layer.type == ParticleEffectsSettings.FILL_WAVES,
        ) { onChange { it.withPresetType(ParticleEffectsSettings.FILL_WAVES) } }
        FillTypeChip(
            strings[Keys.PARTICLE_PRESET_RAINBOW],
            layer,
            layer.type == ParticleEffectsSettings.FILL_RAINBOW,
        ) { onChange { it.withPresetType(ParticleEffectsSettings.FILL_RAINBOW) } }
        FillTypeChip(
            strings[Keys.PARTICLE_PRESET_NEON],
            layer,
            layer.type == ParticleEffectsSettings.FILL_NEON,
        ) { onChange { it.withPresetType(ParticleEffectsSettings.FILL_NEON) } }
    }

    if (isNone) {
        return
    }

    val primaryKey = particleColourKey(regionKey, "fill", "primary")
    val secondaryKey = particleColourKey(regionKey, "fill", "secondary")
    AdvancedSection(strings[Keys.EFFECTS_ADVANCED_FILL_NOTE]) {
    ColourRow(
        strings[Keys.PARTICLE_EFFECTS_PRIMARY_COLOUR],
        layer.primaryColor,
        customColours = customColours[primaryKey] ?: emptyList(),
        onCustomColoursChange = { onCustomColoursChange(primaryKey, it) },
    ) { value -> onChange { it.copy(primaryColor = value) } }
    ColourRow(
        strings[Keys.PARTICLE_EFFECTS_SECONDARY_COLOUR],
        layer.secondaryColor,
        customColours = customColours[secondaryKey] ?: emptyList(),
        onCustomColoursChange = { onCustomColoursChange(secondaryKey, it) },
    ) { value -> onChange { it.copy(secondaryColor = value) } }
    DefaultableSlider(
        label = strings.getString(Keys.PARTICLE_EFFECTS_SPEED, "%.1f".format(layer.speed)),
        value = layer.speed,
        range = ParticleEffectsSettings.MIN_SPEED..ParticleEffectsSettings.MAX_SPEED,
        default = ParticleEffectsSettings.DEFAULT_SPEED,
        steps = PARTICLE_SLIDER_STEPS,
    ) { value -> onChange { it.copy(speed = value) } }
    DefaultableSlider(
        label = strings.getString(Keys.PARTICLE_EFFECTS_DENSITY, "%.1f".format(layer.density)),
        value = layer.density,
        range = ParticleEffectsSettings.MIN_DENSITY..ParticleEffectsSettings.MAX_DENSITY,
        default = ParticleEffectsSettings.DEFAULT_DENSITY,
        steps = PARTICLE_SLIDER_STEPS,
    ) { value -> onChange { it.copy(density = value) } }
    }
}

/** Renames a saved preset. */
@Composable
private fun ParticlePresetNameDialog(
    title: String,
    confirmLabel: String,
    maxLength: Int,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
) {
    val strings = LocalStrings.current
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(maxLength) },
                singleLine = true,
                label = { Text(strings[Keys.PARTICLE_EFFECTS_PRESET_NAME_HINT]) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim().ifEmpty { strings[Keys.THEME_CUSTOM] }) },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings[Keys.THEME_CANCEL]) }
        },
    )
}

/** Saves a new preset: a name, and which region's (outline, fill) pair to copy. */
@Composable
private fun ParticleSavePresetDialog(
    regionSlots: List<RegionSlot>,
    onConfirm: (name: String, outline: ParticleOutlineLayer, fill: ParticleFillLayer) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    var selectedKey by remember { mutableStateOf(regionSlots.first().key) }
    val selected = regionSlots.firstOrNull { it.key == selectedKey } ?: regionSlots.first()
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings[Keys.PARTICLE_EFFECTS_NAME_PRESET]) },
        text = {
            Column {
                Text(strings[Keys.PARTICLE_EFFECTS_SAVE_FROM_REGION], style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 6.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (slot in regionSlots) {
                        PickerChip(slot.title, slot.key == selectedKey) { selectedKey = slot.key }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(CustomEffectsPresetEntry.MAX_NAME_LENGTH) },
                    singleLine = true,
                    label = { Text(strings[Keys.PARTICLE_EFFECTS_PRESET_NAME_HINT]) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalName = name.trim().ifEmpty { strings[Keys.THEME_CUSTOM] }
                    onConfirm(finalName, selected.region.outline, selected.region.fill)
                },
            ) { Text(strings[Keys.THEME_SAVE]) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings[Keys.THEME_CANCEL]) }
        },
    )
}

@Composable
private fun ParticlePresetDeleteDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings[Keys.PARTICLE_EFFECTS_DELETE_PRESET_TITLE]) },
        text = { Text(strings.getString(Keys.THEME_DELETE_THEME_MESSAGE, name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(strings[Keys.THEME_DELETE], color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings[Keys.THEME_CANCEL]) }
        },
    )
}

/** The [com.borderkeys.data.theme.KeyboardTheme.customColours] key of a region, layer and slot. */
private fun particleColourKey(regionKey: String, layerKey: String, slot: String): String =
    "particle_${regionKey}_${layerKey}_$slot"

/** The steps of every speed, density and width slider: 0.5..2 in 0.25 stops. */
private val PARTICLE_SLIDER_STEPS =
    ((ParticleEffectsSettings.MAX_SPEED - ParticleEffectsSettings.MIN_SPEED) / 0.25f).roundToInt() - 1
