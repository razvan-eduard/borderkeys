#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Builds a personal-history corpus for PersonalCorpusTest from a language's held-out Leipzig
sentences.

The persona is every held-out sentence that mentions the word the list flags as a name that the
held-out file writes most, in file order, less the sentences a counted corpus also writes
(heldout_overlap.py, which must admit the language). Sentences are split into runs the way the
keyboard reads the words before the caret (make_context_corpus.runs), Romanian words spelled as
normalise_diacritics.py spells the list.

The first half of the persona is the history, ending before the sentence after which the pairs
or the triples written the learn-after number of times would outnumber what the keyboard loads.
The second half gives the rows: words of lower-case letters with two words before them in their
run, each word and context once, typed with the typo of the slip, omitted, extra and midtypo
corpora in turn, the typo not a word of the list, and typed as written when the list spells the
word exactly. The rows are drawn to the same number per
class, as the history's counts estimate it: `meant`, a learned pair or triple ends in the word;
`others`, the context learned other words; `none`, it learned nothing.

Writes `personal_history_<language>.txt`, one run per line, and `personal_rows_<language>.tsv`,
`typed<TAB>meant<TAB>kind<TAB>previous2 previous1`.

    python3 tools/make_personal_corpus.py en_US native-tests/data
"""

import argparse
import collections
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import corpus_words  # noqa: E402
import heldout_overlap  # noqa: E402
import make_extra_corpus  # noqa: E402
import make_midtypo_corpus  # noqa: E402
import make_omitted_corpus  # noqa: E402
import make_slip_corpus  # noqa: E402
import normalise_diacritics  # noqa: E402
from make_context_corpus import runs  # noqa: E402

REPOSITORY = Path(__file__).resolve().parent.parent

SEED = 20261011

# Rows drawn per class, as many as each typo corpus holds.
COUNT = make_slip_corpus.COUNT

# KeyboardPreferences.DEFAULT_LEARN_AFTER_USES.
LEARN_AFTER_USES = 3

# PhraseBudget.LANGUAGE_CAP: the pairs, and the triples, one language keeps.
LOADED_PAIRS = 10_000
LOADED_TRIPLES = 10_000

# LearningBuffer.MIN_WORD_LENGTH and MAX_WORD_LENGTH.
MIN_LEARNED_LENGTH = 2
MAX_LEARNED_LENGTH = 64

# UserBigram.SENTENCE_START: the context of a run's first word.
SENTENCE_START = "\u0002start"

# A row's word has its previous2 and previous1 in its run.
WORDS_BEFORE = 2

# The typo corpora whose typos the rows take in turn.
TYPOS = (
    ("slip", make_slip_corpus),
    ("omitted", make_omitted_corpus),
    ("extra", make_extra_corpus),
    ("midtypo", make_midtypo_corpus),
)

CLASSES = ("meant", "others", "none")

# The tag whose text is spelled through normalise_diacritics.
ROMANIAN = "ro_RO"


def speller(tag, words):
    """How [tag]'s text is spelled as its list spells it: Romanian through normalise_diacritics."""
    if tag != ROMANIAN:
        return lambda word: word
    known = {word for word, _ in words.rows}
    names = {word for word, name in words.rows if name}
    return lambda word: normalise_diacritics.normalised(word, known, word in names)[0]


def spelled_runs(text, spell):
    """[text]'s runs, each word as [spell] spells it."""
    return [[spell(word) for word in run] for run in runs(text)]


def learnable(word: str) -> bool:
    return MIN_LEARNED_LENGTH <= len(word) <= MAX_LEARNED_LENGTH


class History:
    """The pairs and triples the keyboard records from runs, and which of them are learned."""

    def __init__(self):
        self.pairs = collections.Counter()
        self.triples = collections.Counter()
        self.learned_pairs = 0
        self.learned_triples = 0
        self.successors = collections.defaultdict(set)
        self.triple_successors = collections.defaultdict(set)

    @staticmethod
    def links(run):
        """The pair and triple keys a run records, as LearningFlow.record makes them."""
        pairs = []
        triples = []
        for at, word in enumerate(run):
            if not learnable(word):
                continue
            previous1 = run[at - 1] if at >= 1 else SENTENCE_START
            if len(previous1) <= MAX_LEARNED_LENGTH and previous1 != word:
                pairs.append((previous1, word))
            if at >= WORDS_BEFORE:
                previous2 = run[at - 2]
                if (len(previous2) <= MAX_LEARNED_LENGTH and len(previous1) <= MAX_LEARNED_LENGTH
                        and previous1 != word):
                    triples.append((previous2, previous1, word))
        return pairs, triples

    def learned_after(self, sentence_runs):
        """How many pairs and triples are learned once every run of [sentence_runs] is added."""
        pairs = collections.Counter()
        triples = collections.Counter()
        for run in sentence_runs:
            run_pairs, run_triples = self.links(run)
            pairs.update(run_pairs)
            triples.update(run_triples)
        new_pairs = sum(1 for key, added in pairs.items()
                        if self.pairs[key] < LEARN_AFTER_USES <= self.pairs[key] + added)
        new_triples = sum(1 for key, added in triples.items()
                          if self.triples[key] < LEARN_AFTER_USES <= self.triples[key] + added)
        return self.learned_pairs + new_pairs, self.learned_triples + new_triples

    def add(self, run):
        pairs, triples = self.links(run)
        for key in pairs:
            self.pairs[key] += 1
            if self.pairs[key] == LEARN_AFTER_USES:
                self.learned_pairs += 1
                self.successors[key[0]].add(key[1])
        for key in triples:
            self.triples[key] += 1
            if self.triples[key] == LEARN_AFTER_USES:
                self.learned_triples += 1
                self.triple_successors[key[:2]].add(key[2])

    def class_of(self, previous2: str, previous1: str, word: str) -> str:
        if (word in self.triple_successors.get((previous2, previous1), ())
                or word in self.successors.get(previous1, ())):
            return "meant"
        if self.triple_successors.get((previous2, previous1)) or self.successors.get(previous1):
            return "others"
        return "none"


def persona_word(sentences, names, spell) -> str:
    """The name the sentences write most; the first in alphabetical order among equals."""
    counts = collections.Counter()
    for text in sentences:
        for run in spelled_runs(text, spell):
            counts.update(word for word in run if word in names)
    best = max(counts.values())
    return min(word for word, count in counts.items() if count == best)


def typo(word, module, rng, words):
    """[word] with [module]'s typo, or None when the word is outside that corpus's lengths or the
    typo is a word of the list."""
    if len(word) < module.MIN_LENGTH:
        return None
    longest = getattr(module, "MAX_LENGTH", None)
    if longest is not None and len(word) > longest:
        return None
    case = module.mangled(word, rng)
    if case is None or case == word or words.is_word(case):
        return None
    return case


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("tag", choices=sorted(heldout_overlap.CORPORA))
    parser.add_argument("out", type=Path)
    parser.add_argument("--leipzig", type=Path, default=heldout_overlap.LEIPZIG)
    parser.add_argument("--heldout", type=Path, default=heldout_overlap.HELDOUT)
    parser.add_argument("--wordlist", type=Path, default=heldout_overlap.WORDLIST)
    args = parser.parse_args()

    verdict = heldout_overlap.verdict(args.tag, args.leipzig, args.heldout, args.wordlist)
    if not verdict.admitted:
        sys.exit(f"{args.tag}: refused; see tools/heldout_overlap.py {args.tag}")
    overlap = heldout_overlap.shared(args.tag, args.leipzig, args.heldout, args.wordlist)
    shared = set().union(*overlap.values())

    words = corpus_words.load(REPOSITORY / "dictionaries" / f"{args.tag}.tsv")
    names = {word for word, name in words.rows if name}
    spelled = {word for word, _ in words.rows}
    spell = speller(args.tag, words)

    held = []
    with heldout_overlap.heldout_path(args.tag, args.heldout).open(
            encoding="utf-8", errors="replace") as source:
        for line in source:
            text = heldout_overlap.sentence_text(line)
            if heldout_overlap.fingerprint(text) not in shared:
                held.append(text)

    persona = persona_word(held, names, spell)
    mentioning = [text for text in held if any(persona in run for run in spelled_runs(text, spell))]
    first, second = mentioning[:len(mentioning) // 2], mentioning[len(mentioning) // 2:]

    history = History()
    history_runs = []
    used = 0
    for text in first:
        sentence = spelled_runs(text, spell)
        learned_pairs, learned_triples = history.learned_after(sentence)
        if learned_pairs > LOADED_PAIRS or learned_triples > LOADED_TRIPLES:
            break
        for run in sentence:
            history.add(run)
            history_runs.append(run)
        used += 1

    candidates = {name: [] for name in CLASSES}
    seen = set()
    for text in second:
        for run in spelled_runs(text, spell):
            for at in range(WORDS_BEFORE, len(run)):
                word = run[at]
                if not (word.isalpha() and word.islower()):
                    continue
                previous2, previous1 = run[at - 2], run[at - 1]
                if (word, previous2, previous1) in seen:
                    continue
                seen.add((word, previous2, previous1))
                candidates[history.class_of(previous2, previous1, word)].append(
                    (word, previous2, previous1))

    rng = random.Random(SEED)
    rows = []
    drawn = {}
    for name in CLASSES:
        pool = candidates[name]
        rng.shuffle(pool)
        taken = 0
        for word, previous2, previous1 in pool:
            if taken == COUNT:
                break
            kind, module = TYPOS[taken % len(TYPOS)]
            case = typo(word, module, rng, words)
            if case is None:
                continue
            context = f"{previous2} {previous1}"
            if word in spelled:
                rows.append((word, word, "clean", context))
            rows.append((case, word, kind, context))
            taken += 1
        drawn[name] = taken

    language = args.tag.split("_")[0]
    args.out.mkdir(parents=True, exist_ok=True)
    source = verdict.heldout
    overlap_note = ", ".join(f"{name} {len(prints)}" for name, prints in overlap.items())
    history_path = args.out / f"personal_history_{language}.txt"
    with history_path.open("w", encoding="utf-8") as out:
        out.write(f"# A persona's history from {source}: the sentences mentioning \"{persona}\", "
                  f"first half, {used} of {len(first)} sentences, {len(history_runs)} runs.\n")
        out.write(f"# Generated by tools/make_personal_corpus.py. Sentences also written in the "
                  f"counted corpora were left out: {overlap_note}.\n")
        out.write(f"# Learned at {LEARN_AFTER_USES} uses: {history.learned_pairs} pairs, "
                  f"{history.learned_triples} triples.\n")
        for run in history_runs:
            out.write(" ".join(run) + "\n")
    rows_path = args.out / f"personal_rows_{language}.tsv"
    with rows_path.open("w", encoding="utf-8") as out:
        out.write(f"# Rows after the history in personal_history_{language}.txt, from the second "
                  f"half of the same persona, {len(second)} sentences.\n")
        out.write(f"# Generated by tools/make_personal_corpus.py, seed {SEED}. Per class, "
                  + ", ".join(f"{name} {drawn[name]}" for name in CLASSES)
                  + " words, each typed with a typo, and as written when the list spells it.\n")
        out.write("# typed<TAB>meant<TAB>kind<TAB>previous2 previous1\n")
        for typed, meant, kind, context in rows:
            out.write(f"{typed}\t{meant}\t{kind}\t{context}\n")
    print(f"{history_path}: {len(history_runs)} runs; {rows_path}: {len(rows)} rows "
          f"({', '.join(f'{name} {drawn[name]}' for name in CLASSES)})", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
