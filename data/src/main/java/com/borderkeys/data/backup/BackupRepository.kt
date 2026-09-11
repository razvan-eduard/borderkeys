// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.backup

import com.borderkeys.data.BorderKeysDatabase
import com.borderkeys.data.ClipboardRepository
import com.borderkeys.data.theme.ThemeRepository
import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedTrigram
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.BlockedWord
import kotlinx.coroutines.flow.first

/**
 * Gathering what a keyboard knows, and putting it back.
 *
 * Four parts, chosen one at a time by whoever is exporting, because they are not equally
 * private and not equally worth carrying. Settings are a handful of numbers. The dictionary is
 * every word this device learned from what its owner typed. The clipboard is what they last
 * copied. Which of those goes into a file is not a decision to make on their behalf.
 *
 * Importing adds rather than replaces, wherever adding makes sense. A dictionary is a count per
 * word, so two devices' dictionaries merge by summing -- and a merge cannot lose what was
 * already there, which a wholesale replacement can. Settings are the exception: they are one
 * coherent set and half of one is not a setting.
 */
class BackupRepository(
    private val database: BorderKeysDatabase,
    private val themes: ThemeRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** What the caller asked to include. Each is a separate answer. */
    data class Parts(
        val settings: Boolean = false,
        val dictionary: Boolean = false,
        val languages: Boolean = false,
        val clipboard: Boolean = false,
    ) {
        val any: Boolean get() = settings || dictionary || languages || clipboard
    }

    suspend fun gather(parts: Parts): BackupPayload {
        val preferences = if (parts.settings) themes.preferences.first() else null
        val theme = if (parts.settings) themes.theme.first() else null
        // Which model is active, not the model itself -- see BackupModel's own doc. Rides with
        // settings rather than its own toggle: this is a choice of which assistant to use, the
        // same kind of thing a theme or a layout is, not a body of learned or copied text.
        val models = if (parts.settings) {
            database.assistModelDao().observeAll().first().map {
                BackupModel(fileName = it.fileName, sha256 = it.sha256, active = it.active)
            }
        } else {
            emptyList()
        }

        val packs = if (parts.languages) {
            database.languagePackDao().observeAll().first().map {
                BackupPack(tag = it.tag, enabled = it.enabled, weight = it.weight)
            }
        } else {
            emptyList()
        }

        // Everything, not the top few. A dictionary carried across with its tail cut off is one
        // that has forgotten exactly the uncommon words it was worth carrying for.
        val words = if (parts.dictionary) {
            database.userWordDao().topWords(Int.MAX_VALUE).map {
                BackupWord(word = it.word, locale = it.locale, count = it.count)
            }
        } else {
            emptyList()
        }
        val bigrams = if (parts.dictionary) {
            database.userBigramDao().topPairs(Int.MAX_VALUE).map {
                BackupBigram(previous = it.previousWord, word = it.word, count = it.count)
            }
        } else {
            emptyList()
        }
        val trigrams = if (parts.dictionary) {
            database.userTrigramDao().topTriples(Int.MAX_VALUE).map {
                BackupTrigram(
                    previous2 = it.previousWord2,
                    previous1 = it.previousWord1,
                    word = it.word,
                    count = it.count,
                )
            }
        } else {
            emptyList()
        }
        // The words the user refused travel with the dictionary. A device that has been told
        // twice not to suggest something should not have to be told a third time.
        val blocked = if (parts.dictionary) database.blockedWordDao().allWords() else emptyList()

        val clips = if (parts.clipboard) {
            database.clipboardDao().observeLive(0L).first()
                // An image is a reference to a file this application does not own and the other
                // one could not read. Only the text travels.
                .filter { !it.isImage }
                .map {
                    BackupClip(
                        content = it.content,
                        createdAt = it.createdAt,
                        pinned = it.isPinned,
                    )
                }
        } else {
            emptyList()
        }

        return BackupPayload(
            preferences = preferences,
            theme = theme,
            packs = packs,
            words = words,
            bigrams = bigrams,
            trigrams = trigrams,
            blocked = blocked,
            clips = clips,
            models = models,
        )
    }

    /** What an import actually did, so the screen can say so rather than "done". */
    data class Applied(
        val settings: Boolean = false,
        val words: Int = 0,
        val pairs: Int = 0,
        val triples: Int = 0,
        val blocked: Int = 0,
        val languages: Int = 0,
        val clips: Int = 0,
        val assistModels: Int = 0,
    )

    suspend fun apply(payload: BackupPayload, parts: Parts): Applied {
        var applied = Applied()

        if (parts.settings) {
            payload.preferences?.let { incoming ->
                // Through sanitised(), like every other read: a file is a file, whoever wrote it.
                themes.updatePreferences { incoming.sanitised() }
            }
            payload.theme?.let { incoming -> themes.updateTheme { incoming.sanitised() } }
            applied = applied.copy(settings = payload.preferences != null || payload.theme != null)

            // Only a model this device already has the file for -- by hash, since the same file
            // re-imported gets a new row and a new id every time. A model the payload names but
            // this device has never imported is one nothing here can switch on, the same limit
            // languages already has for a dictionary it does not carry.
            var reactivated = 0
            for (model in payload.models) {
                if (!model.active) {
                    continue
                }
                val existing = database.assistModelDao().findBySha256(model.sha256) ?: continue
                database.assistModelDao().setActive(existing.id)
                reactivated += 1
            }
            applied = applied.copy(assistModels = reactivated)
        }

        if (parts.dictionary) {
            val stamp = now()
            database.userWordDao().incrementAll(
                payload.words.map {
                    LearnedWord(
                        word = it.word,
                        locale = it.locale,
                        delta = it.count,
                        lastUsedAt = stamp,
                    )
                },
            )
            database.userBigramDao().incrementAll(
                payload.bigrams.map {
                    LearnedBigram(
                        previousWord = it.previous,
                        word = it.word,
                        delta = it.count,
                        lastUsedAt = stamp,
                    )
                },
            )
            database.userTrigramDao().incrementAll(
                payload.trigrams.map {
                    LearnedTrigram(
                        previousWord2 = it.previous2,
                        previousWord1 = it.previous1,
                        word = it.word,
                        delta = it.count,
                        lastUsedAt = stamp,
                    )
                },
            )
            for (word in payload.blocked) {
                database.blockedWordDao().insert(BlockedWord(word))
            }
            applied = applied.copy(
                words = payload.words.size,
                pairs = payload.bigrams.size,
                triples = payload.trigrams.size,
                blocked = payload.blocked.size,
            )
        }

        if (parts.languages) {
            // Only packs this device already has. The file carries which languages were on, not
            // the dictionaries themselves -- those are in the application, and one it does not
            // have is one it cannot switch on.
            var touched = 0
            for (pack in payload.packs) {
                val existing = database.languagePackDao().findByTag(pack.tag) ?: continue
                database.languagePackDao().setEnabled(existing.id, pack.enabled)
                database.languagePackDao().setWeight(existing.id, pack.weight)
                touched += 1
            }
            applied = applied.copy(languages = touched)
        }

        if (parts.clipboard) {
            var added = 0
            for (clip in payload.clips) {
                // The same hash ClipboardRepository.remember/rememberImage write, not a second,
                // weaker derivation of the same idea: two different hashes over identical content
                // would mean a backup restore and a live copy of the same text never recognise
                // each other, defeating the whole point of the unique index they both rely on.
                val hash = ClipboardRepository.contentHash(clip.content)
                if (database.clipboardDao().findByHash(hash) != null) {
                    continue
                }
                database.clipboardDao().insertIfAbsent(
                    content = clip.content,
                    createdAt = clip.createdAt,
                    pinnedAt = if (clip.pinned) clip.createdAt else null,
                    contentHash = hash,
                )
                added += 1
            }
            applied = applied.copy(clips = added)
        }

        return applied
    }

    /** What a file turned out to contain, for the screen that offers to import it. */
    fun contentsOf(payload: BackupPayload): Parts = Parts(
        settings = payload.preferences != null || payload.theme != null ||
            payload.models.isNotEmpty(),
        dictionary = payload.words.isNotEmpty() || payload.bigrams.isNotEmpty() ||
            payload.trigrams.isNotEmpty() || payload.blocked.isNotEmpty(),
        languages = payload.packs.isNotEmpty(),
        clipboard = payload.clips.isNotEmpty(),
    )
}
