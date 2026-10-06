// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.entity.LanguagePackEntry
import com.borderkeys.i18n.Keys
import com.borderkeys.predict.LanguagePackInspector
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The rare-words setting's step names, indexed by step. */
internal val RARE_WORDS_STEP_KEYS = listOf(
    Keys.CORRECTIONS_RARE_WORDS_LISTED,
    Keys.CORRECTIONS_RARE_WORDS_SOME,
    Keys.CORRECTIONS_RARE_WORDS_MANY,
    Keys.CORRECTIONS_RARE_WORDS_ALL,
)

/** An enabled dictionary by its shown name, and whether it carries rare words to count. */
internal class PackReach(val name: String, val holdsRareWords: Boolean)

/** One line of the rare-words note: a catalogue key, and the dictionary names that fill its `%s`. */
internal data class ReachLine(val key: String, val names: String? = null)

/** The lines naming which of [packs] the rare-words setting changes and which it leaves as they are. */
internal fun rareWordsReach(packs: List<PackReach>): List<ReachLine> {
    val (affected, unaffected) = packs.partition { it.holdsRareWords }
    if (affected.isEmpty()) {
        return listOf(ReachLine(Keys.CORRECTIONS_RARE_WORDS_NONE))
    }
    return listOfNotNull(
        ReachLine(Keys.CORRECTIONS_RARE_WORDS_AFFECTS, affected.names()),
        unaffected.takeIf { it.isNotEmpty() }
            ?.let { ReachLine(Keys.CORRECTIONS_RARE_WORDS_UNAFFECTED, it.names()) },
    )
}

private fun List<PackReach>.names(): String = joinToString(", ") { it.name }

/** [rareWordsReach] over the enabled packs, each read from its own header; null until read. */
@Composable
internal fun rememberRareWordsReach(): List<ReachLine>? {
    val repository = remember { DataGraph.languagePacks }
    val packs by repository.packs
        .collectAsStateWithLifecycle<List<LanguagePackEntry>?>(initialValue = null)
    val enabled = packs?.filter { it.enabled }?.map { it.displayName to repository.fileFor(it) }
    val reach by produceState<List<ReachLine>?>(initialValue = null, enabled) {
        if (enabled != null) {
            value = withContext(Dispatchers.IO) {
                rareWordsReach(enabled.map { (name, file) -> PackReach(name, knownWordsIn(file) > 0) })
            }
        }
    }
    return reach
}

private fun knownWordsIn(file: File): Int =
    (LanguagePackInspector.inspect(file) as? LanguagePackInspector.Result.Valid)?.info?.knownWordCount ?: 0
