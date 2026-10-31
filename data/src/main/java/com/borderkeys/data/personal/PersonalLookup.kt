// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.personal

import com.borderkeys.data.ContextRow
import com.borderkeys.data.LinkRow
import com.borderkeys.data.PhraseBudget
import com.borderkeys.data.PhraseKey
import com.borderkeys.data.UserPhrase
import com.borderkeys.data.WordShape
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext
import com.borderkeys.data.entity.UserWord
import com.borderkeys.data.languageGone
import com.borderkeys.data.toBigram
import com.borderkeys.data.toPairContext
import com.borderkeys.data.toTrigram
import com.borderkeys.data.toTripleContext
import com.borderkeys.data.personal.PersonalSpelling.Form

/**
 * The personal model as Kotlin holds it and hands it to the engine: every word with its case
 * forms, merged by [PersonalSpelling], its uses in each language, under the id the engine's word
 * table gave it, and the pairs and triples of each language with their contexts' totals and
 * observations and each language's clocks. A commit counts its word and links here, its links
 * under [PhraseBudget], an edit of the personal dictionary makes its change here too
 * ([PersonalModelHost]), and the engine is handed the slice of each context a request names
 * ([slice]). Used on the engine's thread only.
 */
class PersonalLookup {

    /**
     * A word: its case forms as the personal dictionary stores them, each spelling's rows in every
     * language as one, its uses in each language and in no language known, the languages its rows
     * of no language known were placed in at the load, its id in the engine's word table, and
     * whether an active pack knows it.
     */
    private class Word(val forms: MutableList<Form>) {
        val languages = HashMap<String, Int>()
        var unplaced = 0
        val placed = HashSet<String>()
        var id = WordRows.NEW_WORD
        var known = false
    }

    /**
     * One link after a context: its count, its choices, its context's observations and its
     * language's clock at its last use, its last use, and how many times it was added by hand.
     */
    private class Link(
        var count: Int,
        var asserted: Int,
        var contextSeen: Long,
        var languageSeen: Long,
        var lastUsedAt: Long = 0L,
        var added: Int = 0,
    )

    /**
     * The links after one context, by their last word, in the order recorded, and the context's
     * total, observations and last use.
     */
    private class Context {
        val links = LinkedHashMap<String, Link>()
        var total = 0L
        var seen = 0L
        var lastUsedAt = 0L
    }

    /** One kind of link in one language: each context's links, how many links are held, and the clock. */
    private class Links<K> {
        val contexts = HashMap<K, Context>()
        var held = 0
        var clock = 0L
    }

    /** One language's pairs, by the word before, and triples, by the two words before. */
    private class Store {
        val pairs = Links<String>()
        val triples = Links<Pair<String, String>>()
    }

    /**
     * A link as the engine offers it: its last word, its count, its choices, and its context's
     * observations and its language's clock at its last use.
     */
    data class HeldLink(
        val word: String,
        val count: Int,
        val asserted: Int,
        val contextSeen: Long,
        val languageSeen: Long,
    )

    /**
     * A context's links, chosen on purpose first, then the most used, then the first recorded, and
     * its total and observations.
     */
    data class ContextLinks(val links: List<HeldLink>, val total: Long, val seen: Long)

    /**
     * A word as the engine's word table is handed it: its id, whether an active pack knew it, and
     * its forms merged ([PersonalSpelling.merge]) with one count, its highest in a language the
     * active packs count, where its count in a language is its uses there plus its uses in no
     * language known. With that count zero, every count is zero.
     */
    data class HeldWord(val id: Int, val known: Boolean, val entry: PersonalSpelling.Merged)

    /**
     * A word's uses: as the personal dictionary stores them ([stored], every case form's summed),
     * in each language they were placed or committed in ([languages]), and in no language known
     * ([unplaced]).
     */
    data class WordCounts(val stored: Int, val languages: Map<String, Int>, val unplaced: Int) {

        /** The word's count in [language]: its uses there plus its uses in no language known, counted in every one. */
        fun inLanguage(language: String): Int = (languages[language] ?: 0) + unplaced
    }

    private val words = HashMap<String, Word>()
    private val stores = HashMap<String, Store>()

    /** The packs active at the last load. */
    private var packs = ActivePacks.NONE

    /** The id each language a slice names is given, in the order first named. */
    private val languageIds = HashMap<String, Int>()

    /** How many times the words or links have changed: a slice built before a change is stale. */
    var version = 0L
        private set

    /** The order the engine offers a context's links in: chosen on purpose first, then the most used. */
    private val offeredFirst = compareByDescending<Link> { it.asserted > 0 }.thenByDescending { it.count }

    /**
     * Replaces every word with [rows], each row with uses left counted in its spelling's form and in
     * its language, then the sentence start and each word of [pairs] and [triples] with uses, as the
     * links load them, with no counts of their own; the uses of the rows of no language known
     * ([UserWord.UNPLACED]) placed by [place] under [packs]. Returns the rows the engine's word
     * table loads, in that order; the links go once [assigned] has their words' ids ([loadLinks]).
     */
    fun loadWords(
        rows: List<UserWord>,
        pairs: List<UserBigram>,
        triples: List<UserTrigram>,
        packs: ActivePacks = ActivePacks.NONE,
    ): WordRows {
        version++
        words.clear()
        stores.clear()
        this.packs = packs
        val order = ArrayList<String>()
        val unplacedUses = HashMap<String, Int>()
        for (row in rows) {
            if (row.count <= 0) {
                continue
            }
            val key = PhraseKey.word(row.word)
            val word = words.getOrPut(key) {
                order += key
                Word(mutableListOf())
            }
            val at = word.forms.indexOfFirst { it.spelling == row.word }
            val form = Form(row.word, row.count, row.lastUsedAt, row.deliberateCapitals, row.asserted, row.added)
            if (at >= 0) word.forms[at] = word.forms[at].plus(form) else word.forms += form
            if (row.language == UserWord.UNPLACED) {
                unplacedUses[key] = (unplacedUses[key] ?: 0) + row.count
            } else {
                word.languages[row.language] = (word.languages[row.language] ?: 0) + row.count
            }
        }
        hold(UserBigram.SENTENCE_START, order)
        for (pair in pairs) {
            if (pair.count > 0) {
                hold(pair.previousWord, order)
                hold(pair.word, order)
            }
        }
        for (triple in triples) {
            if (triple.count > 0) {
                hold(triple.previousWord2, order)
                hold(triple.previousWord1, order)
                hold(triple.word, order)
            }
        }
        place(unplacedUses, pairs, triples)
        return rowsOf(order)
    }

    /**
     * Places each word's [uses] in no language known: counted whole in each language of the active
     * packs that spell one of its forms, else in each language of [pairs] and [triples] with uses it
     * is a word of, else in the unplaced group.
     */
    private fun place(uses: Map<String, Int>, pairs: List<UserBigram>, triples: List<UserTrigram>) {
        val linkedIn = HashMap<String, MutableSet<String>>()
        fun linked(spelling: String, language: String) {
            linkedIn.getOrPut(PhraseKey.word(spelling)) { HashSet() } += language
        }
        for (pair in pairs) {
            if (pair.count > 0) {
                linked(pair.previousWord, pair.language)
                linked(pair.word, pair.language)
            }
        }
        for (triple in triples) {
            if (triple.count > 0) {
                linked(triple.previousWord2, triple.language)
                linked(triple.previousWord1, triple.language)
                linked(triple.word, triple.language)
            }
        }
        val placing = uses.keys.map { it to words.getValue(it) }
        val spelledIn = packs.spelling(placing.flatMap { (_, word) -> word.forms.map { it.spelling } })
        var at = 0
        for ((key, word) in placing) {
            val languages = HashSet<String>()
            repeat(word.forms.size) { languages += spelledIn[at++] }
            if (languages.isEmpty()) {
                linkedIn[key]?.let { languages += it }
            }
            val unplaced = uses.getValue(key)
            if (languages.isEmpty()) {
                word.unplaced = unplaced
            } else {
                languages.forEach { word.languages[it] = (word.languages[it] ?: 0) + unplaced }
                word.placed += languages
            }
        }
    }

    /**
     * Counts [update] once its uses are above zero, in the form spelled as committed and in
     * [language], and holds it, [previous1] and [previous2] with no counts when new. Returns the
     * rows the engine's word table takes for it: every word whose entry changed.
     */
    fun commit(language: String, update: LearnedWord, previous1: String?, previous2: String?): WordRows {
        version++
        val changed = ArrayList<String>()
        if (update.delta > 0) {
            val key = PhraseKey.word(update.word)
            val word = words.getOrPut(key) { Word(mutableListOf()) }
            val at = word.forms.indexOfFirst { it.spelling == update.word }
            val form = if (at >= 0) word.forms[at] else Form(update.word, 0, 0L)
            val counted = form.copy(
                count = form.count + update.delta,
                lastUsedAt = maxOf(form.lastUsedAt, update.lastUsedAt),
                deliberateCapitals = form.deliberateCapitals + if (update.deliberateCapital) 1 else 0,
                asserted = form.asserted + update.asserted,
            )
            if (at >= 0) word.forms[at] = counted else word.forms += counted
            word.languages[language] = (word.languages[language] ?: 0) + update.delta
            changed += key
        } else {
            hold(update.word, changed)
        }
        previous1?.let { hold(it, changed) }
        previous2?.let { hold(it, changed) }
        return rowsOf(changed)
    }

    /**
     * Counts each of [words] as one use added by hand in [language] at [now], as the personal
     * dictionary adds it: its count, its choices and its adds one more each, in the form spelled as
     * given, a first letter in capitals counted as the user's. Returns the rows the engine's word
     * table takes for them.
     */
    fun added(language: String, words: List<String>, now: Long): WordRows {
        version++
        val changed = LinkedHashSet<String>()
        for (spelling in words) {
            val key = PhraseKey.word(spelling)
            val word = this.words.getOrPut(key) { Word(mutableListOf()) }
            val at = word.forms.indexOfFirst { it.spelling == spelling }
            val form = if (at >= 0) word.forms[at] else Form(spelling, 0, 0L)
            val counted = form.copy(
                count = form.count + 1,
                lastUsedAt = maxOf(form.lastUsedAt, now),
                deliberateCapitals = form.deliberateCapitals + if (WordShape.startsWithCapital(spelling)) 1 else 0,
                asserted = form.asserted + 1,
                added = form.added + 1,
            )
            if (at >= 0) word.forms[at] = counted else word.forms += counted
            word.languages[language] = (word.languages[language] ?: 0) + 1
            changed += key
        }
        return rowsOf(changed.toList())
    }

    /**
     * Takes each of [rows], as the personal dictionary held it when it deleted it, out of the form
     * of its spelling and out of its word's uses in its language, a row of no language known out of
     * the languages the load placed it in, else out of the unplaced group; what the lookup counted
     * beyond the row stays. Returns the rows the engine's word table takes: every word whose entry
     * changed.
     */
    fun removed(rows: List<UserWord>): WordRows {
        version++
        val changed = LinkedHashSet<String>()
        for (row in rows) {
            val key = PhraseKey.word(row.word)
            val word = words[key] ?: continue
            val at = word.forms.indexOfFirst { it.spelling == row.word }
            if (at < 0) {
                continue
            }
            val form = word.forms[at]
            val left = form.copy(
                count = (form.count - row.count).coerceAtLeast(0),
                deliberateCapitals = (form.deliberateCapitals - row.deliberateCapitals).coerceAtLeast(0),
                asserted = (form.asserted - row.asserted).coerceAtLeast(0),
                added = (form.added - row.added).coerceAtLeast(0),
            )
            if (left.count > 0) {
                word.forms[at] = left
            } else {
                word.forms.removeAt(at)
            }
            if (word.forms.isEmpty()) {
                word.forms += Form(row.word, 0, 0L)
            }
            val taken = form.count - left.count
            val languages = if (row.language == UserWord.UNPLACED) word.placed else setOf(row.language)
            for (language in languages) {
                word.languages[language]?.let { word.languages[language] = (it - taken).coerceAtLeast(0) }
            }
            if (row.language == UserWord.UNPLACED && word.placed.isEmpty()) {
                word.unplaced = (word.unplaced - taken).coerceAtLeast(0)
            }
            changed += key
        }
        return rowsOf(changed.toList())
    }

    /**
     * Takes the ids the engine gave [rows], and whether an active pack knows each word; a row it
     * refused keeps none.
     */
    fun assigned(rows: WordRows, ids: IntArray, known: BooleanArray) {
        version++
        for ((at, key) in rows.keys.withIndex()) {
            val word = words[key] ?: continue
            if (ids[at] >= 0) {
                word.id = ids[at]
                word.known = known[at]
            }
        }
    }

    /** Whether an active pack knew [word], in any case, when the engine last took it. */
    fun known(word: String): Boolean = words[PhraseKey.word(word)]?.known == true

    /** Every word held, as [HeldWord] gives it. */
    fun heldWords(): List<HeldWord> = words.map { (key, word) -> HeldWord(word.id, word.known, handed(key, word)) }

    /** [word]'s uses, in any case, as [WordCounts] gives them; null for a word not held. */
    fun counts(word: String): WordCounts? = words[PhraseKey.word(word)]?.let {
        WordCounts(it.forms.sumOf { form -> form.count }, HashMap(it.languages), it.unplaced)
    }

    /**
     * Replaces the pairs and triples with [pairs] and [triples], as the engine loads them: each in
     * its language's store, rows naming the same link adding up and keeping their later stamps,
     * a link whose words have no id or whose last word follows itself left out, and links past
     * [PhraseBudget.LANGUAGE_CAP] in a store left out; then each context's total and observations
     * raised to [pairContexts]' and [tripleContexts]', and each language's clocks set from [clocks].
     */
    fun loadLinks(
        pairs: List<UserBigram>,
        pairContexts: List<UserPairContext>,
        triples: List<UserTrigram>,
        tripleContexts: List<UserTripleContext>,
        clocks: List<UserPhraseClock>,
    ) {
        version++
        stores.clear()
        for (pair in pairs) {
            val store = (if (pair.count > 0) storeFor(pair.language) else null) ?: continue
            if (!linkable(pair.previousWord, pair.word)) {
                continue
            }
            add(
                store.pairs, pair.previousWord, pair.word,
                Link(pair.count, pair.asserted, pair.contextSeen, pair.languageSeen, pair.lastUsedAt, pair.added),
            )
        }
        for (context in pairContexts) {
            stores[context.language]?.pairs?.contexts?.get(context.previousWord)
                ?.raise(context.total, context.seen, context.lastUsedAt)
        }
        for (triple in triples) {
            val store = (if (triple.count > 0) storeFor(triple.language) else null) ?: continue
            if (!linkable(triple.previousWord1, triple.word) || !heldWord(triple.previousWord2)) {
                continue
            }
            add(
                store.triples, triple.previousWord2 to triple.previousWord1, triple.word,
                Link(
                    triple.count, triple.asserted, triple.contextSeen, triple.languageSeen, triple.lastUsedAt,
                    triple.added,
                ),
            )
        }
        for (context in tripleContexts) {
            stores[context.language]?.triples?.contexts?.get(context.previousWord2 to context.previousWord1)
                ?.raise(context.total, context.seen, context.lastUsedAt)
        }
        for (clock in clocks) {
            val store = storeFor(clock.language) ?: continue
            store.pairs.clock = clock.pairs
            store.triples.clock = clock.triples
        }
    }

    /**
     * Records the links a commit of [word] after [previous1] and [previous2] makes in [language]:
     * the pair ([recordPair]) when the word before and the word are learnable, and the triple
     * ([recordTriple]) when the word two before is too, [learnable] answering for each word asked,
     * in order, the sentence start learnable and never asked about. Returns the rows the commit
     * changed.
     */
    fun commitLinks(
        language: String,
        word: String,
        previous1: String?,
        previous2: String?,
        pairAsserted: Boolean,
        tripleAsserted: Boolean,
        at: Long,
        learnable: (List<String>) -> List<Boolean>,
    ): LinkWrites {
        if (previous1 == null) {
            return LinkWrites()
        }
        val asked = listOfNotNull(word, previous1.takeIf { it != UserBigram.SENTENCE_START }, previous2)
        val verdicts = asked.zip(learnable(asked)).toMap()
        fun learnableWord(spelling: String) = spelling == UserBigram.SENTENCE_START || verdicts[spelling] == true
        if (!learnableWord(word) || !learnableWord(previous1)) {
            return LinkWrites()
        }
        val pair = recordPair(language, previous1, word, pairAsserted, at)
        val triple = previous2?.takeIf { learnableWord(it) }?.let {
            recordTriple(language, it, previous1, word, tripleAsserted, at)
        }
        return triple?.let { pair.then(it) } ?: pair
    }

    /**
     * Records [words], two or three, as a phrase added by hand in [language] at [now], as the
     * personal dictionary adds it: the pair of two, or the two pairs and the triple of three, each
     * one use chosen on purpose and added by hand ([recordPair], [recordTriple]), its words held.
     * Returns the rows it changed.
     */
    fun addedPhrase(language: String, words: List<String>, now: Long): LinkWrites {
        var writes = LinkWrites()
        for ((previous, word) in words.zipWithNext()) {
            writes = writes.then(recordPair(language, previous, word, asserted = true, at = now, added = true))
        }
        if (words.size == UserPhrase.TRIPLE_WORDS) {
            val (previous2, previous1, word) = words
            val triple = recordTriple(language, previous2, previous1, word, asserted = true, at = now, added = true)
            writes = writes.then(triple)
        }
        return writes
    }

    /**
     * Records one use of [word] after [previous] in [language]'s pairs at [at], when both words are
     * held and differ: the link's count one more, and its choices when [asserted] and its adds when
     * [added], its context seen once more and the language's pair clock moved on one, the link
     * stamped with both. Then the context keeps its budget ([PhraseBudget.overflow]) and the language
     * its cap ([languageGone]), the link just recorded never going. Returns the rows it changed.
     */
    fun recordPair(
        language: String,
        previous: String,
        word: String,
        asserted: Boolean,
        at: Long,
        added: Boolean = false,
    ): LinkWrites {
        val store = storeFor(language) ?: return LinkWrites()
        val before = PhraseKey.word(previous)
        val next = PhraseKey.word(word)
        if (!linkable(before, next)) {
            return LinkWrites()
        }
        version++
        learn(store.pairs, before, next, asserted, at, added)
        val budgeted = budget(store.pairs, before, next, PhraseBudget.PAIRS, language) { listOf(it) }
        return LinkWrites(
            pairs = listOf(budgeted.kept.toBigram()),
            pairContexts = listOf(store.pairs.contexts.getValue(before).row(language, listOf(before)).toPairContext()),
            clocks = listOf(UserPhraseClock(language, store.pairs.clock, store.triples.clock)),
            gonePairs = budgeted.gone.map { it.toBigram() },
        )
    }

    /** [recordPair], for [word] after [previous2] and [previous1] in [language]'s triples, its words all held. */
    fun recordTriple(
        language: String,
        previous2: String,
        previous1: String,
        word: String,
        asserted: Boolean,
        at: Long,
        added: Boolean = false,
    ): LinkWrites {
        val store = storeFor(language) ?: return LinkWrites()
        val context = PhraseKey.word(previous2) to PhraseKey.word(previous1)
        val next = PhraseKey.word(word)
        if (!linkable(context.second, next) || !heldWord(context.first)) {
            return LinkWrites()
        }
        version++
        learn(store.triples, context, next, asserted, at, added)
        val budgeted = budget(store.triples, context, next, PhraseBudget.TRIPLES, language) {
            listOf(it.first, it.second)
        }
        return LinkWrites(
            triples = listOf(budgeted.kept.toTrigram()),
            tripleContexts = listOf(
                store.triples.contexts.getValue(context).row(language, listOf(context.first, context.second))
                    .toTripleContext(),
            ),
            clocks = listOf(UserPhraseClock(language, store.pairs.clock, store.triples.clock)),
            goneTriples = budgeted.gone.map { it.toTrigram() },
        )
    }

    /**
     * Drops [pairs] and [triples] from their languages' stores; their contexts keep their uses
     * while a link still follows them.
     */
    fun drop(pairs: List<UserBigram>, triples: List<UserTrigram>) {
        version++
        for (pair in pairs) {
            stores[pair.language]?.pairs?.let { drop(it, pair.previousWord, pair.word) }
        }
        for (triple in triples) {
            stores[triple.language]?.triples?.let {
                drop(it, triple.previousWord2 to triple.previousWord1, triple.word)
            }
        }
    }

    /**
     * Forgets every pair and triple [word] takes part in, in any case and in every language, as the
     * personal dictionary forgets them: a link it ends goes, its uses taken out of its context's
     * total and observations; a context it is part of goes with every link after it; and a context
     * left with no link goes. Returns the links gone and the contexts left changed.
     */
    fun forgetLinks(word: String): LinkWrites {
        version++
        val key = PhraseKey.word(word)
        val pairs = Forgotten()
        val triples = Forgotten()
        for ((language, store) in stores) {
            forget(store.pairs, key, language, pairs) { listOf(it) }
            forget(store.triples, key, language, triples) { listOf(it.first, it.second) }
        }
        return LinkWrites(
            pairContexts = pairs.changed.map { it.toPairContext() },
            tripleContexts = triples.changed.map { it.toTripleContext() },
            gonePairs = pairs.gone.map { it.toBigram() },
            goneTriples = triples.gone.map { it.toTrigram() },
        )
    }

    /** What [forgetLinks] took out of one kind of link: the links gone and the contexts left changed. */
    private class Forgotten {
        val gone = ArrayList<LinkRow>()
        val changed = ArrayList<ContextRow>()
    }

    /** [forgetLinks], for [key] in [language]'s [links], into [forgotten]; [wordsOf] gives a context's words. */
    private fun <K> forget(
        links: Links<K>,
        key: String,
        language: String,
        forgotten: Forgotten,
        wordsOf: (K) -> List<String>,
    ) {
        val contexts = links.contexts.entries.iterator()
        while (contexts.hasNext()) {
            val (context, owner) = contexts.next()
            val words = wordsOf(context)
            if (key in words) {
                owner.links.forEach { (next, link) -> forgotten.gone += link.row(language, words, next) }
                links.held -= owner.links.size
                contexts.remove()
                continue
            }
            val link = owner.links.remove(key) ?: continue
            links.held--
            forgotten.gone += link.row(language, words, key)
            owner.total = (owner.total - link.count).coerceAtLeast(0L)
            owner.seen = (owner.seen - link.count).coerceAtLeast(0L)
            if (owner.links.isEmpty()) {
                contexts.remove()
            } else {
                forgotten.changed += owner.row(language, words)
            }
        }
    }

    /** The pairs after [previous] in [language]'s store; none, and zeros, for a context not held. */
    fun pairsAfter(language: String, previous: String): ContextLinks =
        offered(stores[language]?.pairs?.contexts?.get(previous))

    /** The triples after [previous2] and [previous1] in [language]'s store, as [pairsAfter] gives the pairs. */
    fun triplesAfter(language: String, previous2: String, previous1: String): ContextLinks =
        offered(stores[language]?.triples?.contexts?.get(previous2 to previous1))

    /**
     * The slice of the context after [previous1], or the sentence start when it is null, and
     * [previous2] in [language]'s store, as the engine is handed it ([SliceRows]): the language's id,
     * the two words' ids ([SliceRows.NONE] for one not held or not given), every pair and triple of
     * the context in the order [pairsAfter] gives them, each context's total and observations, and
     * for each of the first [SliceRows.MAX_HOPS] pairs, the first pair after its word and the total
     * of that word's context. Written into [into], whose arrays are kept while they hold enough.
     */
    fun slice(language: String, previous1: String?, previous2: String?, into: SliceRows = SliceRows()): SliceRows {
        val before1 = PhraseKey.word(previous1 ?: UserBigram.SENTENCE_START)
        val before2 = previous2?.let(PhraseKey::word)
        val store = stores[language]
        val pairs = store?.pairs?.contexts?.get(before1)
        val triples = before2?.let { store?.triples?.contexts?.get(it to before1) }
        val pairLinks = ordered(pairs)
        val tripleLinks = ordered(triples)
        val linked = pairLinks + tripleLinks
        val firsts = pairLinks.take(SliceRows.MAX_HOPS)
        into.room(linked.size, firsts.size)
        val header = into.header
        key(language, previous1, previous2, header)
        header[SliceRows.PAIR_TOTAL] = clamped(pairs?.total ?: 0L)
        header[SliceRows.TRIPLE_TOTAL] = clamped(triples?.total ?: 0L)
        header[SliceRows.PAIRS] = pairLinks.size
        header[SliceRows.TRIPLES] = tripleLinks.size
        val links = into.links
        val seen = into.seen
        seen[SliceRows.PAIR_SEEN] = pairs?.seen ?: 0L
        seen[SliceRows.TRIPLE_SEEN] = triples?.seen ?: 0L
        for ((at, entry) in linked.withIndex()) {
            val (word, link) = entry
            val row = at * SliceRows.LINK_FIELDS
            links[row + SliceRows.LINK_WORD] = idOf(word)
            links[row + SliceRows.LINK_COUNT] = link.count
            links[row + SliceRows.LINK_ASSERTED] = link.asserted
            seen[SliceRows.CONTEXT_SEEN_FIELDS + at] = link.contextSeen
        }
        header[SliceRows.HOPS] = firsts.size
        val hops = into.hops
        for ((at, first) in firsts.withIndex()) {
            val middle = store?.pairs?.contexts?.get(first.key)
            val after = ordered(middle).firstOrNull()
            val row = at * SliceRows.HOP_FIELDS
            hops[row + SliceRows.HOP_WORD] = after?.let { idOf(it.key) } ?: SliceRows.NONE
            hops[row + SliceRows.HOP_COUNT] = after?.value?.count ?: 0
            hops[row + SliceRows.HOP_ASSERTED] = after?.value?.asserted ?: 0
            hops[row + SliceRows.HOP_MIDDLE_TOTAL] = clamped(middle?.total ?: 0L)
        }
        return into
    }

    /** Holds [spelling]'s word with no counts when it is new, its key added to [changed]. */
    private fun hold(spelling: String, changed: MutableList<String>) {
        val key = PhraseKey.word(spelling)
        if (key !in words) {
            words[key] = Word(mutableListOf(Form(spelling, 0, 0L)))
            changed += key
        }
    }

    /** The rows of the words keyed [keys], each as [handed] gives it. */
    private fun rowsOf(keys: List<String>): WordRows {
        val fields = IntArray(keys.size * WordRows.FIELDS)
        val spellings = arrayOfNulls<String>(keys.size)
        val ids = IntArray(keys.size)
        for ((at, key) in keys.withIndex()) {
            val word = words.getValue(key)
            val merged = handed(key, word)
            ids[at] = word.id
            spellings[at] = merged.spelling
            val row = at * WordRows.FIELDS
            fields[row + WordRows.COUNT] = merged.count
            fields[row + WordRows.DELIBERATE_CAPITALS] = merged.deliberateCapitals
            fields[row + WordRows.ASSERTED] = merged.asserted
            fields[row + WordRows.ADDED] = merged.added
        }
        return WordRows(keys, ids, spellings, fields)
    }

    /** [word], keyed [key], as [HeldWord] gives its entry. */
    private fun handed(key: String, word: Word): PersonalSpelling.Merged {
        val counts = WordCounts(0, word.languages, word.unplaced)
        var count = word.unplaced
        for (language in word.languages.keys) {
            if (packs.counts(language)) {
                count = maxOf(count, counts.inLanguage(language))
            }
        }
        val merged = PersonalSpelling.merge(key, word.forms)
        if (count == 0) {
            return merged.copy(count = 0, deliberateCapitals = 0, asserted = 0, added = 0)
        }
        return merged.copy(count = count)
    }

    /** Whether the engine's word table holds [key]. */
    private fun heldWord(key: String): Boolean = (words[key]?.id ?: WordRows.NEW_WORD) >= 0

    /** Whether a link from [last] to [word] can be held: both words held, and [word] not following itself. */
    private fun linkable(last: String, word: String): Boolean = heldWord(last) && heldWord(word) && last != word

    /** [language]'s store, created when new; null for no language. */
    private fun storeFor(language: String): Store? =
        if (language.isEmpty()) null else stores.getOrPut(language) { Store() }

    /** Raises the context's total, observations and last use to [total], [seen] and [lastUsedAt], never lowering them. */
    private fun Context.raise(total: Long, seen: Long, lastUsedAt: Long) {
        this.total = maxOf(this.total, total)
        this.seen = maxOf(this.seen, seen)
        this.lastUsedAt = maxOf(this.lastUsedAt, lastUsedAt)
    }

    /**
     * The key a request names the context after [previous1], or the sentence start when it is
     * null, and [previous2] in [language] by: [SliceRows.KEY_SLOTS] ints, as [slice]'s header
     * opens, written into the first of [into].
     */
    fun key(
        language: String,
        previous1: String?,
        previous2: String?,
        into: IntArray = IntArray(SliceRows.KEY_SLOTS),
    ): IntArray {
        val key = into
        key[SliceRows.LANGUAGE] = languageIds.getOrPut(language) { languageIds.size }
        key[SliceRows.CONTEXT1] = idOf(PhraseKey.word(previous1 ?: UserBigram.SENTENCE_START))
        key[SliceRows.CONTEXT2] = previous2?.let { idOf(PhraseKey.word(it)) } ?: SliceRows.NONE
        return key
    }

    /** The id the engine's word table holds [key] under, or [SliceRows.NONE]. */
    private fun idOf(key: String): Int = words[key]?.id ?: SliceRows.NONE

    /** [value] as an int, held at the largest. */
    private fun clamped(value: Long): Int = minOf(value, Int.MAX_VALUE.toLong()).toInt()

    /**
     * Adds [link]'s uses and choices to the link of [word] after [context], its stamps and last use
     * the later of its own and [link]'s, and the uses to the context's total; a new link is held
     * only while [links] holds fewer than [most].
     */
    private fun <K> add(links: Links<K>, context: K, word: String, link: Link, most: Int = PhraseBudget.LANGUAGE_CAP) {
        val held = links.contexts[context]?.links?.get(word)
        if (held != null) {
            held.count += link.count
            held.asserted += link.asserted
            held.added += link.added
            held.contextSeen = maxOf(held.contextSeen, link.contextSeen)
            held.languageSeen = maxOf(held.languageSeen, link.languageSeen)
            held.lastUsedAt = maxOf(held.lastUsedAt, link.lastUsedAt)
            links.contexts.getValue(context).total += link.count
            return
        }
        if (links.held >= most) {
            return
        }
        val owner = links.contexts.getOrPut(context) { Context() }
        owner.links[word] = link
        owner.total += link.count
        links.held++
    }

    /** Records one use of [word] after [context] in [links] at [at], as [recordPair] describes. */
    private fun <K> learn(links: Links<K>, context: K, word: String, asserted: Boolean, at: Long, added: Boolean) {
        val owner = links.contexts.getOrPut(context) { Context() }
        owner.seen++
        owner.lastUsedAt = maxOf(owner.lastUsedAt, at)
        links.clock++
        val link = Link(1, if (asserted) 1 else 0, owner.seen, links.clock, at, if (added) 1 else 0)
        add(links, context, word, link, Int.MAX_VALUE)
    }

    /** What the budget left of a link just recorded, and what it gave up, in order. */
    private class Budgeted(val kept: LinkRow, val gone: List<LinkRow>)

    /**
     * Runs the budget on the link of [word] after [context] in [links], just recorded: its context
     * keeps [limits] ([PhraseBudget.overflow]), then the language is pruned past
     * [PhraseBudget.LANGUAGE_CAP] ([languageGone]); [wordsOf] gives a context's words.
     */
    private fun <K> budget(
        links: Links<K>,
        context: K,
        word: String,
        limits: PhraseBudget.Limits,
        language: String,
        wordsOf: (K) -> List<String>,
    ): Budgeted {
        val owner = links.contexts.getValue(context)
        val rows = owner.links.map { (next, link) -> link.row(language, wordsOf(context), next) }
        val kept = rows.first { it.word == word }
        val gone = PhraseBudget.overflow(
            rows, kept, owner.seen, limits, { it.count }, { it.contextSeen }, { it.asserted > 0 },
        )
        gone.forEach { drop(links, context, it.word) }
        val pruned = if (PhraseBudget.languageExcess(links.held) == 0) {
            emptyList()
        } else {
            val all = links.contexts.flatMap { (held, linksAfter) ->
                linksAfter.links.map { (next, link) -> link.row(language, wordsOf(held), next) }
            }
            val totals = links.contexts.entries.associate { (held, linksAfter) -> wordsOf(held) to linksAfter.total }
            languageGone(all, totals, links.clock, kept)
        }
        if (pruned.isNotEmpty()) {
            val keys = links.contexts.keys.associateBy(wordsOf)
            pruned.forEach { link -> keys[link.context]?.let { drop(links, it, link.word) } }
        }
        return Budgeted(kept, gone + pruned)
    }

    /** The link as a row: its language, its context's words and its last word. */
    private fun Link.row(language: String, context: List<String>, word: String) =
        LinkRow(language, context, word, count, lastUsedAt, contextSeen, languageSeen, asserted, added)

    /** The context as a row: its language and its words. */
    private fun Context.row(language: String, context: List<String>) =
        ContextRow(language, context, total, lastUsedAt, seen)

    /**
     * Takes the link of [word] after [context] out of [links]; the context keeps its uses while a
     * link still follows it, and goes, as the personal dictionary drops it, once none does.
     */
    private fun <K> drop(links: Links<K>, context: K, word: String) {
        val owner = links.contexts[context] ?: return
        if (owner.links.remove(word) != null) {
            links.held--
        }
        if (owner.links.isEmpty()) {
            links.contexts.remove(context)
        }
    }

    /** [context]'s links in the order the engine offers them, with its total and observations. */
    private fun offered(context: Context?): ContextLinks {
        context ?: return ContextLinks(emptyList(), 0L, 0L)
        val links = ordered(context)
            .map { (word, link) -> HeldLink(word, link.count, link.asserted, link.contextSeen, link.languageSeen) }
        return ContextLinks(links, context.total, context.seen)
    }

    /** [context]'s links, by their last word, in the order the engine offers them; none for no context. */
    private fun ordered(context: Context?): List<Map.Entry<String, Link>> =
        context?.links?.entries?.sortedWith(compareBy(offeredFirst) { it.value }).orEmpty()
}
