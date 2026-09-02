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
import re
import subprocess
import sys
import unicodedata
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent

# A word is letters, plus the marks that belong to them, plus the apostrophes and hyphens that
# appear inside words. Deliberately not \w: that admits digits and underscores, and a dictionary
# full of "covid19" and "foo_bar" predicts nothing anyone types.
WORD = re.compile(r"[^\W\d_]+(?:['’-][^\W\d_]+)*", re.UNICODE)


# Sequences that only appear when UTF-8 has been decoded as Latin-1 somewhere upstream: "dacă"
# arriving as "dacÄƒ". Real corpora carry some of this, it survives every frequency cutoff
# because the mis-encoding is consistent, and it reaches the suggestion strip looking like a
# word. Cheaper to refuse here than to explain later.
MOJIBAKE = re.compile(r"[ÂÃÄÅ][\u0080-\u00bf\u0192\u2020-\u203a]")


def tokenise(line: str) -> list[str]:
    """Lower-cases and splits a line into words, keeping the accents."""
    normalised = unicodedata.normalize("NFC", line)
    return [
        m.group(0).lower()
        for m in WORD.finditer(normalised)
        if not MOJIBAKE.search(m.group(0))
    ]


# Not a word, and it cannot be one: the tokeniser only ever emits letters, so nothing in a
# corpus can collide with it. The pack compiler turns it into a reserved index.
SENTENCE_START = "\x02start"


def count_corpus(paths: list[Path], order: int) -> tuple[Counter, Counter, Counter]:
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
                for token in tokenise(line):
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
            try:
                counts[word] += int(parts[-1])
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

    if arguments.corpus:
        words, bigrams, trigrams = count_corpus(arguments.corpus, arguments.order)
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
        "".join(f"{w}\t{c}\n" for w, c in ranked), encoding="utf-8")
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
