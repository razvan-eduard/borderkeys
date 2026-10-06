// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.QuickAction
import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.Screen

/**
 * The tour of what the keyboard can do beyond the onboarding's settings, opened from Home:
 * [FeatureTour] on a page of its own.
 */
@Composable
fun FeaturesScreen(modifier: Modifier = Modifier, hasAssistant: Boolean, open: (Screen) -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        FeatureTour(hasAssistant, open)
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Every feature the onboarding does not set, as a card with a small drawn preview, a line on
 * what it does, its default, and the screen it is switched on from, grouped under four lightly
 * coloured headings. A card with a screen of its own opens that screen when tapped.
 */
@Composable
internal fun FeatureTour(hasAssistant: Boolean, open: (Screen) -> Unit) {
    val strings = LocalStrings.current
    val defaults = remember { KeyboardPreferences() }
    val groups = remember(strings, hasAssistant) { featureGroups(strings, defaults, hasAssistant) }

    Text(
        strings[Keys.FEATURES_INTRO],
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    for (group in groups) {
        val accent = group.accent()
        Text(
            group.title,
            style = MaterialTheme.typography.titleMedium,
            color = accent,
            modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
        )
        for (feature in group.features) {
            FeatureCard(feature, accent, open)
        }
    }
}

// ---- the model ----------------------------------------------------------------------------

private class Feature(
    val title: String,
    val text: String,
    val default: String,
    val where: String,
    val target: Screen?,
    val preview: @Composable (Color) -> Unit,
)

private class FeatureGroup(
    val title: String,
    val accent: @Composable () -> Color,
    val features: List<Feature>,
)

private fun featureGroups(
    strings: LanguageManager,
    defaults: KeyboardPreferences,
    hasAssistant: Boolean,
): List<FeatureGroup> {
    fun onOff(on: Boolean) = strings[if (on) Keys.FEATURES_DEFAULT_ON else Keys.FEATURES_DEFAULT_OFF]
    fun where(screen: String, item: String) = strings.getString(Keys.FEATURES_WHERE, strings[screen], item)
    val typing = strings[Keys.SCREEN_SUGGESTIONS_AND_CORRECTIONS]
    val layout = Keys.SCREEN_LAYOUT
    val dictionary = Keys.SCREEN_DICTIONARY_AND_HEATMAP

    val typingGroup = FeatureGroup(strings[Keys.FEATURES_GROUP_TYPING], { MaterialTheme.colorScheme.primary }, listOf(
        Feature(
            strings[Keys.FEATURE_STRIP_TITLE], strings[Keys.FEATURE_STRIP_TEXT],
            onOff(defaults.showSuggestionStrip),
            strings.getString(Keys.FEATURES_WHERE, typing, strings[Keys.CORRECTIONS_SHOW_THE_SUGGESTION_STRIP]),
            Screen.Typing,
        ) { accent -> ChipsPreview(strings[Keys.FEATURE_SAMPLE_STRIP].split(SAMPLE_SEPARATOR), accent, outlined = 0) },
        Feature(
            strings[Keys.CORRECTIONS_RARE_WORDS], strings[Keys.CORRECTIONS_RARE_WORDS_NOTE],
            strings[Keys.CORRECTIONS_RARE_WORDS_LISTED],
            strings.getString(Keys.FEATURES_WHERE, typing, strings[Keys.CORRECTIONS_RARE_WORDS]),
            Screen.Typing,
        ) { accent ->
            ChipsPreview(
                listOf(
                    strings[Keys.CORRECTIONS_RARE_WORDS_LISTED],
                    strings[Keys.CORRECTIONS_RARE_WORDS_SOME],
                    strings[Keys.CORRECTIONS_RARE_WORDS_MANY],
                ),
                accent,
                outlined = 0,
            )
        },
        Feature(
            strings[Keys.DICTIONARY_SHORTCUTS], strings[Keys.FEATURE_SHORTCUTS_TEXT],
            strings[Keys.FEATURES_DEFAULT_NONE],
            where(dictionary, strings[Keys.DICTIONARY_SHORTCUTS]),
            Screen.Dictionary,
        ) { accent -> ArrowPreview(strings[Keys.FEATURE_SAMPLE_SHORTCUT], strings[Keys.FEATURE_SAMPLE_SHORTCUT_TEXT], accent) },
    ))

    val layoutGroup = FeatureGroup(strings[Keys.FEATURES_GROUP_LAYOUT], { MaterialTheme.colorScheme.tertiary }, listOf(
        Feature(
            strings[Keys.LAYOUT_FLICKS], strings[Keys.LAYOUT_FLICKS_NOTE],
            strings[Keys.FEATURES_DEFAULT_NONE],
            where(layout, strings[Keys.LAYOUT_FLICKS]),
            Screen.KeyFlicks,
        ) { accent -> ArrowPreview(FLICK_SAMPLE, EURO_SAMPLE, accent) },
        Feature(
            strings[Keys.LAYOUT_EXTRA_KEYS], strings[Keys.LAYOUT_EXTRA_KEYS_NOTE],
            strings[Keys.FEATURES_DEFAULT_ON],
            where(layout, strings[Keys.LAYOUT_EXTRA_KEYS]),
            Screen.ExtraKeys,
        ) { accent -> KeyCapsPreview(EXTRA_KEYS_SAMPLE, accent) },
        Feature(
            strings[Keys.LAYOUT_OWN_LAYOUTS], strings[Keys.LAYOUT_OWN_LAYOUTS_NOTE],
            strings[Keys.FEATURES_DEFAULT_NONE],
            where(layout, strings[Keys.LAYOUT_OWN_LAYOUTS]),
            Screen.CustomLayouts,
        ) { accent -> MonoPreview(LAYOUT_SOURCE_SAMPLE, accent) },
        Feature(
            strings[Keys.LAYOUT_ACCENTED_CHARACTERS], strings[Keys.FEATURE_ACCENTS_TEXT],
            onOff(defaults.accentedCharacters),
            where(layout, strings[Keys.LAYOUT_ACCENTED_CHARACTERS]),
            Screen.Layout,
        ) { accent -> PopupPreview("a", listOf("\u00e0", "\u00e1", "\u00e2", "\u00e4", "\u0103"), accent) },
        Feature(
            strings[Keys.LAYOUT_DEAD_KEYS], strings[Keys.LAYOUT_DEAD_KEYS_NOTE],
            onOff(defaults.deadKeys),
            where(layout, strings[Keys.LAYOUT_DEAD_KEYS]),
            Screen.Layout,
        ) { accent -> ArrowPreview(DEAD_KEY_SAMPLE, DEAD_KEY_RESULT, accent) },
        Feature(
            strings[Keys.LAYOUT_MODIFIER_ROW], strings[Keys.FEATURE_MODIFIER_ROW_TEXT],
            onOff(defaults.modifierRow),
            where(layout, strings[Keys.LAYOUT_MODIFIER_ROW]),
            Screen.Layout,
        ) { accent -> KeyCapsPreview(listOf("esc", "tab", "ctrl", "alt", "\u2190", "\u2193", "\u2191", "\u2192"), accent) },
        Feature(
            strings[Keys.LAYOUT_SPACE_TRACKPOINT], strings[Keys.LAYOUT_SPACE_TRACKPOINT_NOTE],
            onOff(defaults.spaceTrackpoint),
            where(layout, strings[Keys.LAYOUT_SPACE_TRACKPOINT]),
            Screen.Layout,
        ) { accent -> KeyCapsPreview(JOYSTICK_SAMPLE, accent, highlighted = setOf(1)) },
        Feature(
            strings[Keys.LAYOUT_BACKSPACE_SLIDE], strings[Keys.LAYOUT_BACKSPACE_SLIDE_NOTE],
            onOff(defaults.backspaceSlideSelects),
            where(layout, strings[Keys.LAYOUT_BACKSPACE_SLIDE]),
            Screen.Layout,
        ) { accent ->
            KeyCapsPreview(strings[Keys.FEATURE_SAMPLE_TYPO_FIXED].map { it.toString() } + BACKSPACE_GLYPH, accent, highlighted = setOf(0, 1, 2))
        },
    ))

    val panels = mutableListOf(
        Feature(
            strings[Keys.FEATURE_EMOJI_TITLE], strings[Keys.FEATURE_EMOJI_TEXT],
            strings[Keys.FEATURES_DEFAULT_ALWAYS],
            strings[Keys.FEATURE_EMOJI_WHERE],
            null,
        ) { accent -> ArrowPreview(strings[Keys.FEATURE_SAMPLE_EMOJI_WORD], EMOJI_SAMPLE, accent) },
        Feature(
            strings[Keys.SCREEN_DRAFT_BOX], strings[Keys.FEATURE_DRAFT_BOX_TEXT],
            onOff(defaults.composerEnabled),
            strings[Keys.SCREEN_DRAFT_BOX],
            Screen.Composer,
        ) { accent -> TextBoxPreview(accent) },
        Feature(
            strings[Keys.PARTICLE_EFFECTS_ENABLE], strings[Keys.HOME_EFFECTS_NOTE],
            onOff(false),
            strings[Keys.EFFECTS_TITLE],
            Screen.Effects,
        ) { accent -> SwatchesPreview(accent) },
        Feature(
            strings[Keys.DEBUG_STATS], strings[Keys.FEATURE_STATS_TEXT],
            strings[Keys.FEATURES_DEFAULT_COLLAPSED],
            strings[Keys.FEATURE_STATS_WHERE],
            null,
        ) { accent -> MonoPreview(STATS_SAMPLE, accent) },
    )
    val panelsGroup = FeatureGroup(strings[Keys.FEATURES_GROUP_PANELS], { MaterialTheme.colorScheme.secondary }, panels)

    val privacy = mutableListOf(
        Feature(
            strings[Keys.FEATURE_PRIVATE_TITLE], strings[Keys.FEATURE_PRIVATE_TEXT],
            strings[Keys.FEATURES_DEFAULT_ALWAYS],
            strings[Keys.SCREEN_PRIVACY],
            Screen.Privacy,
        ) { accent -> DotsPreview(strings[Keys.STRIP_SHOW_TYPED], accent) },
        Feature(
            strings[Keys.ACTION_PRIVATE_COPY], strings[Keys.CLIPBOARD_PRIVATE_NOTE],
            strings[Keys.FEATURES_DEFAULT_MANUAL],
            where(Keys.SCREEN_QUICK_ACTIONS, strings[Keys.ACTION_PRIVATE_COPY]),
            Screen.QuickActions,
        ) { accent -> ArrowPreview(strings[Keys.FEATURE_SAMPLE_SHORTCUT_TEXT], LOCK_GLYPH, accent) },
        Feature(
            strings[Keys.SCREEN_BACKUP], strings[Keys.FEATURE_BACKUP_TEXT],
            strings[Keys.FEATURES_DEFAULT_MANUAL],
            strings[Keys.SCREEN_BACKUP],
            Screen.Backup,
        ) { accent -> ArrowPreview(LOCK_GLYPH, BACKUP_EXTENSION, accent) },
    )
    if (hasAssistant) {
        privacy += Feature(
            strings[Keys.SCREEN_TEXT_ASSISTANT], strings[Keys.FEATURE_ASSISTANT_TEXT],
            strings[Keys.FEATURES_DEFAULT_NO_MODEL],
            strings[Keys.SCREEN_TEXT_ASSISTANT],
            Screen.Assistant,
        ) { accent -> ChipsPreview(listOf(strings[Keys.FEATURE_ASSISTANT_SUMMARISE], strings[Keys.FEATURE_ASSISTANT_CORRECT], strings[Keys.FEATURE_ASSISTANT_TRANSLATE]), accent) }
    }
    val privacyGroup = FeatureGroup(strings[Keys.FEATURES_GROUP_PRIVACY], { PRIVACY_ACCENT }, privacy)

    return listOf(typingGroup, layoutGroup, panelsGroup, privacyGroup)
}

// ---- the card -----------------------------------------------------------------------------

@Composable
private fun FeatureCard(feature: Feature, accent: Color, open: (Screen) -> Unit) {
    val target = feature.target
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.07f))
            .then(if (target != null) Modifier.clickable { open(target) } else Modifier),
    ) {
        Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    feature.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    feature.default,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    modifier = Modifier
                        .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Box(modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)) { feature.preview(accent) }
            Text(feature.text, style = MaterialTheme.typography.bodySmall)
            Text(
                if (target != null) feature.where + OPENS_GLYPH else feature.where,
                style = MaterialTheme.typography.labelSmall,
                color = if (target != null) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private val PRIVACY_ACCENT = Color(0xFFB26A00)

/** Glyphs, not words: drawn rather than translated. */
private const val ARROW_GLYPH = "\u2192"
private const val OPENS_GLYPH = " \u203a"
private const val LOCK_GLYPH = "\ud83d\udd12"
private const val BACKUP_EXTENSION = ".bkbackup"
private const val SPACE_GLYPH = "\u2423"
private const val DOTS_GLYPHS = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
private const val EMOJI_SAMPLE = "\ud83c\udf82 \ud83c\udf70 \ud83e\uddc1"
private const val STATS_SAMPLE = "1.5 \u00b7 60 ms"
private const val FLICK_SAMPLE = "e \u2197"
private const val EURO_SAMPLE = "\u20ac"
private val EXTRA_KEYS_SAMPLE = listOf("\u00df", "\u20ac", "\u00f1", "\u0491")
private const val LAYOUT_SOURCE_SAMPLE = "{ \"c\": \"q\" }"
private const val DEAD_KEY_SAMPLE = "\u00b4 e"
private const val DEAD_KEY_RESULT = "\u00e9"
private val JOYSTICK_SAMPLE = listOf("\u2190", SPACE_GLYPH, "\u2192")
private const val BACKSPACE_GLYPH = "\u232b"

/** The catalogue lists a preview's several words in one string, split on this. */
internal const val SAMPLE_SEPARATOR = "|"

// ---- previews -----------------------------------------------------------------------------

@Composable
private fun KeyCap(label: String, accent: Color, highlighted: Boolean = false, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (highlighted) accent.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun KeyCapsPreview(caps: List<String>, accent: Color, highlighted: Set<Int> = emptySet()) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        caps.forEachIndexed { index, cap ->
            KeyCap(cap, accent, index in highlighted, Modifier.weight(1f))
        }
    }
}

@Composable
internal fun SwipePreview(accent: Color) {
    val caps = "qwertyuiop".map { it.toString() }
    Box(
        modifier = Modifier.fillMaxWidth().drawWithContent {
            drawContent()
            val step = size.width / caps.size
            fun centre(index: Int) = Offset(step * (index + 0.5f), size.height / 2f)
            val path = Path().apply {
                moveTo(centre(4).x, centre(4).y)
                quadraticTo(centre(6).x, size.height * 0.05f, centre(8).x, centre(8).y)
            }
            drawPath(path, accent, style = Stroke(width = 6f, cap = StrokeCap.Round))
        },
    ) {
        KeyCapsPreview(caps, accent, highlighted = setOf(4, 8))
    }
}

@Composable
private fun Chip(label: String, accent: Color, outlined: Boolean = false) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .then(
                if (outlined) Modifier.border(1.5.dp, accent, RoundedCornerShape(50))
                else Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)),
            )
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
internal fun ChipsPreview(labels: List<String>, accent: Color, outlined: Int = -1) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label -> Chip(label, accent, outlined = index == outlined) }
    }
}

@Composable
internal fun CorrectionPreview(typo: String, fixed: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(typo, style = MaterialTheme.typography.bodyMedium, textDecoration = TextDecoration.LineThrough)
        Text(ARROW_GLYPH, style = MaterialTheme.typography.bodyMedium, color = accent)
        Chip(fixed, accent, outlined = true)
        KeyCap(SPACE_GLYPH, accent, modifier = Modifier.width(56.dp))
    }
}

@Composable
internal fun ArrowPreview(from: String, to: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(from, accent)
        Text(ARROW_GLYPH, style = MaterialTheme.typography.bodyMedium, color = accent)
        Text(to, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PopupPreview(key: String, popup: List<String>, accent: Color) {
    Column(horizontalAlignment = Alignment.Start) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(accent.copy(alpha = 0.2f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (item in popup) {
                Text(item, style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        KeyCap(key, accent, modifier = Modifier.width(40.dp))
    }
}

@Composable
private fun IconRowPreview(accent: Color) {
    val actions = listOf(QuickAction.COPY_ALL, QuickAction.PASTE, QuickAction.SELECT_ALL, QuickAction.UNDO, QuickAction.CURSOR_LEFT)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (action in actions) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(iconFor(action)),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun CardsPreview(accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (width in listOf(0.7f, 0.5f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(width)
                    .height(18.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(5.dp)),
            )
        }
    }
}

@Composable
private fun TextBoxPreview(accent: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (width in listOf(0.9f, 0.6f)) {
            Box(modifier = Modifier.fillMaxWidth(width).height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
        }
    }
}

@Composable
private fun SwatchesPreview(accent: Color) {
    val colours = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.error,
        MaterialTheme.colorScheme.surfaceVariant,
        accent,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (colour in colours) {
            Box(modifier = Modifier.size(22.dp).clip(CircleShape).background(colour))
        }
    }
}

@Composable
private fun DotsPreview(show: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(DOTS_GLYPHS, style = MaterialTheme.typography.titleMedium)
        Chip(show, accent, outlined = true)
    }
}

@Composable
private fun MonoPreview(text: String, accent: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        color = accent,
    )
}
