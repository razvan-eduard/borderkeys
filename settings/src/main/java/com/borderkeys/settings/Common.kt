// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import com.borderkeys.i18n.Keys

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * `onClick` is deliberately the **last** parameter, after the composable `trailing` slot, which
 * is the opposite of the usual Compose convention. The convention caused a real bug: with
 * `trailing` last, `SettingRow(strings[Keys.COMMON_ABOUT], "…") { open(Screen.About) }` bound the trailing lambda
 * to the *composable* slot, which runs during composition rather than on a click — so every row
 * navigated the moment it was drawn and the application opened on whichever row came last.
 * Putting the click last means the natural call site is the correct one.
 */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
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
            if (subtitle != null) {
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
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
