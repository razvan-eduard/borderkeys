// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import com.borderkeys.i18n.Keys

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import com.borderkeys.data.DataGraph
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.borderkeys.data.theme.EffectsSettings
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.State
import com.borderkeys.data.backup.BackupRepository
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardTheme
import com.borderkeys.data.theme.ParticleEffectsSettings
import com.borderkeys.keyboard.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The shapes every settings screen is built from. */

@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.searchTarget(text).padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
    )
}

/**
 * The search result the screen on top was opened for, or null: the row, label or heading whose
 * title it names scrolls into view and glows for a moment, and the Advanced fold it sits under
 * starts open.
 */
val LocalSearchTarget = compositionLocalOf<SettingsSearch.Match?> { null }

/**
 * Makes the element titled [title] the search target's landing place: once placed, it scrolls
 * into view and its background glows for [HIGHLIGHT_MILLIS]. Unchanged when it is not the target.
 */
@Composable
fun Modifier.searchTarget(title: String): Modifier {
    val target = LocalSearchTarget.current
    if (target == null || !target.matchesTitle(title)) {
        return this
    }
    val requester = remember { BringIntoViewRequester() }
    var placed by remember { mutableStateOf(false) }
    var highlighted by remember { mutableStateOf(false) }
    LaunchedEffect(target) {
        snapshotFlow { placed }.first { it }
        requester.bringIntoView()
        highlighted = true
        delay(HIGHLIGHT_MILLIS)
        highlighted = false
    }
    val colour by animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        animationSpec = tween(HIGHLIGHT_FADE_MILLIS),
    )
    return this
        .bringIntoViewRequester(requester)
        .onPlaced { placed = true }
        .background(colour)
}

/** How long a search target glows, and how long the glow takes to come and go. */
private const val HIGHLIGHT_MILLIS = 4_000L
private const val HIGHLIGHT_FADE_MILLIS = 1_000

/** A setting's title above a control that has no row of its own: a picker's chips or a slider. */
@Composable
fun SettingLabel(text: String, color: Color = Color.Unspecified) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier.searchTarget(text).padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

/**
 * A row with a title, an explanation and something on the right. `onClick` is the last parameter,
 * after the composable `trailing` and `content` slots, so a trailing lambda is the click; pass
 * `content` by name. `content`, when given, replaces the subtitle's `Text`.
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
            .searchTarget(title)
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
 * A card's seldom-needed rows, folded under one "Advanced settings" line that starts closed on
 * every visit, open when the search target sits inside it, and stays as set across rotation.
 * [summary] names what the fold holds.
 */
@Composable
fun AdvancedSection(summary: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val strings = LocalStrings.current
    val holdsTarget = summary != null && summary == LocalSearchTarget.current?.advancedSummary
    var expanded by rememberSaveable { mutableStateOf(holdsTarget) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                strings[Keys.COMMON_ADVANCED_SETTINGS],
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // A chevron, down while closed and up while open.
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
        // Disabled: greyed and unresponsive, not hidden.
        onClick = if (enabled) ({ onCheckedChange(!checked) }) else null,
        trailing = { Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange) },
    )
}

/**
 * Dims [content] and takes every touch inside it with a transparent [clickable] laid over it, for
 * a control that is turned off rather than removed.
 */
@Composable
fun Disableable(disabled: Boolean, content: @Composable () -> Unit) {
    Box {
        Column(modifier = Modifier.alpha(if (disabled) 0.4f else 1f)) { content() }
        if (disabled) {
            // The overlay that takes the touch; it draws nothing.
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
 * [shape], [FilterChipDefaults.shape] by default, can be shared with a drawing that traces the
 * chip's outline.
 */
@Composable
fun PickerChip(label: String, selected: Boolean, shape: Shape = FilterChipDefaults.shape, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, shape = shape)
}

/** The animation switches, as stored now and on every change. */
@Composable
fun rememberEffects(): State<EffectsSettings> {
    val themes = remember { DataGraph.themes }
    val effects = remember { themes.preferences.map { it.effects }.distinctUntilChanged() }
    return effects.collectAsStateWithLifecycle(initialValue = remember { themes.currentPreferences().effects })
}

/** Writes a change to [KeyboardPreferences]. */
@Composable
fun rememberPreferencesUpdater(): ((KeyboardPreferences) -> KeyboardPreferences) -> Unit {
    val themes = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    return { transform -> scope.launch { themes.updatePreferences(transform) } }
}

/** Writes a change to [KeyboardTheme]. */
@Composable
fun rememberThemeUpdater(): ((KeyboardTheme) -> KeyboardTheme) -> Unit {
    val themes = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    return { transform -> scope.launch { themes.updateTheme(transform) } }
}

/** Writes a change to [ParticleEffectsSettings]. */
@Composable
fun rememberParticleEffectsUpdater(): ((ParticleEffectsSettings) -> ParticleEffectsSettings) -> Unit {
    val themes = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    return { transform -> scope.launch { themes.updateParticleEffects(transform) } }
}

/** Moves [from] to [to], clamped, and returns the new order. */
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

/** The core build's package name when this is the plus build, or null. */
fun siblingPackage(context: Context): String? {
    val self = context.packageName
    return if (self.endsWith(PLUS_SUFFIX)) self.removeSuffix(PLUS_SUFFIX) else null
}

private const val PLUS_SUFFIX = ".plus"

/**
 * The seven switches for what a backup carries: settings, theme, particle effects, size and
 * position, dictionary, languages, clipboard. Shared by the file export and the direct transfer.
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

/** One row per part [detected] in a parsed import file. */
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
 * A line of text with [link], matched verbatim, turned into a tappable link that opens the
 * browser; without a match the line is plain text. [link] has no scheme; `https://` is added to
 * the URL opened.
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
 * A warning in a filled container, for a setting that can do something to your text, your data
 * or your device.
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
 * A slider with a reset control, a small "x" beside the value, shown once [value] differs from
 * [default].
 */
@Composable
fun DefaultableSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float,
    /** Discrete stops between the ends, as `Slider.steps`; 0 for a continuous drag. */
    steps: Int = 0,
    /**
     * False dims the label and disables the slider and its reset, for a setting another switch
     * currently bypasses.
     */
    enabled: Boolean = true,
    /** Runs when a drag ends and after a reset, once [onChange] has the value. */
    onChangeFinished: (() -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        SliderLabel(label, enabled, showReset = value != default) {
            onChange(default)
            onChangeFinished?.invoke()
        }
        Slider(
            value = value.coerceIn(range),
            valueRange = range,
            steps = steps,
            onValueChange = onChange,
            enabled = enabled,
            onValueChangeFinished = onChangeFinished,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The line above a slider: [label], dimmed unless [enabled], and a reset "x" when [showReset]. */
@Composable
private fun SliderLabel(label: String, enabled: Boolean, showReset: Boolean, onReset: () -> Unit) {
    val strings = LocalStrings.current
    val labelColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = labelColor,
            modifier = Modifier.weight(1f),
        )
        if (enabled && showReset) {
            IconButton(onClick = onReset, modifier = Modifier.size(28.dp)) {
                Icon(
                    painter = painterResource(android.R.drawable.ic_menu_close_clear_cancel),
                    contentDescription = strings[Keys.COMMON_RESET_TO_DEFAULT],
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A slider of a few fixed values, each a node at a fixed place on the track; a tap or a drag
 * lands on the nearest node. [label] sits above it with [DefaultableSlider]'s reset control once
 * [value] is not [default], and a dot under the track marks where [default] sits.
 */
@Composable
fun NodeSlider(
    label: String,
    options: List<Int>,
    value: Int,
    default: Int,
    /** False dims it and takes no touch, as for [DefaultableSlider]. */
    enabled: Boolean = true,
    onPick: (Int) -> Unit,
) {
    val selected = KeyboardPreferences.nearestStep(options, value)
    val defaultIndex = KeyboardPreferences.nearestStep(options, default)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentOptions by rememberUpdatedState(options)
    val currentSelected by rememberUpdatedState(selected)
    val currentPick by rememberUpdatedState(onPick)
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    val active = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
    val track = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
    val ring = MaterialTheme.colorScheme.outline.copy(alpha = alpha)
    val mark = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        SliderLabel(label, enabled, showReset = value != default) { onPick(default) }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(NODE_SLIDER_HEIGHT)
                .semantics {
                    stateDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        selected.toFloat(),
                        0f..(options.size - 1).coerceAtLeast(0).toFloat(),
                        (options.size - 2).coerceAtLeast(0),
                    )
                    if (enabled) {
                        setProgress { target ->
                            val index = target.roundToInt().coerceIn(options.indices)
                            if (index != selected) {
                                onPick(options[index])
                            }
                            true
                        }
                    } else {
                        disabled()
                    }
                }
                .pointerInput(enabled, rtl) {
                    if (!enabled) {
                        return@pointerInput
                    }
                    detectTapGestures { offset ->
                        val index = nodeAt(
                            offset.x, currentOptions.size, size.width.toFloat(), NODE_THUMB_RADIUS.toPx(), rtl,
                        )
                        if (index != currentSelected) {
                            currentPick(currentOptions[index])
                        }
                    }
                }
                .pointerInput(enabled, rtl) {
                    if (!enabled) {
                        return@pointerInput
                    }
                    var last = -1
                    detectHorizontalDragGestures(onDragStart = { last = currentSelected }) { change, _ ->
                        change.consume()
                        val index = nodeAt(
                            change.position.x, currentOptions.size, size.width.toFloat(), NODE_THUMB_RADIUS.toPx(), rtl,
                        )
                        if (index != last) {
                            last = index
                            currentPick(currentOptions[index])
                        }
                    }
                },
        ) {
            val count = options.size
            if (count == 0) {
                return@Canvas
            }
            val thumb = NODE_THUMB_RADIUS.toPx()
            val y = size.height / 2f
            fun at(index: Int) = Offset(nodeX(index, count, size.width, thumb, rtl), y)
            val stroke = NODE_TRACK_WIDTH.toPx()
            drawLine(track, at(0), at(count - 1), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(active, at(0), at(selected), strokeWidth = stroke, cap = StrokeCap.Round)
            val node = NODE_RADIUS.toPx()
            for (index in 0 until count) {
                if (index <= selected) {
                    drawCircle(active, radius = node, center = at(index))
                } else {
                    drawCircle(track, radius = node, center = at(index))
                    drawCircle(ring, radius = node, center = at(index), style = Stroke(NODE_RING_WIDTH.toPx()))
                }
            }
            drawCircle(active, radius = thumb, center = at(selected))
            val dot = NODE_DEFAULT_MARK_RADIUS.toPx()
            drawCircle(mark, radius = dot, center = at(defaultIndex).copy(y = y + thumb + dot * 2))
        }
    }
}

/**
 * One of [choices], each a value and its name, as a [NodeSlider] whose label is the chosen one's
 * name. A [value] not among them reads as the first.
 */
@Composable
fun <T> ChoiceSlider(
    choices: List<Pair<T, String>>,
    value: T,
    default: T,
    enabled: Boolean = true,
    onPick: (T) -> Unit,
) {
    val selected = choices.indexOfFirst { it.first == value }.coerceAtLeast(0)
    NodeSlider(
        label = choices.getOrNull(selected)?.second.orEmpty(),
        options = choices.indices.toList(),
        value = selected,
        default = choices.indexOfFirst { it.first == default }.coerceAtLeast(0),
        enabled = enabled,
    ) { index -> onPick(choices[index].first) }
}

/** Where node [index] of [count] sits across [width], [inset] in from each end, mirrored when [rtl]. */
internal fun nodeX(index: Int, count: Int, width: Float, inset: Float, rtl: Boolean): Float {
    val fraction = if (count <= 1) 0f else index.toFloat() / (count - 1)
    val x = inset + (width - 2 * inset) * fraction
    return if (rtl) width - x else x
}

/** The node of [count] nearest [x], as [nodeX] places them. */
internal fun nodeAt(x: Float, count: Int, width: Float, inset: Float, rtl: Boolean): Int {
    if (count <= 1) {
        return 0
    }
    val fromStart = if (rtl) width - x else x
    val span = (width - 2 * inset).coerceAtLeast(1f)
    val fraction = ((fromStart - inset) / span).coerceIn(0f, 1f)
    return (fraction * (count - 1)).roundToInt()
}

/** [NodeSlider]'s height, track, nodes, thumb, ring and default mark. */
private val NODE_SLIDER_HEIGHT = 44.dp
private val NODE_TRACK_WIDTH = 4.dp
private val NODE_RADIUS = 6.dp
private val NODE_THUMB_RADIUS = 11.dp
private val NODE_RING_WIDTH = 1.5.dp
private val NODE_DEFAULT_MARK_RADIUS = 2.dp

/** Every setting's default, which a [ChoiceSlider] marks and resets to. */
val DEFAULT_PREFERENCES = KeyboardPreferences()

/** A disabled control's alpha, as Material dims it. */
private const val DISABLED_ALPHA = 0.38f
