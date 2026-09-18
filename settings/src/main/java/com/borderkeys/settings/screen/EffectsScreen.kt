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
import com.borderkeys.settings.rememberThemeUpdater
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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
 *
 * "My presets" ([MyPresetsCard], at the top) is a different thing from any one region's own
 * picker: one saved (outline, fill) combination, under a name the user chose, applied to *every*
 * region at once -- a common look across the whole keyboard in one tap, the way
 * [ThemeScreen][com.borderkeys.settings.screen.ThemeScreen]'s own "My Themes" already sets the
 * entire [com.borderkeys.data.theme.KeyboardTheme] in one tap rather than one field at a time.
 * Per-region customisation below is untouched and still fully independent; a saved preset is a
 * fast starting point for all five, not a replacement for dialling in one on its own.
 */
@Composable
fun EffectsScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val repository = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    val updateTheme = rememberThemeUpdater()
    val updateParticleEffects = rememberParticleEffectsUpdater()
    val appearance by repository.appearance
        .collectAsStateWithLifecycle(initialValue = remember { repository.currentAppearance() })
    val (theme, _, _, particleEffects) = appearance

    val onCustomColoursChange: (String, List<Int>) -> Unit = { key, colours ->
        updateTheme { t -> t.copy(customColours = t.customColours + (key to colours)) }
    }

    val customEffectsPresets by repository.customEffectsPresets
        .collectAsStateWithLifecycle(initialValue = remember { repository.currentCustomEffectsPresets() })

    // Which "save"/"rename"/"delete" dialog (if any) is open right now -- the same shape
    // ThemeScreen's own savingCurrentTheme/renaming/deleting already are. Saving needs to ask
    // which region's look to copy -- see ParticleSavePresetDialog -- so it only needs a flag
    // here, not a captured pair the way it once did.
    var savingEffectsPreset by remember { mutableStateOf(false) }
    var renamingEffectsPreset by remember { mutableStateOf<CustomEffectsPresetEntry?>(null) }
    var deletingEffectsPreset by remember { mutableStateOf<CustomEffectsPresetEntry?>(null) }
    var presetNotice by remember { mutableStateOf("") }

    val baseline = baselineFor(particleEffects, customEffectsPresets)
    // Off applied, and still exactly Off: every region card is greyed out and takes no input.
    // Nothing can be changed while everything is off -- a preset is the way in -- so "custom"
    // never has to mean "off, but with something dialled in underneath".
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
                        // The preset just saved is the one the regions now count as coming
                        // from -- not the one they were built on top of. Regions other than
                        // the one it was copied from may still differ, and the card says so.
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
 * The preset everything on the screen is measured against: the chip shown selected, whether the
 * card reports unsaved changes, and which layers wear a "Custom" badge -- one meaning of
 * "custom" (not what the applied preset gave it) at every level, not one per level.
 */
private sealed class Baseline(val id: String) {
    class BuiltIn(val preset: BuiltInEffectsPreset) : Baseline(preset.id)
    class Saved(val entry: CustomEffectsPresetEntry) : Baseline(entry.id)

    fun matches(settings: ParticleEffectsSettings): Boolean = when (this) {
        is BuiltIn -> preset.matches(settings)
        is Saved -> entry.matches(settings)
    }

    /** What this preset gave the region [key] -- see [RegionCard] for what differing from it means. */
    fun lookFor(key: String): ParticleRegionLook = when (this) {
        is Saved -> ParticleRegionLook(entry.outline, entry.fill)
        is BuiltIn -> preset.lookFor(key)
    }
}

/**
 * [ParticleEffectsSettings.appliedPresetId] resolved to the preset it names. When nothing has
 * been applied yet -- or the saved preset it named was deleted since -- a preset the regions
 * happen to match exactly is taken, and failing that Off: every install starts there, and Off
 * is a preset with the same weight as any named one, so whatever was changed since is exactly
 * the change from it the card and the layer badges should show.
 */
private fun baselineFor(settings: ParticleEffectsSettings, saved: List<CustomEffectsPresetEntry>): Baseline {
    val applied = settings.appliedPresetId
    BuiltInEffectsPresets.ALL.firstOrNull { it.id == applied }?.let { return Baseline.BuiltIn(it) }
    saved.firstOrNull { it.id == applied }?.let { return Baseline.Saved(it) }
    BuiltInEffectsPresets.ALL.firstOrNull { it.matches(settings) }?.let { return Baseline.BuiltIn(it) }
    saved.firstOrNull { it.matches(settings) }?.let { return Baseline.Saved(it) }
    return Baseline.BuiltIn(BuiltInEffectsPresets.OFF)
}

/** One region's own current (outline, fill), for [MyPresetsCard]'s region selector -- read-only
 *  here, since a preset saved from this region is not scoped back to it and applying one writes
 *  to every region at once regardless of which is selected when the tap happens. */
private class RegionSlot(val key: String, val title: String, val region: ParticleRegionSettings)

/** [MyPresetsCard]'s own saved-preset library plus the actions its controls trigger -- bundled
 *  into one object rather than loose callback parameters. Neither apply action is scoped to a
 *  single region: [onApplyCustom] writes [CustomEffectsPresetEntry.outline]/[.fill] to every
 *  region identically, and [onApplyBuiltIn] writes [BuiltInEffectsPreset]'s own five, one per
 *  region. [onSave] only opens the save dialog -- see [ParticleSavePresetDialog] for where the
 *  region to snapshot is actually chosen. See
 *  [com.borderkeys.data.theme.ThemeRepository.saveCustomEffectsPreset] for what save/rename/
 *  delete actually do. */
private class EffectsPresetActions(
    val saved: List<CustomEffectsPresetEntry>,
    val onApplyCustom: (CustomEffectsPresetEntry) -> Unit,
    val onApplyBuiltIn: (BuiltInEffectsPreset) -> Unit,
    val onSave: () -> Unit,
    val onRename: (CustomEffectsPresetEntry) -> Unit,
    val onDelete: (CustomEffectsPresetEntry) -> Unit,
)

/** [BuiltInEffectsPreset.id] carries no display text of its own -- `:data` does not depend on
 *  `:i18n` -- so this is the one place that maps its stable slug to a translated string, the
 *  same way [FillLayerSection] already maps [ParticleEffectsSettings.FILL_FIRE] to
 *  [Keys.PARTICLE_PRESET_FIRE]. */
private fun builtInPresetNameKey(id: String): String = when (id) {
    "off" -> Keys.PARTICLE_EFFECTS_BUILTIN_OFF
    "ice" -> Keys.PARTICLE_EFFECTS_BUILTIN_ICE
    "sand" -> Keys.PARTICLE_EFFECTS_BUILTIN_SAND
    "forest" -> Keys.PARTICLE_EFFECTS_BUILTIN_FOREST
    "neon" -> Keys.PARTICLE_EFFECTS_BUILTIN_NEON
    else -> Keys.PARTICLE_EFFECTS_BUILTIN_FIRE
}

/**
 * One card, at the top of the screen. Two rows of chips: [BuiltInEffectsPresets.ALL] first, then
 * whatever the user has saved themselves -- both apply to every region at once. The selected
 * chip is the preset the regions were last given ([ParticleEffectsSettings.appliedPresetId],
 * stored with them), whether or not they have been tweaked since; tweaked, the pulsing text at
 * the bottom of this card says what they drifted from. Highlighting used to depend on the five
 * regions still matching a preset exactly, which held only until the first slider moved, and the
 * drift notice on a value this composable alone remembered, which held only until the screen
 * was left -- so a look built on Fire showed no preset and no warning at all. [baseline] is
 * what both are measured against now; see [baselineFor] for how it is chosen when nothing has
 * been applied yet. Nothing here is scoped to one region -- picking which region's look to copy
 * only comes up inside [ParticleSavePresetDialog], and only because saving a new preset still
 * needs one unambiguous source, not because this card is organised by region.
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
        ) {
            TextButton(onClick = presetActions.onSave) { Text(strings[Keys.PARTICLE_EFFECTS_SAVE_PRESET]) }
            if (active != null) {
                TextButton(onClick = { presetActions.onRename(active) }) { Text(strings[Keys.THEME_RENAME]) }
                TextButton(onClick = { presetActions.onDelete(active) }) {
                    Text(strings[Keys.THEME_DELETE], color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (locked) {
            Text(
                strings[Keys.PARTICLE_EFFECTS_OFF_LOCKED_NOTE],
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        // Anything at all changed since the preset was applied -- a colour, a speed, a region
        // switched on -- is the one thing "custom" means here, and the moment a save makes sense.
        if (!baseline.matches(particleEffects)) {
            val name = when (baseline) {
                is Baseline.BuiltIn -> strings[builtInPresetNameKey(baseline.preset.id)]
                is Baseline.Saved -> baseline.entry.name
            }
            PulsingUnsavedText(strings.getString(Keys.PARTICLE_EFFECTS_UNSAVED_CHANGES, name))
        }
    }
}

/** "Unsaved changes since Fire" -- fades in and out rather than sitting still, since it is meant
 *  to be noticed once and then acted on (save, or pick a different preset), not read as a static
 *  label the way everything else on this card is. */
@Composable
private fun PulsingUnsavedText(text: String) {
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
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
    )
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
    baseline: ParticleRegionLook,
    locked: Boolean,
    customColours: Map<String, List<Int>>,
    onCustomColoursChange: (String, List<Int>) -> Unit,
    onRegionChange: ((ParticleRegionSettings) -> ParticleRegionSettings) -> Unit,
    preview: @Composable (() -> Unit)? = null,
) {
    // "Custom" on a layer means one thing only: it is not what the applied preset gave this
    // region -- see baselineFor. Off is a preset like any other here, with stock looks of its
    // own to differ from.
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
            // Eats taps over the whole card, and only taps: a clickable does not claim drags, so
            // the page still scrolls across it. No ripple, since nothing here is a target.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            )
        }
    }
}

/** How dim a locked region card is drawn -- Material's own disabled-content alpha. */
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
    // Every change below is a transform applied to whatever is *stored* at the moment the write
    // runs, never a snapshot of the region this composition was last handed: the store answers
    // asynchronously, and a snapshot written from a composition that has not caught up yet
    // silently reverted the change before it -- switch the region on, tap a style before the
    // switch has echoed back, and the switch flipped itself off again.
    val strings = LocalStrings.current
    SettingsSectionCard(title) {
        SwitchRow(
            title = strings[Keys.PARTICLE_EFFECTS_ENABLE],
            subtitle = strings[Keys.PARTICLE_EFFECTS_ENABLE_NOTE],
            checked = region.enabled,
        ) { value -> onRegionChange { it.copy(enabled = value) } }

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

/** [layer]'s own outline style, live, traced around the chip while it is the selected one --
 *  see [ParticleChipPreview]'s own doc for why this is the real particle engine rather than a
 *  second, separately-drawn animation. Never wrapped for a chip that is not currently selected:
 *  nothing has been "set" for a style nobody has picked yet, so there is nothing truthful to
 *  preview there.
 *
 *  [FilterChipDefaults.shape] is read once, here, and handed to both [PickerChip] and
 *  [ParticleChipPreview] -- the one real shape, not a corner radius this composable guesses at
 *  and that [PickerChip]'s own chip could quietly stop matching later (see [PickerChip]'s own
 *  doc for why that already happened once).
 *
 *  Drawn *over* [PickerChip], not behind it: fill spawns everywhere inside the shape, not just
 *  its edge, and a chip's own container is opaque, so a preview sitting behind it would show
 *  only the sliver of each particle that happens to escape past the corners. The chip is a plain
 *  `FilterChip` with no click handling of its own added on top, so the tap still reaches it
 *  through the particle view above -- confirmed on-device, not just assumed. */
@Composable
private fun OutlineTypeChip(label: String, layer: ParticleOutlineLayer, selected: Boolean, onClick: () -> Unit) {
    PreviewedChip(label, selected, onClick) { shape ->
        // Not clipped to the chip: every outline style draws just outside it now.
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
 * A [PickerChip] whose layout box is exactly its visible surface, with [preview] laid over it
 * when [selected]. Material's chip pads its own layout out to the 48dp minimum touch target
 * while drawing a 32dp surface -- so a preview sized to "the chip" was sized to the touch
 * target, and the traced outline ran 8dp outside the surface on every side. The particle
 * element has to be the drawn shape, nothing else (see [com.borderkeys.ime.fx.ParticleElement]),
 * so the minimum is switched off for these chips and the box the preview fills *is* the surface.
 * Applied to every chip in these rows -- unselected ones, and the "None" chip that never
 * previews anything ([selected] false, [highlighted] true) -- so no chip in a row is taller
 * than its neighbours and selecting one never shifts the row.
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
 * Outline's own type picklist (None plus its three styles), and -- only while a real style is
 * chosen, since None has nothing to colour or pace -- its colours, speed, density and width.
 *
 * [custom] is the caller's verdict on whether this layer still is what the applied preset gave
 * it -- see [RegionCard]; the badge here only shows it.
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
    AdvancedSection {
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
 * Fill's own type picklist -- None plus the original five presets. Otherwise the same shape as
 * [OutlineLayerSection], minus the width axis Fill has no equivalent for: picking None hides
 * colour/speed/density the same way it does for Outline, and a region can now run with only its
 * Outline layer showing.
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
    AdvancedSection {
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

/** Renaming an existing preset needs nothing beyond a name -- unlike saving a new one, it is
 *  never ambiguous which look is being renamed. */
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

/**
 * Saving a *new* preset is not just a name: a custom preset is one (outline, fill) pair, so
 * saving one still has to ask which region's pair to copy -- the one piece of per-region choice
 * this feature has left, deliberately confined to this dialog rather than sitting in
 * [MyPresetsCard] itself, where it would read as "presets are still organised by region" when
 * they are not.
 */
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
