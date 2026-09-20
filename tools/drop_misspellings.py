#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Removes a corpus's own misspellings from a word list.

The packs are built by counting real text, which is what gives them every inflection anyone
actually writes without needing a lemma list. It also gives them every misspelling anyone
actually writes. `teh`, `recieve`, `seperate` and `definately` are all words of our English
dictionary, and a word in the dictionary is a word the keyboard defends: typing `recieve` offers
`recieve` first and `receive` second, because an exact match is not something a correction is
allowed to outrank.

Why not simply ask the spell checker about everything
-----------------------------------------------------
Measured, that removes 56,476 of 114,252 English rows -- 49.4%. Hunspell holds `American`,
`Friday`, `April`, `British` and `York` capitalised while these lists are lower-cased, so half
the vocabulary comes back rejected. A blanket gate is not usable and this is not one.

Why this does not delete anything on its own
--------------------------------------------
It was written to, and the measurements said no. The rule is: rare, rejected by the spell
checker in every case form, and one edit from something much commoner that the spell checker
accepts. That finds `teh`, `recieve`, `seperate` and `adn`. It also finds, at every threshold
tried:

  ratio 10    collectables, hypothesised, practises   British spellings
              impedances, recognitions                valid plurals
  ratio 100   starks, handley, mathers, keyes, vick   surnames
  ratio 1000  fong, leong, yue, zain, houser, eason   surnames
              publix, neem                            a brand, a plant

Tightening does not clear them out. A surname the names pipeline missed is *exactly* the same
shape as a typo -- a rare string one edit from a common word that no spell checker knows -- and
no threshold separates `teh` from `fong`, because there is nothing in the data that differs.
The treebank cannot arbitrate either: it is built from the same corpus and holds the typos as
words, tagging `teh` as a determiner and `recieve` as a verb, while `theo` is absent from it
altogether. It would keep the errors and delete the name.

So this prints a review, and removal takes a list somebody has read. That is also what the one
comparable implementation does, arrived at here independently and only after trying the
automatic version and measuring what it would have deleted.

Usage
-----
  tools/drop_misspellings.py --hunspell en_US=/path/to/en_US --report out.tsv
  tools/drop_misspellings.py --hunspell en_US=/path/to/en_US --remove-list reviewed.txt
"""

from __future__ import annotations

import argparse
import math
import subprocess
import sys
from pathlib import Path

BUNDLED = ("en_US", "ro_RO", "de_DE", "es_ES", "fr_FR", "it_IT")

# Below this a word has too few letters for "one edit away" to mean anything: at two characters
# almost every other two-character word is one edit away, and the ratio test alone would start
# rewriting real words. Three keeps `teh`, which is the case this was written for.
DEFAULT_MIN_LENGTH = 3

# How much commoner the correction has to be. Ten is deliberately wide: a genuine misspelling is
# orders of magnitude rarer than the word it fails to spell (`teh` against `the` is far past
# this), while two real words one edit apart -- `their`/`there`, `form`/`from` -- sit close
# enough that neither can delete the other.
DEFAULT_RATIO = 10.0

# How common a word may be before it stops looking like a typo, in Zipf (log10 of occurrences per
# billion tokens). A misspelling that reached a corpus at all is rare in absolute terms, not
# merely rare next to the word it fails to spell -- and that distinction is the whole difficulty
# here, because "the" is so common that every three-letter string one edit from it clears any
# ratio test. `teh` sits at 2.62 and `theo`, a name, at 3.33; without this the rule deletes both.
DEFAULT_MAX_ZIPF = 3.0


def read_list(path: Path) -> tuple[dict[str, int], set[str]]:
    counts: dict[str, int] = {}
    names: set[str] = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        try:
            counts[parts[0]] = int(parts[1])
        except ValueError:
            continue
        if len(parts) >= 3 and parts[2] == "name":
            names.add(parts[0])
    return counts, names


def hunspell_rejects(words: list[str], dictionary: Path) -> set[str]:
    """Which of [words] the spell checker refuses. Empty on any failure, which errs towards
    keeping rows rather than deleting them on a broken dictionary path."""
    if not words:
        return set()
    try:
        result = subprocess.run(
            ["hunspell", "-d", str(dictionary), "-i", "utf-8", "-l"],
            input="\n".join(words) + "\n", capture_output=True, text=True, check=True,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        print(f"hunspell failed: {error}", file=sys.stderr)
        return set()
    return {line.strip() for line in result.stdout.splitlines() if line.strip()}


def rejected_by_all(words: list[str], dictionaries: list[Path]) -> set[str]:
    """The words every one of [dictionaries] disowns, in every case form.

    Several per language, because one is not the language. Asking only en_US calls `collectables`,
    `hypothesised`, `practises`, `organised` and `analysed` errors -- they are British spellings,
    and en_GB holds all of them. A word has to be refused by every dictionary given before it is
    even a candidate, so adding one can only ever shrink the list, never grow it.
    """
    refused = None
    for dictionary in dictionaries:
        here = rejected_in_every_case(words, dictionary)
        refused = here if refused is None else (refused & here)
        if not refused:
            break
    return refused or set()


def rejected_in_every_case(words: list[str], dictionary: Path) -> set[str]:
    """The words the language disowns however they are capitalised.

    Asking only about the lower-case form is what made a blanket gate unusable: `american` and
    `friday` are refused in that form and accepted capitalised, because that is how the
    dictionary stores them. A word is only a candidate here if no case form is accepted.
    """
    forms: list[str] = []
    for word in words:
        forms.append(word)
        if word[:1].islower():
            forms.append(word.capitalize())
            forms.append(word.upper())
    rejected = hunspell_rejects(forms, dictionary)
    out = set()
    for word in words:
        if word not in rejected:
            continue
        if word[:1].islower() and (word.capitalize() not in rejected or
                                   word.upper() not in rejected):
            continue
        out.add(word)
    return out


def neighbours(word: str, alphabet: str) -> set[str]:
    """Every spelling one edit from [word]: a letter dropped, added, swapped or transposed."""
    found = set()
    for i in range(len(word)):
        found.add(word[:i] + word[i + 1:])
    for i in range(len(word) - 1):
        found.add(word[:i] + word[i + 1] + word[i] + word[i + 2:])
    for i in range(len(word) + 1):
        for letter in alphabet:
            found.add(word[:i] + letter + word[i:])
    for i in range(len(word)):
        for letter in alphabet:
            if letter != word[i]:
                found.add(word[:i] + letter + word[i + 1:])
    found.discard(word)
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dictionaries", type=Path, default=Path("dictionaries"))
    parser.add_argument("--tag", action="append", default=None)
    parser.add_argument("--hunspell", action="append", default=[], metavar="TAG=PATH",
                        help="a spell checker for a language, e.g. en_US=/path/to/en_US. Repeat "
                             "the same tag to add more: a word must be refused by all of them. "
                             "Give en_GB beside en_US or every British spelling reads as an "
                             "error.")
    parser.add_argument("--min-length", type=int, default=DEFAULT_MIN_LENGTH)
    parser.add_argument("--ratio", type=float, default=DEFAULT_RATIO)
    parser.add_argument("--max-zipf", type=float, default=DEFAULT_MAX_ZIPF,
                        help=f"only consider words rarer than this, default {DEFAULT_MAX_ZIPF}")
    parser.add_argument("--sample", type=int, default=25)
    parser.add_argument("--report", type=Path, default=None,
                        help="write the full candidate list here, for review")
    parser.add_argument("--remove-list", type=Path, default=None,
                        help="a reviewed file of words to delete, one per line. Only words in "
                             "BOTH this file and the detector's own candidates are removed.")
    arguments = parser.parse_args()

    oracles: dict[str, list[Path]] = {}
    for entry in arguments.hunspell:
        tag, _, path = entry.partition("=")
        if not path:
            print(f"--hunspell wants TAG=PATH, got {entry!r}", file=sys.stderr)
            return 1
        oracles.setdefault(tag, []).append(Path(path))
    if not oracles:
        print("nothing to do: this needs at least one --hunspell TAG=PATH", file=sys.stderr)
        return 1

    targets = arguments.tag or [t for t in BUNDLED if t in oracles]
    for tag in targets:
        path = arguments.dictionaries / f"{tag}.tsv"
        if tag not in oracles or not path.is_file():
            print(f"{tag}: no spell checker or no word list, skipped")
            continue
        counts, names = read_list(path)
        vocabulary = {w: c for w, c in counts.items() if w not in names}

        # Only rows the language disowns in every case form are even looked at.
        total = sum(counts.values()) or 1
        scale = 1e9 / total

        def zipf(word: str) -> float:
            return math.log10(vocabulary[word] * scale)

        candidates = [w for w in vocabulary
                      if len(w) >= arguments.min_length and w.isalpha()
                      and zipf(w) < arguments.max_zipf]
        suspect = rejected_by_all(sorted(candidates), oracles[tag])

        alphabet = "".join(sorted({c for w in vocabulary for c in w if c.isalpha()}))
        # A neighbour only counts if the language vouches for it, so ask once about all of them
        # rather than per word.
        proposals: dict[str, tuple[str, int, int]] = {}
        for word in sorted(suspect):
            best = None
            for other in neighbours(word, alphabet):
                rival = vocabulary.get(other)
                if rival is None or rival < vocabulary[word] * arguments.ratio:
                    continue
                if best is None or rival > best[1]:
                    best = (other, rival)
            if best is not None:
                proposals[word] = (best[0], vocabulary[word], best[1])

        corrections = sorted({c for c, _, _ in proposals.values()})
        refused = rejected_by_all(corrections, oracles[tag])
        removals = {w: v for w, v in proposals.items() if v[0] not in refused}

        share = 100.0 * len(removals) / max(len(vocabulary), 1)
        print(f"{tag}: {len(suspect):,} disowned in every case, {len(proposals):,} with a "
              f"commoner neighbour, {len(removals):,} removed of {len(vocabulary):,} "
              f"({share:.2f}%)")
        for word in sorted(removals, key=lambda w: -removals[w][2])[:arguments.sample]:
            correction, own, rival = removals[word]
            print(f"    {word:<18} -> {correction:<18} {own:>7,} against {rival:>9,}")
        if arguments.report:
            out = arguments.report if len(targets) == 1 else \
                arguments.report.with_suffix(f".{tag}{arguments.report.suffix}")
            rows = sorted(removals.items(), key=lambda kv: -kv[1][2] / kv[1][1])
            out.write_text(
                "# word\tsuggested correction\tits count\tthe correction's count\tratio\n" +
                "".join(f"{w}\t{c}\t{own}\t{rival}\t{rival / own:.0f}\n"
                        for w, (c, own, rival) in rows),
                encoding="utf-8",
            )
            print(f"    review written: {out}  ({len(rows):,} candidates)")

        if arguments.remove_list:
            reviewed = {
                line.split("#", 1)[0].strip()
                for line in arguments.remove_list.read_text(encoding="utf-8").splitlines()
            }
            # The intersection, never the file alone: a reviewed list is a filter on what this
            # found, so a typing slip in it cannot delete a word the detector never suspected.
            agreed = {w for w in removals if w in reviewed}
            missing = sorted(w for w in reviewed if w and w not in removals)
            lines = [line for line in path.read_text(encoding="utf-8").splitlines()
                     if line.split("\t", 1)[0] not in agreed]
            path.write_text("\n".join(lines) + "\n", encoding="utf-8")
            print(f"    removed {len(agreed):,} of the {len(reviewed):,} reviewed: {path}")
            if missing:
                print(f"    not candidates, so left alone: {', '.join(missing[:8])}")
        print()

    if not arguments.remove_list:
        print("nothing removed -- this reports, and --remove-list takes a file somebody has read")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
