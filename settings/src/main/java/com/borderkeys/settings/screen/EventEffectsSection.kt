// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.borderkeys.data.theme.EffectEvent
import com.borderkeys.data.theme.EffectFrequency
import com.borderkeys.data.theme.EffectSetting
import com.borderkeys.data.theme.EffectsSettings
import com.borderkeys.effects.EffectStyle
import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.ColourRow
import com.borderkeys.settings.PickerChip
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.SwitchRow

/**
 * The effects that answer an *event* -- a word learned, a correction applied -- as opposed to
 * the particle cards below, which decorate a *surface*.
 *
 * One card per event, each the same three questions: which animation, what colour, how often.
 * A card whose animation is Off dims and stops taking input, the same treatment a particle
 * region gets when its own switch is off, and for the same reason: colour and frequency are
 * about how it looks when it plays, and it does not play.
 */
@Composable
fun EventEffectsSection(
    effects: EffectsSettings,
    customColours: Map<String, List<Int>>,
    onCustomColoursChange: (String, List<Int>) -> Unit,
    onChange: ((EffectsSettings) -> EffectsSettings) -> Unit,
) {
    val strings = LocalStrings.current

    SettingsSectionCard(strings[Keys.EFFECTS_TITLE]) {
        SwitchRow(
            title = strings[Keys.EFFECTS_ENABLE],
            subtitle = strings[Keys.EFFECTS_ENABLE_NOTE],
            checked = effects.enabled,
        ) { value -> onChange { it.copy(enabled = value) } }
    }

    for (event in EffectEvent.entries) {
        EventEffectCard(
            title = strings[titleKeyFor(event)],
            setting = effects.forEvent(event),
            colourKey = effectColourKey(event),
            locked = !effects.enabled,
            customColours = customColours,
            onCustomColoursChange = onCustomColoursChange,
            onSettingChange = { change ->
                onChange { it.withEvent(event, change(it.forEvent(event))) }
            },
        )
    }
}

@Composable
private fun EventEffectCard(
    title: String,
    setting: EffectSetting,
    colourKey: String,
    locked: Boolean,
    customColours: Map<String, List<Int>>,
    onCustomColoursChange: (String, List<Int>) -> Unit,
    onSettingChange: ((EffectSetting) -> EffectSetting) -> Unit,
) {
    val strings = LocalStrings.current
    Box {
        Box(modifier = Modifier.alpha(if (locked) EVENT_LOCKED_ALPHA else 1f)) {
            SettingsSectionCard(title) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PickerChip(strings[Keys.EFFECTS_STYLE_OFF], !setting.enabled) {
                        onSettingChange { it.copy(style = EffectSetting.OFF) }
                    }
                    for (style in EffectStyle.entries) {
                        PickerChip(strings[labelKeyFor(style)], setting.style == style.name) {
                            onSettingChange { it.copy(style = style.name) }
                        }
                    }
                }

                // Colour and frequency describe how it plays, so they follow the animation
                // being chosen at all rather than sitting live above an Off card.
                Box {
                    Box(modifier = Modifier.alpha(if (setting.enabled) 1f else EVENT_LOCKED_ALPHA)) {
                        Column {
                            ColourRow(
                                label = strings[Keys.PARTICLE_EFFECTS_PRIMARY_COLOUR],
                                current = setting.colour,
                                customColours = customColours[colourKey].orEmpty(),
                                onCustomColoursChange = { colours ->
                                    onCustomColoursChange(colourKey, colours)
                                },
                            ) { colour -> onSettingChange { it.copy(colour = colour) } }

                            Text(
                                strings[Keys.EFFECTS_FREQUENCY],
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                for ((frequency, labelKey) in FREQUENCY_LABELS) {
                                    PickerChip(strings[labelKey], setting.frequency == frequency) {
                                        onSettingChange { it.withFrequency(frequency) }
                                    }
                                }
                            }
                        }
                    }
                    if (!setting.enabled) {
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
        if (locked) {
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

/** Material's own disabled-content alpha, the same the particle cards dim to. */
private const val EVENT_LOCKED_ALPHA = 0.38f

/**
 * What each style is called, in the user's own language.
 *
 * A `when` over the enum rather than a list of names: the chips are built from
 * [EffectStyle.entries], so a style added to the module makes this stop compiling until it has
 * a label, instead of appearing as a chip with a missing string or not appearing at all.
 */
private fun labelKeyFor(style: EffectStyle): String = when (style) {
    EffectStyle.RiseFade -> Keys.EFFECTS_STYLE_RISE_FADE
    EffectStyle.Slide -> Keys.EFFECTS_STYLE_SLIDE
    EffectStyle.LampHide -> Keys.EFFECTS_STYLE_LAMP_HIDE
    EffectStyle.Pop -> Keys.EFFECTS_STYLE_POP
    EffectStyle.Drift -> Keys.EFFECTS_STYLE_DRIFT
}

private val FREQUENCY_LABELS = listOf(
    EffectFrequency.FirstTime to Keys.EFFECTS_FREQUENCY_FIRST,
    EffectFrequency.EveryTime to Keys.EFFECTS_FREQUENCY_EVERY,
)

private fun titleKeyFor(event: EffectEvent): String = when (event) {
    EffectEvent.SwipeAccepted -> Keys.EFFECTS_ON_SWIPE_ACCEPTED
    EffectEvent.LearnedWord -> Keys.EFFECTS_ON_LEARNED_WORD
    EffectEvent.AutocorrectApplied -> Keys.EFFECTS_ON_AUTOCORRECT
    EffectEvent.CorrectionReverted -> Keys.EFFECTS_ON_REVERTED
    EffectEvent.SuggestionPicked -> Keys.EFFECTS_ON_SUGGESTION_PICKED
}

/** Where a card's own swatches live in the shared custom-colour map. */
private fun effectColourKey(event: EffectEvent): String = "effect_${event.name}"
