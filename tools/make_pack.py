#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Turns a real corpus or frequency list into a BorderKeys language pack.

`build_dict.py` takes a word list that already has frequencies and n-gram counts on a consistent
scale. Nothing produces those by hand at any useful size, and nothing should: the data exists,
freely licensed, and the work is getting it into shape. That is what this does.

Standard library only, like everything else in tools/. It downloads nothing -- point it at files
you fetched yourself, so the licence of what goes into a pack is a decision you made rather than
one a script made for you. `docs/dictionaries.md` names the free sources and their terms.

Input, in order of how good the result is
-----------------------------------------
  --corpus FILE...      plain text. Words and their pairs are counted from the same text, which
                        is the only way the two are guaranteed to be on the same scale -- see the
                        note on backoff below. This is the best input and usually the easiest to
                        find: any large body of writing in the language will do.

  --frequencies FILE    "word count" or "word<TAB>count", one per line. The format the
                        OpenSubtitles and Wortschatz lists come in. Gives good unigrams and no
                        context, so the keyboard corrects well and predicts the next word badly.

  --ngram-counts FILE   "w1 w2 count" or "w1<TAB>w2<TAB>count", and the same with three words.
                        Combine with --frequencies when the two come from the same corpus.

  --wordlist FILE       bare words, no counts, one per line -- a Hunspell expansion or a spell
                        checker's list. Every word gets the same frequency, which means the
                        keyboard knows the words and nothing about which are common. Accepted
                        because coverage with no ranking still beats no dictionary, and refused
                        silently would be worse than warned about loudly.

  --names FILE          a make_names.py output: proper names, flagged in the compiled pack so
                        the keyboard always capitalises them (see build_dict.py's
                        WORD_FLAG_PROPER_NOUN) regardless of typed case or sentence position.
                        Merged in after the frequency-based cutoffs below, not subject to them --
                        a name absent from a corpus is not evidence it is rare, only that it is a
                        name, which is exactly the case this file exists to cover.

Why the scales have to match
----------------------------
The engine backs off with the usual rule: if a bigram is known it uses P(w2|w1), otherwise
0.4 * P(w2). Those two are only comparable when the counts come from the same body of text. Mix a
frequency list from one corpus with n-gram counts from another and every bigram will look either
impossibly likely or impossibly rare, and the context model quietly stops working. Counting both
from one corpus is why --corpus is the recommended path.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import unicodedata
from collections import Counter
from pathlib import Path

from build_dict import plain_apostrophes, without_vowel_marks

HERE = Path(__file__).resolve().parent


class Alphabet:
    """What the keyboard for one language can type, and which bare letters are words there.

    Read from tools/drop_unreachable.py, which builds it from the long-press overlay the keys
    themselves are drawn from, so the corpus and the keyboard cannot disagree about which
    letters a language has.
    """

    def __init__(self, tag: str, rules, build_dict):
        self.fold = build_dict.fold_word
        self.folded = rules.alphabet_of(tag, build_dict.fold_code_point)
        self.single_letters = set(rules.SINGLE_LETTER_WORDS[tag])


def alphabet_for(tag: str) -> Alphabet | None:
    """The alphabet for a BCP-47 tag, or None where this project has declared none."""
    import importlib.util  # noqa: PLC0415 -- one tool reaching for another, not a dependency

    spec = importlib.util.spec_from_file_location("drop_unreachable",
                                                  HERE / "drop_unreachable.py")
    rules = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(rules)
    key = tag.replace("-", "_")
    if key not in rules.SINGLE_LETTER_WORDS:
        return None
    return Alphabet(key, rules, rules.load_build_dict())


# A word is letters, plus the marks that belong to them, plus the apostrophes and hyphens that
# appear inside words. Deliberately not \w: that admits digits and underscores, and a dictionary
# full of "covid19" and "foo_bar" predicts nothing anyone types. Every apostrophe a word holds is
# written as the plain one on the way in, and the Hebrew and Arabic vowel marks, which the fold
# drops, are left out of the spelling.
LETTER = r"(?:[^\W\d_]|[֑-ׇً-ٰٟۖ-ۭ])"
WORD = re.compile(rf"{LETTER}+(?:['’‘ʼ׳-]{LETTER}+)*", re.UNICODE)


# Sequences that only appear when UTF-8 has been decoded as Latin-1 somewhere upstream: "dacă"
# arriving as "dacÄƒ". Real corpora carry some of this, it survives every frequency cutoff
# because the mis-encoding is consistent, and it reaches the suggestion strip looking like a
# word. Cheaper to refuse here than to explain later.
MOJIBAKE = re.compile(r"[ÂÃÄÅ][\u0080-\u00bf\u0192\u2020-\u203a]")


def admits(word: str, alphabet: Alphabet | None) -> bool:
    """Whether [word] is a word of the language [alphabet] describes.

    [WORD] matches letters of any script and matches a single one of them, because a regular
    expression is the wrong place to know which letters a language has. That belongs to the
    keyboard, which is the thing that has to be able to type the word: see
    drop_unreachable.alphabet_of, reading the same long-press overlay the keys are drawn from.
    Without an alphabet -- a tag this project ships no overlay decision for -- every token is
    admitted, which is what this did for every corpus counted before now.
    """
    if alphabet is None:
        return True
    if any(folded not in alphabet.folded for folded in alphabet.fold(word)):
        return False
    return len(word) != 1 or word in alphabet.single_letters


def tokenise(line: str, alphabet: Alphabet | None = None) -> list[str]:
    """Lower-cases and splits a line into words, keeping the accents."""
    normalised = unicodedata.normalize("NFC", line)
    return [
        lowered
        for m in WORD.finditer(normalised)
        if not MOJIBAKE.search(m.group(0))
        for lowered in (without_vowel_marks(plain_apostrophes(m.group(0).lower())),)
        if lowered and admits(lowered, alphabet)
    ]


# Not a word, and it cannot be one: the tokeniser only ever emits letters, so nothing in a
# corpus can collide with it. The pack compiler turns it into a reserved index.
SENTENCE_START = "\x02start"


def count_corpus(paths: list[Path], order: int,
                 alphabet: Alphabet | None = None) -> tuple[Counter, Counter, Counter]:
    """Counts words, pairs and triples from plain text, in one pass, without holding the text."""
    words: Counter = Counter()
    bigrams: Counter = Counter()
    trigrams: Counter = Counter()
    for path in paths:
        with path.open(encoding="utf-8", errors="replace") as handle:
            previous1: str | None = None
            previous2: str | None = None
            for line in handle:
                first = True
                for token in tokenise(line, alphabet):
                    words[token] += 1
                    if first:
                        # What a sentence opens with, counted as a pair with a marker that is
                        # not a word. Raw frequency is a bad answer to "what might you write
                        # next" on an empty field: the most common words in any language are
                        # the ones that join clauses, and nobody starts a message with "de".
                        bigrams[(SENTENCE_START, token)] += 1
                        first = False
                    if previous1 is not None:
                        bigrams[(previous1, token)] += 1
                        if order >= 3 and previous2 is not None:
                            trigrams[(previous2, previous1, token)] += 1
                    previous2 = previous1
                    previous1 = token
                # A line break ends a context. Without this the last word of one line predicts
                # the first of the next, which in a corpus of subtitles or paragraphs is noise.
                previous1 = None
                previous2 = None
    return words, bigrams, trigrams


def read_frequencies(path: Path) -> Counter:
    counts: Counter = Counter()
    with path.open(encoding="utf-8", errors="replace") as handle:
        for line in handle:
            parts = line.split()
            if len(parts) < 2:
                continue
            word = parts[0].lower()
            # A row ending in the literal "name" is this project's own proper-noun tag (see
            # build_dict.py's load_words), not an external frequency list's own trailing column --
            # its frequency sits second-to-last, not last. Reading it as parts[-1] silently threw
            # away every name-flagged row (int("name") raises, caught below, row dropped) the
            # first time a dictionary already containing one was fed back in as --frequencies.
            frequency_index = -2 if len(parts) >= 3 and parts[-1] == "name" else -1
            try:
                counts[word] += int(parts[frequency_index])
            except ValueError:
                continue
    return counts


def read_ngram_counts(path: Path) -> tuple[Counter, Counter]:
    bigrams: Counter = Counter()
    trigrams: Counter = Counter()
    with path.open(encoding="utf-8", errors="replace") as handle:
        for line in handle:
            parts = line.split()
            if len(parts) < 3:
                continue
            try:
                count = int(parts[-1])
            except ValueError:
                continue
            words = [w.lower() for w in parts[:-1]]
            if len(words) == 2:
                bigrams[(words[0], words[1])] += count
            elif len(words) == 3:
                trigrams[(words[0], words[1], words[2])] += count
    return bigrams, trigrams


# What "proper noun" is called in each tagset the bundled grammars use (see docs/pos-tagging.md):
# MULTEXT-East for Romanian (Np, plus its inflected forms), Penn for English (NNP/NNPS), STTS for
# German (NE), UD-derived sets for Spanish and French (PROPN...), and ISDT for Italian (SP). Exact
# for the two-letter ones -- "NE" must not match a negation tag, "SP" must not match MULTEXT's
# "Spsa" preposition -- and a prefix for the rest.
PROPER_NOUN_TAGS = frozenset({"NE", "SP"})
PROPER_NOUN_TAG_PREFIXES = ("Np", "NNP", "PROPN")


def is_proper_noun_tag(tag: str) -> bool:
    return tag in PROPER_NOUN_TAGS or tag.startswith(PROPER_NOUN_TAG_PREFIXES)


def strip_accents(word: str) -> str:
    """The spelling without accents, by Unicode's own decomposition -- enough to tell "the same
    word without its accents" from a different word (see common_words)."""
    return "".join(c for c in unicodedata.normalize("NFD", word)
                   if unicodedata.category(c) != "Mn")


def is_ordinary(name: str, ordinary: "OrdinaryWords", frequencies: dict[str, int]) -> bool:
    """Whether a name from the list is really one of the language's ordinary words.

    An exact match on the lower-cased spelling is one. So is a match once accents are stripped
    from both sides -- the compiler folds accents away too, so "Sá" (a Portuguese surname) lands
    on the same trie entry as "să" (Romanian "to"), the flag is OR'd across the spellings that
    fold together, and every "sa" anyone typed came out "Să" -- but only when the ordinary word
    is at least as frequent in the corpus as the name is: "și" outweighs "si" and refuses it,
    while "măria" (41 uses) must not refuse "Maria" (7,149), which is what an accent-blind rule
    did. A name the corpus does not know at all counts as frequency zero, so any real word wins.
    """
    lowered = name.lower()
    if lowered in ordinary.exact:
        return True
    accented = ordinary.folded.get(strip_accents(lowered))
    if accented is None:
        return False
    return frequencies.get(accented, 0) >= frequencies.get(lowered, 0)


class OrdinaryWords:
    """[exact]: the treebank's ordinary words, lower-cased. [folded]: the same words keyed by
    their accent-stripped spelling, keeping the most frequent one where several collide.
    [proper]: the words the treebank tags as proper nouns, lower-cased -- the one positive
    signal a treebank gives, read by name_allowed. [spelled]: the corpus words that are
    lower-case headwords of the language's spelling dictionary (make_ordinary.py) -- the
    witness for the words the treebank never met."""

    def __init__(self, exact: set[str], folded: dict[str, str], proper: set[str] | None = None,
                 spelled: set[str] | None = None) -> None:
        self.exact = exact
        self.folded = folded
        self.proper = proper or set()
        self.spelled = spelled or set()

    @staticmethod
    def empty() -> "OrdinaryWords":
        return OrdinaryWords(set(), {}, set(), set())


# How many real people Wikidata has to know by a name before a word that common in the corpus
# may capitalise itself every time it is typed: (rank ceiling, people needed). A word among the
# language's 300 most frequent is almost never written as a name -- "president", "states" and
# "red" are all somebody's name to Wikidata, and every one of them would have come out
# capitalised -- so it takes thousands of people to overturn that; a word past the 8,000 most
# frequent is rare enough that a single recorded person is evidence. Read off the Romanian
# corpus against the fetch: at ranks 300-1000 the real first names carry 400-800 people
# ("Mihai" 692, "Vasile" 799, "Iulia" 637) where the words that must not be flagged carry under
# 200 ("satu" 182, "tine" 49); past rank 1000 a surname is a small family ("Trump" 82,
# "Dumitrescu" 137, "Năstase" 28) and the threshold has to drop with it. The counts are of
# people whose name item carries a label in the language, so they run low for names spelled
# the same everywhere, which is why the lower tiers are single digits.
NAME_EVIDENCE_TIERS = ((300, 2000), (1000, 400), (3000, 8), (8000, 3))

# A word among this many most frequent that the treebank has no tag for at all is not a word
# the treebank never met -- it is one it splits before tagging ("del", "au", "zur" are all
# multiword tokens in Universal Dependencies) -- and never a name. Only the very top: past it,
# a treebank simply has not seen every real first name ("Iulia", "Dana", "Klaus").
UNTAGGED_FREQUENT_RANK = 300

# Past its own vocabulary a treebank says nothing at all, the tiers below ask a single person of
# a rare word, and Wikidata knows one person named "Thunder", "Needle" and "Wage" each: several
# thousand ordinary English words came out flagged that way. The spelling dictionary is the
# second witness (make_ordinary.py) for exactly those words -- the ones the treebank never met.
# Where it has met a word, its verdict stands either way: a treebank that saw "Dan" and "Ion"
# mostly as names outranks a dictionary that also knows a martial-arts rank and a charged
# particle, and one that saw "will" mostly as a verb has already refused it. What that leaves
# is the treebank's own blind spot -- one tag per word, and a treebank of web text meets
# "Apple" and "Hidden" (the company, the valley) more often than the fruit and the adjective --
# which is what dictionaries/<tag>.names-exclude is for.

# A name the corpus never wrote down is added at the flat frequency only with this many people
# behind it -- the family-name floor the first bundled lists were built with, which kept them
# to roughly a tenth of the corpus's own size. Lower, and the given-name tail alone (over a
# hundred thousand labels a language, most of them a handful of people each) would double the
# pack and offer names nobody in the language writes.
NAME_ADD_MIN_USES = 50


def name_evidence_needed(rank: int | None) -> int:
    """People Wikidata must know by a name for a corpus word of [rank] (1 = most frequent, None
    = not in the corpus) to be flagged -- see NAME_EVIDENCE_TIERS."""
    if rank is None:
        return 1
    for ceiling, needed in NAME_EVIDENCE_TIERS:
        if rank <= ceiling:
            return needed
    return 1


def name_allowed(key: str, uses: int, rank: int | None, ordinary: OrdinaryWords, frequencies: dict[str, int]) -> bool:
    """Whether the corpus word [key] may carry the proper-noun flag on the strength of [uses]
    people Wikidata knows by that name: never when the treebank calls it an ordinary word,
    never when the treebank does not know it and the spelling dictionary calls it an ordinary
    word, never when it is frequent and the treebank has no tag for it, and otherwise only with
    as many people behind it as its frequency demands."""
    if len(key) < 2 or is_ordinary(key, ordinary, frequencies):
        return False
    if key in ordinary.spelled and key not in ordinary.proper:
        return False
    if rank is not None and rank <= UNTAGGED_FREQUENT_RANK and key not in ordinary.proper:
        return False
    return uses >= name_evidence_needed(rank)


def read_word_list(path: Path) -> set[str]:
    """One word per line, lower-cased; blank lines and # comments skipped."""
    return {line.strip().lower() for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.startswith("#")}


def read_names(path: Path) -> list[tuple[str, int, int]]:
    """A make_names.py output: (name, flat frequency, people) per row. Files written before
    the people count existed carry three columns; those names count as backed by one person,
    which the tiers above read as "rare word or nothing"."""
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        uses = int(parts[3]) if len(parts) >= 4 and parts[3].isdigit() else 1
        rows.append((parts[0], int(parts[1]), uses))
    return rows


def common_words(grammar: Path, frequencies: dict[str, int] | None = None) -> OrdinaryWords:
    """Words the language's own treebank tags as something other than a proper noun.

    A name list from Wikidata is a classification source (see make_names.py) with no idea that
    "in", "to", "of", "said" and "will" are also names of real people in its database -- enough of
    them, in fact, to pass the family-name threshold. Flagged, those words were then capitalised
    every time they were typed, which is a worse keyboard than one that never heard of names at
    all. The treebank behind the `.pos` grammar has already decided what each common word is; a
    word it calls a preposition, a verb or an adjective is not flagged, however many people are
    called that. A word it has never seen ("sadoveanu") keeps the flag: absence from a treebank is
    not evidence of anything.
    """
    data = json.loads(grammar.read_text(encoding="utf-8"))
    tagset = data["tagset"]
    frequencies = frequencies or {}
    exact: set[str] = set()
    folded: dict[str, str] = {}
    proper: set[str] = set()
    for word, index in data["tags"].items():
        if is_proper_noun_tag(tagset[index]):
            proper.add(word.lower())
            continue
        lowered = word.lower()
        exact.add(lowered)
        # The spelling without accents too: people type "si" for "și" and "cat" for "cât", the
        # corpus holds both, the treebank only the accented one -- and Wikidata has a family
        # named Si. Folded with Unicode's own decomposition, which is enough to tell "the same
        # word without its accents" from a different word; the engine's own stricter fold
        # (proximity.cpp) is not needed for that. Weighed, not applied blindly -- see is_ordinary.
        stripped = strip_accents(lowered)
        if stripped != lowered:
            current = folded.get(stripped)
            if current is None or frequencies.get(lowered, 0) > frequencies.get(current, 0):
                folded[stripped] = lowered
    return OrdinaryWords(exact, folded, proper)


def read_wordlist(path: Path) -> Counter:
    counts: Counter = Counter()
    with path.open(encoding="utf-8", errors="replace") as handle:
        for line in handle:
            word = line.strip().lower()
            # Hunspell .dic lines carry affix flags after a slash, and the first line is a count.
            word = word.split("/")[0].strip()
            if word and WORD.fullmatch(word):
                counts[word] = 1
    return counts


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Build a .bkd language pack from a corpus or a frequency list.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--corpus", type=Path, nargs="+")
    parser.add_argument("--frequencies", type=Path)
    parser.add_argument("--ngram-counts", type=Path)
    parser.add_argument("--wordlist", type=Path)
    parser.add_argument("--names", type=Path,
                        help="a make_names.py output, merged in and flagged as proper nouns")
    parser.add_argument("--names-flag-only", type=Path,
                        help="a second make_names.py output, typically fetched with a lower "
                             "--min-family-uses: a name in it that the corpus already has gains "
                             "the flag, one it does not have is NOT added -- a hundred thousand "
                             "surnames at a flat frequency would outrank the corpus's own tail")
    parser.add_argument("--names-exclude", type=Path,
                        help="one word per line (# comments): names that are really ordinary "
                             "words the grammar does not know -- never flagged, never added")
    parser.add_argument("--names-include", type=Path,
                        help="one word per line (# comments): proper nouns the name lists cannot "
                             "know -- months, weekdays -- flagged whatever the evidence says")
    parser.add_argument("--names-ordinary", type=Path,
                        help="a make_ordinary.py output: corpus words that are lower-case "
                             "headwords of the language's spelling dictionary -- never flagged "
                             "unless the treebank itself tags them as names")
    parser.add_argument("--grammar", type=Path,
                        help="the language's dictionaries/<tag>.pos; a --names entry the treebank "
                             "tags as an ordinary word (a preposition, a verb...) is not flagged")
    parser.add_argument("--tag", required=True, help="BCP-47, e.g. ro-RO")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--max-words", type=int, default=120_000,
                        help="keep the most frequent this many (default 120000)")
    parser.add_argument("--min-count", type=int, default=3,
                        help="drop words seen fewer times than this (default 3)")
    parser.add_argument("--max-ngrams", type=int, default=200_000)
    parser.add_argument("--min-ngram-count", type=int, default=3)
    parser.add_argument("--order", type=int, default=3, choices=(2, 3))
    parser.add_argument("--keep-intermediate", action="store_true",
                        help="leave the .tsv and .ngrams beside the pack, for inspection")
    arguments = parser.parse_args()

    words: Counter = Counter()
    bigrams: Counter = Counter()
    trigrams: Counter = Counter()

    # What this language's keyboard can type, which is what its dictionary may hold. None for a
    # tag with no declared alphabet, and then every token is counted as it always was.
    alphabet = alphabet_for(arguments.tag)
    if alphabet is None:
        print(f"no alphabet declared for {arguments.tag}: counting every script the corpus "
              f"quotes, which is how Devanagari and Greek became English words", file=sys.stderr)

    if arguments.corpus:
        words, bigrams, trigrams = count_corpus(arguments.corpus, arguments.order, alphabet)
    if arguments.frequencies:
        words.update(read_frequencies(arguments.frequencies))
    if arguments.ngram_counts:
        extra_bigrams, extra_trigrams = read_ngram_counts(arguments.ngram_counts)
        bigrams.update(extra_bigrams)
        trigrams.update(extra_trigrams)
    if arguments.wordlist:
        listed = read_wordlist(arguments.wordlist)
        # Only as a floor: a word already counted keeps its real count.
        for word, _ in listed.items():
            words.setdefault(word, 1)
        if not arguments.corpus and not arguments.frequencies:
            print("warning: a bare word list gives every word the same frequency, so the "
                  "keyboard will know the words and nothing about which are common.",
                  file=sys.stderr)

    if not words:
        parser.error("nothing to build from: give --corpus, --frequencies or --wordlist")

    kept = {w: c for w, c in words.items() if c >= arguments.min_count}
    if not kept:
        kept = dict(words)
    ranked = sorted(kept.items(), key=lambda item: (-item[1], item[0]))[:arguments.max_words]
    vocabulary = {w for w, _ in ranked}

    # Merged in after max_words rather than before: a name is not competing for one of the
    # corpus's own ranked slots, since names.tsv's flat frequency (see make_names.py) is not on
    # the same scale as a real corpus count and would either always lose that ranking (if the
    # flat value is low) or crowd out real words (if it is not) -- neither is the point. A word
    # that is ALREADY in the corpus (a name that also happens to be a common word, e.g. "Will")
    # keeps its own real frequency and just gains the flag, rather than being duplicated --
    # compared lower-case, since the corpus is lower-cased on the way in and the name list is
    # not; the flag is what makes the compiled word capitalise, not the spelling written here.
    proper_nouns: set[str] = set()
    excluded = read_word_list(arguments.names_exclude) if arguments.names_exclude else set()
    included = read_word_list(arguments.names_include) if arguments.names_include else set()
    proper_nouns |= {w for w in included if w in vocabulary}
    frequencies = {w: c for w, c in ranked}
    ranks = {w: index + 1 for index, (w, _) in enumerate(ranked)}
    ordinary = common_words(arguments.grammar, frequencies) if arguments.grammar else OrdinaryWords.empty()
    ordinary.exact |= excluded
    if arguments.names_ordinary:
        ordinary.spelled |= read_word_list(arguments.names_ordinary)
    if arguments.names:
        refused = 0
        for name, frequency, uses in read_names(arguments.names):
            key = name.lower()
            if not name_allowed(key, uses, ranks.get(key), ordinary, frequencies):
                refused += 1
                continue
            if key not in vocabulary:
                if uses < NAME_ADD_MIN_USES:
                    refused += 1
                    continue
                ranked.append((key, frequency))
                vocabulary.add(key)
            proper_nouns.add(key)
        print(f"names: {len(proper_nouns)} flagged, {refused} refused as ordinary words of the "
              f"language" + ("" if arguments.grammar else " (no --grammar given, so none refused)"),
              file=sys.stderr)
    if arguments.names_flag_only:
        gained = 0
        for name, _, uses in read_names(arguments.names_flag_only):
            key = name.lower()
            if key in vocabulary and key not in proper_nouns and \
                    name_allowed(key, uses, ranks.get(key), ordinary, frequencies):
                proper_nouns.add(key)
                gained += 1
        print(f"names: {gained} corpus words gained the flag from --names-flag-only", file=sys.stderr)

    # An n-gram naming a word that did not survive the cutoff cannot be looked up, and the writer
    # would drop it anyway. Filtering here keeps the intermediate files honest.
    def survives(key: tuple) -> bool:
        # The sentence marker is not a word and will never be in the vocabulary; it is resolved
        # to a reserved index by the pack compiler instead.
        return all(part in vocabulary for part in key if part != SENTENCE_START)

    ngram_rows = []
    for key, count in bigrams.items():
        if count >= arguments.min_ngram_count and survives(key):
            ngram_rows.append((count, key))
    for key, count in trigrams.items():
        if count >= arguments.min_ngram_count and survives(key):
            ngram_rows.append((count, key))
    ngram_rows.sort(key=lambda item: -item[0])
    ngram_rows = ngram_rows[:arguments.max_ngrams]

    arguments.out.parent.mkdir(parents=True, exist_ok=True)
    words_path = arguments.out.with_suffix(".tsv")
    ngrams_path = arguments.out.with_suffix(".ngrams")
    words_path.write_text(
        "".join(f"{w}\t{c}\tname\n" if w in proper_nouns else f"{w}\t{c}\n" for w, c in ranked),
        encoding="utf-8")
    ngrams_path.write_text(
        "".join("\t".join(key) + f"\t{count}\n" for count, key in ngram_rows), encoding="utf-8")

    command = [
        sys.executable, str(HERE / "build_dict.py"),
        "--words", str(words_path),
        "--tag", arguments.tag,
        "--out", str(arguments.out),
    ]
    if ngram_rows:
        command += ["--ngrams", str(ngrams_path)]
    result = subprocess.run(command, check=False)
    if result.returncode != 0:
        return result.returncode

    print(f"{len(ranked)} words, {len(ngram_rows)} n-grams, tag {arguments.tag}")
    if not arguments.keep_intermediate:
        words_path.unlink(missing_ok=True)
        ngrams_path.unlink(missing_ok=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
