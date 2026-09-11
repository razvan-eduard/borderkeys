// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import com.borderkeys.i18n.Keys

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

/**
 * One choice among a small fixed set, shown as a chip that fills in when it is the current one.
 *
 * Every mode/placement/size picker in this app -- position, quick-action size, digit position,
 * text size and the rest -- turned out to be exactly this and nothing more once each screen's own
 * copy was compared against the others: a label, whether it is the one currently chosen, and what
 * picking it does. The differences between the screens were in what `onClick` writes, never in
 * what the chip itself is.
 */
@Composable
fun PickerChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
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

/**
 * Moves [from] to [to], clamped, and returns the new order.
 *
 * Shared by every bar with buttons a person reorders -- quick actions, the draft box's own bar --
 * rather than typed out again per screen: the logic is the same list regardless of what the ids
 * in it happen to mean.
 */
fun move(ids: List<Int>, from: Int, to: Int): List<Int> {
    if (from !in ids.indices) {
        return ids
    }
    val target = to.coerceIn(0, ids.size - 1)
    val mutable = ids.toMutableList()
    mutable.add(target, mutable.removeAt(from))
    return mutable
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
 * The four switches for what a backup file carries -- settings, dictionary, languages,
 * clipboard -- shared by the screen that writes a file and the one that sends everything to a
 * nearby device directly, since both are choosing the same four parts of the same
 * [BackupRepository.Parts].
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
    onChange: (Float) -> Unit,
) {
    val strings = LocalStrings.current
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (value != default) {
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
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
