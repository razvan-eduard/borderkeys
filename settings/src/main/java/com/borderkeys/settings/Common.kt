// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import com.borderkeys.i18n.Keys

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.borderkeys.data.DataGraph
import com.borderkeys.data.backup.BackupRepository
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardTheme
import com.borderkeys.data.theme.ParticleEffectsSettings
import com.borderkeys.keyboard.R
import kotlinx.coroutines.launch

/**
 * The three shapes every settings screen is built from.
 *
 * Written once here rather than reached for from a component library, because there are three of
 * them and each is a Row with a Text in it.
 */

@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
    )
}

/**
 * A row with a title, an explanation and something on the right.
 *
 * The explanation is not optional by accident. Every setting in this application either changes
 * what is stored about the user or trades one thing for another, and a switch with only a name
 * makes the user guess which.
 *
 * `onClick` is deliberately the **last** parameter, after the composable `trailing` and `content`
 * slots, which is the opposite of the usual Compose convention. The convention caused a real
 * bug: with `trailing` last, `SettingRow(strings[Keys.COMMON_ABOUT], "…") { open(Screen.About) }`
 * bound the trailing lambda to the *composable* slot, which runs during composition rather than
 * on a click — so every row navigated the moment it was drawn and the application opened on
 * whichever row came last. Putting the click last means the natural call site is the correct one
 * -- which is also why `content` takes a named argument at its own call sites rather than the
 * trailing-lambda spot: the same bug, one parameter over.
 *
 * `content`, when given, replaces `subtitle`'s plain `Text` with whatever it draws instead --
 * the one thing a `String` cannot carry, used by the two rows in Settings that want their
 * subtitle as a tappable link rather than as text.
 */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (content != null) {
                content()
            } else if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * The rows of a card that are seldom needed -- calibration values, workarounds for particular
 * apps, matters of taste settled once -- folded under one "Advanced settings" line, closed by
 * default. A card then leads with what changes how typing feels and keeps the rest a tap away
 * rather than gone. Open or closed is remembered across rotation and nothing longer: every
 * visit to a screen starts with them folded, which is the point.
 */
@Composable
fun AdvancedSection(content: @Composable ColumnScope.() -> Unit) {
    val strings = LocalStrings.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            strings[Keys.COMMON_ADVANCED_SETTINGS],
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        // One icon in one place, in two states -- a chevron down while closed, up while open --
        // rather than a glyph rotated in place, which swung around its own centre.
        Icon(
            painter = painterResource(
                if (expanded) com.borderkeys.keyboard.R.drawable.bk_chevron_up
                else com.borderkeys.keyboard.R.drawable.bk_chevron_down,
            ),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
    AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        // Disabled shows the row greyed and unresponsive rather than hidden, so a setting
        // gated on another one is still discoverable, with a note to say what unlocks it.
        onClick = if (enabled) ({ onCheckedChange(!checked) }) else null,
        trailing = { Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange) },
    )
}

/**
 * Dims [content] and swallows every touch inside it, for a control that is turned off rather
 * than removed -- the setting it shows is still there and still stored, it simply cannot be
 * reached right now.
 *
 * A transparent [clickable] laid over the top rather than an `enabled` flag threaded through
 * whatever is inside: the theme screen's swatches and preset cards are drawn with plain
 * `Modifier.clickable` blocks, none of which have an `enabled` parameter to thread one through,
 * and one overlay is the same fix for all of them at once rather than a fix repeated at every
 * call site. [SettingRow] has the same gap -- it expresses "not clickable" by taking a null
 * `onClick` and dims nothing by itself.
 */
@Composable
fun Disableable(disabled: Boolean, content: @Composable () -> Unit) {
    Box {
        Column(modifier = Modifier.alpha(if (disabled) 0.4f else 1f)) { content() }
        if (disabled) {
            // Compose's own single-argument Box, which draws nothing -- the point of this one
            // is only ever to sit in front of everything else and take the touch.
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

/**
 * One choice among a small fixed set, shown as a chip that fills in when it is the current one.
 *
 * Every mode/placement/size picker in this app -- position, quick-action size, digit position,
 * text size and the rest -- turned out to be exactly this and nothing more once each screen's own
 * copy was compared against the others: a label, whether it is the one currently chosen, and what
 * picking it does. The differences between the screens were in what `onClick` writes, never in
 * what the chip itself is.
 *
 * [shape] defaults to the same [FilterChipDefaults.shape] an unstyled [FilterChip] already draws
 * with on its own, so every ordinary caller is unaffected. It exists as a parameter at all so a
 * caller that draws something *else* meant to trace this exact chip's own outline --
 * `com.borderkeys.settings.screen.EffectsScreen`'s particle-style preview, for instance -- can
 * read the real value once and hand the identical [androidx.compose.ui.graphics.Shape] to both,
 * rather than that second drawing guessing at a corner radius of its own that this chip's real
 * shape could change out from under it.
 */
@Composable
fun PickerChip(label: String, selected: Boolean, shape: Shape = FilterChipDefaults.shape, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, shape = shape)
}

/**
 * Writes a change to [KeyboardPreferences] without a screen naming `DataGraph.themes`, a
 * `rememberCoroutineScope` and its own local `update` function to do it -- every screen this
 * app has was typing the same three lines to get here.
 */
@Composable
fun rememberPreferencesUpdater(): ((KeyboardPreferences) -> KeyboardPreferences) -> Unit {
    val themes = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    return { transform -> scope.launch { themes.updatePreferences(transform) } }
}

/** The same, for [KeyboardTheme] -- [ThemeScreen][com.borderkeys.settings.screen.ThemeScreen]'s
 *  own equivalent of [rememberPreferencesUpdater]. */
@Composable
fun rememberThemeUpdater(): ((KeyboardTheme) -> KeyboardTheme) -> Unit {
    val themes = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    return { transform -> scope.launch { themes.updateTheme(transform) } }
}

/** The same, for [ParticleEffectsSettings] --
 *  [EffectsScreen][com.borderkeys.settings.screen.EffectsScreen]'s own equivalent of
 *  [rememberPreferencesUpdater]. */
@Composable
fun rememberParticleEffectsUpdater(): ((ParticleEffectsSettings) -> ParticleEffectsSettings) -> Unit {
    val themes = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    return { transform -> scope.launch { themes.updateParticleEffects(transform) } }
}

/**
 * Moves [from] to [to], clamped, and returns the new order.
 *
 * Shared by every bar with buttons a person reorders -- quick actions, the draft box's own bar --
 * rather than typed out again per screen: the logic is the same list regardless of what the ids
 * in it happen to mean.
 */
fun <T> move(ids: List<T>, from: Int, to: Int): List<T> {
    if (from !in ids.indices) {
        return ids
    }
    val target = to.coerceIn(0, ids.size - 1)
    val mutable = ids.toMutableList()
    mutable.add(target, mutable.removeAt(from))
    return mutable
}

/**
 * One entry of a list a person orders: its label, an optional icon, and either the three
 * ordering controls -- to the top, one up, remove -- or, with [onAdd], a whole row that adds
 * it. Shared by every such list in the settings; what the entries are is the caller's.
 */
@Composable
fun ReorderRow(
    label: String,
    index: Int,
    icon: Int? = null,
    onMoveTop: () -> Unit = {},
    onMoveUp: () -> Unit = {},
    onRemove: () -> Unit = {},
    onAdd: (() -> Unit)? = null,
) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onAdd != null) Modifier.clickable(onClick = onAdd) else Modifier)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(start = if (icon != null) 16.dp else 0.dp),
        )
        if (onAdd == null) {
            IconButton(onClick = onMoveTop, enabled = index > 0, modifier = Modifier.size(36.dp)) {
                Icon(
                    painter = painterResource(R.drawable.bk_reorder_top),
                    contentDescription = strings[Keys.QUICK_MOVE_TOP],
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onMoveUp, enabled = index > 0, modifier = Modifier.size(36.dp)) {
                Icon(
                    painter = painterResource(R.drawable.bk_reorder_up),
                    contentDescription = strings[Keys.QUICK_MOVE_UP],
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(
                    painter = painterResource(R.drawable.bk_reorder_remove),
                    contentDescription = strings[Keys.QUICK_REMOVE],
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * The package name of the other build, or null when this one has no sibling.
 *
 * Derived from this application's own name rather than written down twice: the assistant build
 * is the core one with a suffix, so one of them is the other with the suffix removed. Shared
 * rather than kept as two copies, which had already drifted -- one behind a named constant, one
 * with the suffix typed inline.
 */
fun siblingPackage(context: Context): String? {
    val self = context.packageName
    return if (self.endsWith(PLUS_SUFFIX)) self.removeSuffix(PLUS_SUFFIX) else null
}

private const val PLUS_SUFFIX = ".plus"

/**
 * The seven switches for what a backup file carries -- settings, theme, particle effects, size
 * and position, dictionary, languages, clipboard -- shared by the screen that writes a file and
 * the one that sends everything to a nearby device directly, since both are choosing the same
 * seven parts of the same [BackupRepository.Parts].
 */
@Composable
fun BackupPartSwitches(parts: BackupRepository.Parts, onChange: (BackupRepository.Parts) -> Unit) {
    val strings = LocalStrings.current
    SwitchRow(
        title = strings[Keys.BACKUP_PART_SETTINGS],
        subtitle = strings[Keys.BACKUP_PART_SETTINGS_NOTE],
        checked = parts.settings,
    ) { value -> onChange(parts.copy(settings = value)) }
    SwitchRow(
        title = strings[Keys.BACKUP_PART_THEME],
        subtitle = strings[Keys.BACKUP_PART_THEME_NOTE],
        checked = parts.theme,
    ) { value -> onChange(parts.copy(theme = value)) }
    SwitchRow(
        title = strings[Keys.BACKUP_PART_PARTICLE_EFFECTS],
        subtitle = strings[Keys.BACKUP_PART_PARTICLE_EFFECTS_NOTE],
        checked = parts.particleEffects,
    ) { value -> onChange(parts.copy(particleEffects = value)) }
    SwitchRow(
        title = strings[Keys.BACKUP_PART_SIZE_AND_POSITION],
        subtitle = strings[Keys.BACKUP_PART_SIZE_AND_POSITION_NOTE],
        checked = parts.sizeAndPosition,
    ) { value -> onChange(parts.copy(sizeAndPosition = value)) }
    SwitchRow(
        title = strings[Keys.BACKUP_PART_DICTIONARY],
        subtitle = strings[Keys.BACKUP_PART_DICTIONARY_NOTE],
        checked = parts.dictionary,
    ) { value -> onChange(parts.copy(dictionary = value)) }
    SwitchRow(
        title = strings[Keys.BACKUP_PART_LANGUAGES],
        subtitle = strings[Keys.BACKUP_PART_LANGUAGES_NOTE],
        checked = parts.languages,
    ) { value -> onChange(parts.copy(languages = value)) }
    SwitchRow(
        title = strings[Keys.BACKUP_PART_CLIPBOARD],
        subtitle = strings[Keys.BACKUP_PART_CLIPBOARD_NOTE],
        checked = parts.clipboard,
    ) { value -> onChange(parts.copy(clipboard = value)) }
}

/**
 * One row per part [detected] as actually present in a parsed import file -- never all seven
 * unconditionally the way [BackupPartSwitches]' own export-time rows are, since offering a tick
 * for a section the file does not carry would be an offer to import nothing.
 */
@Composable
fun BackupReviewChecklist(
    detected: BackupRepository.Parts,
    selected: BackupRepository.Parts,
    onChange: (BackupRepository.Parts) -> Unit,
) {
    val strings = LocalStrings.current
    Column {
        if (detected.settings) {
            BackupReviewRow(strings[Keys.BACKUP_PART_SETTINGS], selected.settings) {
                onChange(selected.copy(settings = it))
            }
        }
        if (detected.theme) {
            BackupReviewRow(strings[Keys.BACKUP_PART_THEME], selected.theme) {
                onChange(selected.copy(theme = it))
            }
        }
        if (detected.particleEffects) {
            BackupReviewRow(strings[Keys.BACKUP_PART_PARTICLE_EFFECTS], selected.particleEffects) {
                onChange(selected.copy(particleEffects = it))
            }
        }
        if (detected.sizeAndPosition) {
            BackupReviewRow(strings[Keys.BACKUP_PART_SIZE_AND_POSITION], selected.sizeAndPosition) {
                onChange(selected.copy(sizeAndPosition = it))
            }
        }
        if (detected.dictionary) {
            BackupReviewRow(strings[Keys.BACKUP_PART_DICTIONARY], selected.dictionary) {
                onChange(selected.copy(dictionary = it))
            }
        }
        if (detected.languages) {
            BackupReviewRow(strings[Keys.BACKUP_PART_LANGUAGES], selected.languages) {
                onChange(selected.copy(languages = it))
            }
        }
        if (detected.clipboard) {
            BackupReviewRow(strings[Keys.BACKUP_PART_CLIPBOARD], selected.clipboard) {
                onChange(selected.copy(clipboard = it))
            }
        }
    }
}

@Composable
private fun BackupReviewRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Divider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    )
}

/**
 * A line of text with one substring turned into a tappable link that opens the browser.
 *
 * Used for a model's source: something like "huggingface.co/Qwen/Qwen3-0.6B-GGUF" is a place a
 * person can go to get the file, and text they cannot tap is a place they have to retype by hand
 * instead. [link] is matched against [text] verbatim -- when it is not found the line is shown
 * as plain text rather than silently dropping the link, since a substring not appearing is a
 * caller mistake worth being visible about.
 *
 * No `https://` in [link] itself: the sources this renders are written short, for reading, and
 * the scheme is added only for the URL actually opened, not for what is displayed.
 */
@Composable
fun LinkedText(text: String, link: String) {
    val start = text.indexOf(link)
    if (start < 0) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = buildAnnotatedString {
        append(text.substring(0, start))
        withLink(
            LinkAnnotation.Url(
                url = "https://$link",
                styles = TextLinkStyles(
                    style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                ),
            ),
        ) {
            append(link)
        }
        append(text.substring(start + link.length))
    }
    Text(
        annotated,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun Explanation(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

/**
 * What [Explanation] cannot say: this setting can do something to your text, your data or your
 * device that a caption in the same colour as everything else would not prepare you for.
 *
 * A filled container rather than coloured text -- this codebase already reserves
 * [MaterialTheme.colorScheme.error] for destructive button labels, and reusing that alone here
 * would make a warning read the same as a "Delete" button standing still. This is the next step
 * up from that, short of a dialog that blocks until dismissed, which is more friction than a
 * setting someone has not even turned on yet has earned.
 */
@Composable
fun CautionNote(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.bk_icon_warning),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/**
 * A slider that knows its own default, and offers to go back to it.
 *
 * The reset control -- a small "x" beside the value -- only appears once [value] has actually
 * moved away from [default]. Always showing it would mean a control that usually does nothing,
 * which on a row already this narrow reads as "is this broken" before it reads as "reset".
 *
 * The one slider every screen with a numeric setting reads from, rather than each screen
 * building its own copy: Size and Theme both used to, with no way to answer "how far is this
 * from stock" except tapping through every one by hand.
 */
@Composable
fun DefaultableSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float,
    /** Discrete stops between the ends, the same meaning Compose's own `Slider.steps` has --
     *  0 for a continuous drag, passed through by the two callers stepping over a fixed list. */
    steps: Int = 0,
    /** False dims the label and disables both the slider and its reset button, for a setting
     *  that reads normally but has no effect right now -- a value another switch on the same
     *  screen is currently bypassing entirely, not one that is merely at its default. */
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    val strings = LocalStrings.current
    val labelColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = labelColor,
                modifier = Modifier.weight(1f),
            )
            if (enabled && value != default) {
                IconButton(onClick = { onChange(default) }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        painter = painterResource(android.R.drawable.ic_menu_close_clear_cancel),
                        contentDescription = strings[Keys.COMMON_RESET_TO_DEFAULT],
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Slider(
            value = value.coerceIn(range),
            valueRange = range,
            steps = steps,
            onValueChange = onChange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
