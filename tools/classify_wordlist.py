#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Keeps a word list to what a language's own evidence says is a word.

One pass over `dictionaries/<tag>.tsv`, classifying each row from positive and negative
evidence, then judging it in one of two tiers by how common it is:

  the commonest `--commonest` rows   kept unless a negative rule fires and nothing positive
                                     vouches for the row;
  everything after                   kept only where something positive vouches for it.

Positive evidence, any one of which vouches for a row:
  - a spell checker of the language accepts it as written (an ordinary word), only capitalised
    (a name) or only in capitals (an acronym); `--hunspell` may name several checkers for one
    language and each is asked;
  - the language's treebank tagged it (`<tag>.pos`);
  - this repository already flags it as a name;
  - it is the possessive or the elision of a vouched row: "cameron's", "l'homme";
  - the corpus wrote it beside enough different words often enough (`<tag>.ngrams`).

Negative evidence:
  - a neighbour one edit away that is far commoner, by a margin that grows as the row gets
    shorter: a typo;
  - another language knows it far better (`--wordfreq`, optional);
  - three letters, rare and unvouched: noise.

The three case forms are asked of the spell checker rather than read from the corpus, because
make_pack.py lower-cases at intake:

    lower_ok            accepted as written        an ordinary word
    cap_ok - lower_ok   accepted only capitalised  a name
    upper_ok - the rest accepted only in capitals  an acronym

Every language has guard words; a run that would drop one stops without writing. `--coverage`
measures how many of the commonest words of a held-out frequency list the list holds before
and after, and `--review` writes every dropped row with its reason.

Standard library only, like the rest of tools/, plus the spell checker on PATH and wordfreq
when --wordfreq is given. Neither is bundled and neither is fetched.

Usage
-----
  ./classify_wordlist.py --tag en_US --hunspell en_US=/path/to/en_US --hunspell en_US=/path/to/en_GB \\
      --coverage /path/to/en_50k.txt --review /tmp/review
  ./classify_wordlist.py --tag ro_RO --hunspell ro_RO=/path/to/ro_RO --apply
"""

from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
from pathlib import Path

# The zipf points a neighbour must win by before a row is called a typo of it, by the row's own
# length: (shortest, longest, gap). A short word has fewer neighbours and each is likelier to be
# a real word of its own, so it takes more of a gap to condemn one.
TYPO_GAPS = ((6, 99, 2.0), (4, 5, 2.5), (3, 3, 3.0))

# How common a row has to be elsewhere before another language is said to own it, and by how
# much it must beat this language. The same pair tools/drop_foreign.py uses.
FOREIGN_FLOOR = 3.0
FOREIGN_MARGIN = 1.0

# A three-letter row this rare, with nothing positive behind it, is corpus noise rather than a
# word: initials, fragments, the tail of a bad decode.
SHORT_NOISE_LENGTH = 3
SHORT_NOISE_ZIPF = 3.0

# A row the corpus wrote beside at least this many different words, each pairing at least this
# often, is vouched for by use.
PAIR_MIN_COUNT = 10
PAIR_MIN_PARTNERS = 3

# The cut-offs at which coverage of a held-out list is reported.
COVERAGE_TOPS = (10000, 20000, 50000)

# German capitalises every noun, so "accepted when capitalised" says nothing about a word being
# a name there: it is the ordinary spelling of half the vocabulary.
CAPITALISES_EVERY_NOUN = {"de_DE"}

# Elided articles and pronouns whose remainder is a word of its own.
ELISIONS = ("l'", "d'", "j'", "m'", "n'", "s'", "t'", "c'", "qu'", "un'", "dell'", "all'",
            "nell'", "sull'", "dall'", "quest'")

# Rows no run may drop. Each is spelled as the list spells it.
GUARD_WORDS = {
    "en_US": ("the", "and", "gonna", "wanna", "okay", "hello", "thanks", "don't", "i'm"),
    "ro_RO": ("și", "este", "mulțumesc", "acasă", "bună", "salut"),
    "de_DE": ("und", "nicht", "danke", "hallo"),
    "es_ES": ("gracias", "hola", "años", "también"),
    "fr_FR": ("être", "merci", "bonjour", "aujourd'hui", "salut"),
    "it_IT": ("grazie", "ciao", "perché", "però"),
}


def read_rows(path: Path) -> list[tuple[str, int, bool]]:
    """`word<TAB>frequency[<TAB>name]`, in the order the file holds them."""
    rows: list[tuple[str, int, bool]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) < 2 or not parts[0]:
            continue
        try:
            frequency = int(parts[1])
        except ValueError:
            continue
        rows.append((parts[0], frequency, len(parts) > 2 and parts[2] == "name"))
    return rows


def hunspell_accepted(words: list[str], dictionary: str, transform) -> set[str]:
    """Every word of [words] the checker accepts once [transform] has been applied to it.

    One process for the whole list: hunspell reads a word per line and prints back only what
    it refuses, so the accepted set is the difference.
    """
    if not words:
        return set()
    shaped = [transform(word) for word in words]
    try:
        result = subprocess.run(
            ["hunspell", "-d", dictionary, "-l"],
            input="\n".join(shaped) + "\n",
            capture_output=True, text=True, check=False,
        )
    except FileNotFoundError:
        raise SystemExit("hunspell is not on PATH")
    refused = {line.strip() for line in result.stdout.splitlines() if line.strip()}
    return {word for word, form in zip(words, shaped) if form not in refused}


def accepted_by_any(words: list[str], dictionaries: list[str], transform) -> set[str]:
    """The union of [hunspell_accepted] over every checker given for the language."""
    out: set[str] = set()
    for dictionary in dictionaries:
        out |= hunspell_accepted(words, dictionary, transform)
    return out


def treebank_vocabulary(path: Path) -> set[str]:
    """Every word the language's treebank tagged, lower-cased -- see build_pos.py."""
    if not path.is_file():
        return set()
    tags = json.loads(path.read_text(encoding="utf-8")).get("tags", {})
    return {word.lower() for word in tags if any(c.isalpha() for c in word)}


def pair_vouched(path: Path) -> set[str]:
    """Rows the corpus wrote beside at least PAIR_MIN_PARTNERS different words, each pair at
    least PAIR_MIN_COUNT times -- see make_pack.py for the file."""
    if not path.is_file():
        return set()
    partners: dict[str, set[str]] = {}
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 3:
                continue
            try:
                count = int(parts[2])
            except ValueError:
                continue
            if count < PAIR_MIN_COUNT:
                continue
            first, second = parts[0], parts[1]
            if first != "start":
                partners.setdefault(first, set()).add(second)
            partners.setdefault(second, set()).add(first)
    return {word for word, others in partners.items() if len(others) >= PAIR_MIN_PARTNERS}


def base_of(word: str) -> str | None:
    """The row a possessive or an elision is built on, or None when it is neither."""
    if word.endswith("'s") and len(word) > 3:
        return word[:-2]
    for elision in ELISIONS:
        if word.startswith(elision) and len(word) > len(elision) + 1:
            return word[len(elision):]
    return None


def neighbours(word: str, alphabet: str) -> set[str]:
    """Every string one edit from [word]: a deletion, a transposition, a substitution."""
    out: set[str] = set()
    for i in range(len(word)):
        out.add(word[:i] + word[i + 1:])
        if i + 1 < len(word):
            out.add(word[:i] + word[i + 1] + word[i] + word[i + 2:])
        for letter in alphabet:
            if letter != word[i]:
                out.add(word[:i] + letter + word[i + 1:])
    out.discard(word)
    return out


def read_coverage_list(path: Path) -> list[str]:
    """A held-out frequency list, commonest first: `word<SPACE>count` per line."""
    words: list[str] = []
    seen: set[str] = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if not parts:
            continue
        word = parts[0].lower()
        if word not in seen:
            seen.add(word)
            words.append(word)
    return words


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dictionaries", type=Path, default=Path("dictionaries"))
    parser.add_argument("--tag", action="append", required=True,
                        help="which list to classify, repeatable")
    parser.add_argument("--hunspell", action="append", default=[], metavar="TAG=PATH",
                        required=True,
                        help="a checker for the language, TAG=/path/to/xx_XX; repeatable per tag")
    parser.add_argument("--commonest", type=int, default=65000,
                        help="how many of the commonest rows are judged conservatively")
    parser.add_argument("--wordfreq", action="store_true",
                        help="also drop rows another language knows far better (needs wordfreq)")
    parser.add_argument("--coverage", type=Path, default=None,
                        help="a held-out frequency list to report coverage against")
    parser.add_argument("--review", type=Path, default=None,
                        help="a directory to write <tag>.dropped.tsv into")
    parser.add_argument("--apply", action="store_true", help="rewrite the list; otherwise report")
    parser.add_argument("--sample", type=int, default=12, help="dropped rows to print per reason")
    arguments = parser.parse_args()

    checkers: dict[str, list[str]] = {}
    for pair in arguments.hunspell:
        tag, _, path = pair.partition("=")
        checkers.setdefault(tag, []).append(path)
    zipf_frequency = None
    if arguments.wordfreq:
        try:
            from wordfreq import zipf_frequency as zf
        except ImportError:
            raise SystemExit("--wordfreq needs the wordfreq package")
        zipf_frequency = zf

    status = 0
    for tag in arguments.tag:
        path = arguments.dictionaries / f"{tag}.tsv"
        if not path.is_file():
            print(f"{tag}: no list at {path}", file=sys.stderr)
            status = 1
            continue
        if tag not in checkers:
            print(f"{tag}: no --hunspell given for it", file=sys.stderr)
            status = 1
            continue
        if not classify(tag, path, checkers[tag], arguments, zipf_frequency):
            status = 1
    return status


def classify(tag: str, path: Path, dictionaries: list[str], arguments, zipf_frequency) -> bool:
    rows = read_rows(path)
    total = sum(frequency for _, frequency, _ in rows) or 1
    zipf = {word: math.log10(frequency / total * 1e9) for word, frequency, _ in rows}
    flagged_name = {word for word, _, is_name in rows if is_name}
    words = [word for word, _, _ in rows]
    in_list = set(words)

    lower_ok = accepted_by_any(words, dictionaries, lambda w: w)
    cap_ok = accepted_by_any(words, dictionaries, lambda w: w[:1].upper() + w[1:])
    upper_ok = accepted_by_any(words, dictionaries, lambda w: w.upper())

    if tag in CAPITALISES_EVERY_NOUN:
        spell = lower_ok | cap_ok
        name = set()
    else:
        spell = lower_ok
        name = cap_ok - lower_ok
    acro = upper_ok - cap_ok - lower_ok
    treebank = treebank_vocabulary(path.with_suffix(".pos")) & in_list
    pairs = pair_vouched(path.with_suffix(".ngrams")) & in_list
    positive = spell | acro | flagged_name | treebank | pairs
    based = {word for word in words
             if word not in positive and (base_of(word) or "") in (positive | name)}
    positive |= based

    alphabet = "".join(sorted({c for word in words for c in word if c.isalpha()}))
    ranked = sorted(range(len(rows)), key=lambda i: -rows[i][1])
    rank_of = {rows[i][0]: place for place, i in enumerate(ranked)}

    # A neighbour only condemns a row when something vouches for the neighbour: a rare string
    # one edit from another rare string is two accidents, not a typo of anything.
    high_frequency_valid = {word for word in words
                            if word in positive and zipf[word] >= SHORT_NOISE_ZIPF}
    typo: dict[str, tuple[str, float]] = {}
    for word, _, _ in rows:
        if word in positive or len(word) < 3:
            continue
        gap = next((g for lo, hi, g in TYPO_GAPS if lo <= len(word) <= hi), None)
        if gap is None:
            continue
        best, best_zipf = "", 0.0
        for other in neighbours(word, alphabet):
            if other in high_frequency_valid and zipf[other] > best_zipf:
                best, best_zipf = other, zipf[other]
        if best and best_zipf >= zipf[word] + gap:
            typo[word] = (best, best_zipf - zipf[word])

    foreign: dict[str, tuple[str, float]] = {}
    if zipf_frequency is not None:
        others = [other for other in ("en", "ro", "de", "es", "fr", "it")
                  if not tag.lower().startswith(other)]
        for word, _, _ in rows:
            if word in positive:
                continue
            best, best_zipf = "", 0.0
            for other in others:
                score = zipf_frequency(word, other)
                if score > best_zipf:
                    best, best_zipf = other, score
            if best_zipf > FOREIGN_FLOOR and best_zipf > zipf[word] + FOREIGN_MARGIN:
                foreign[word] = (best, best_zipf)

    kept: list[tuple[str, int, bool]] = []
    dropped: dict[str, list[tuple[str, str]]] = {"typo": [], "foreign": [], "short": [], "tail": []}
    for word, frequency, is_name in rows:
        rank = rank_of[word]
        vouched = word in positive or word in name
        if rank < arguments.commonest:
            if word in typo and not vouched:
                dropped["typo"].append((word, f"-> {typo[word][0]} by {typo[word][1]:.1f} zipf"))
            elif word in foreign and word not in positive:
                dropped["foreign"].append((word, f"{foreign[word][0]} knows it at {foreign[word][1]:.1f}"))
            elif (len(word) == SHORT_NOISE_LENGTH and not vouched
                  and zipf[word] < SHORT_NOISE_ZIPF):
                dropped["short"].append((word, f"zipf {zipf[word]:.1f}"))
            else:
                kept.append((word, frequency, is_name))
        elif vouched:
            kept.append((word, frequency, is_name))
        else:
            dropped["tail"].append((word, f"rank {rank:,}, nothing vouches for it"))

    kept_words = {word for word, _, _ in kept}
    removed = sum(len(items) for items in dropped.values())
    print(f"{tag}: {len(rows):,} rows, {removed:,} dropped, {len(kept):,} kept")
    print(f"    positive evidence: {len(spell):,} spelled, {len(name):,} names, "
          f"{len(acro):,} acronyms, {len(flagged_name):,} already flagged, "
          f"{len(treebank):,} in the treebank, {len(pairs):,} by pairs, {len(based):,} by base")
    for reason, items in dropped.items():
        if not items:
            continue
        print(f"    {reason}: {len(items):,}")
        for word, why in items[:arguments.sample]:
            print(f"        {word:<20} {why}")

    lost_guards = [word for word in GUARD_WORDS.get(tag, ()) if word in in_list and word not in kept_words]
    if lost_guards:
        print(f"    GUARD WORDS DROPPED: {', '.join(lost_guards)} -- nothing written")

    if arguments.coverage is not None:
        held_out = read_coverage_list(arguments.coverage)
        for top in COVERAGE_TOPS:
            sample = held_out[:top]
            before = sum(1 for word in sample if word in in_list)
            after = sum(1 for word in sample if word in kept_words)
            print(f"    coverage of the {top:,} commonest held-out words: "
                  f"{before / len(sample):.1%} before, {after / len(sample):.1%} after")

    if arguments.review is not None:
        arguments.review.mkdir(parents=True, exist_ok=True)
        review = arguments.review / f"{tag}.dropped.tsv"
        with review.open("w", encoding="utf-8") as handle:
            for reason, items in dropped.items():
                for word, why in items:
                    handle.write(f"{word}\t{reason}\t{why}\n")
        print(f"    review: {review}")

    if arguments.apply and not lost_guards:
        path.write_text(
            "".join(f"{w}\t{f}\tname\n" if n else f"{w}\t{f}\n" for w, f, n in kept),
            encoding="utf-8",
        )
        print(f"    written: {path}")
    print()
    return not lost_guards


if __name__ == "__main__":
    raise SystemExit(main())
