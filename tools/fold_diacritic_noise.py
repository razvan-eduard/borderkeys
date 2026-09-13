#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Folds diacritic-dropped corpus noise into its accented counterpart.

A real, measured problem found 2026-09-13: a corpus scraped from real-world Romanian text
carries plenty of writing that dropped diacritics entirely -- not a different word, the same
word missing its accent -- and build_dict.py counts each spelling as its own word, with its own
frequency. For a word already spelled without diacritics (fold_word does not strip them; see
bkd_format.hpp's own note on why "s" and "s-with-comma-below" are different fold keys on
purpose), the no-diacritic spelling and its accented sibling end up as two unrelated dictionary
entries, and the disused-looking one keeps AutoCorrection.correctionFor's "already a known word"
guard from ever proposing the accent back -- e.g. "citeste" (4,455) outranked "citește" (559)
outright, so typing "citeste" was never corrected to "citește", not because the engine is broken
but because the corpus made "citeste" look like a legitimately common word in its own right.

Not every such pair is noise, though: Romanian's own grammar produces the identical surface
pattern legitimately all the time -- "casa"/"casă" (definite/indefinite noun), "verifica"/
"verifică" (infinitive/conjugated verb), "peste"/"pește" (unrelated homographs). Guessing from
spelling alone which pairs are noise and which are two real words is exactly the kind of judgment
call that should not be automated with a heuristic; this script instead cross-checks the
no-diacritic spelling against a real spellchecker wordlist (e.g. a Hunspell dictionary's own
`unmunch` expansion) and only folds a pair when the no-diacritic form is not, itself, a word that
dictionary recognises. Confirmed against LibreOffice's ro_RO Hunspell dictionary 2026-09-13: 697
of 1,933 candidate pairs were genuine noise (the rest were real words left alone).

The reference wordlist is never bundled or fetched automatically -- same reasoning as
make_names.py's own Wikidata dependency: a decision to cross-check against someone else's
licensed data is the maintainer's to make when they run this, not a build-time dependency
everyone else's checkout suddenly needs.

Usage
-----
    unmunch ro_RO.dic ro_RO.aff > ro_words_full.txt
    ./fold_diacritic_noise.py --words dictionaries/ro_RO.tsv --reference ro_words_full.txt \
        --out dictionaries/ro_RO.tsv
"""

from __future__ import annotations

import argparse
import sys

FOLD_TABLE = str.maketrans("ăâîșşțţ", "aaisstt")


def strip_diacritics(word: str) -> str:
    return word.translate(FOLD_TABLE)


def read_rows(path: str) -> list[tuple[str, int, bool]]:
    rows = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) not in (2, 3):
                raise SystemExit(f"{path}: expected 'word<TAB>frequency[<TAB>name]', got {line!r}")
            if len(parts) == 3 and parts[2] != "name":
                raise SystemExit(f"{path}: third column must be 'name', got {parts[2]!r}")
            rows.append((parts[0], int(parts[1]), len(parts) == 3))
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--words", required=True, help="a dictionaries/*.tsv word list")
    parser.add_argument("--reference", required=True,
                        help="a real wordlist (one word per line) to check a no-diacritic "
                             "spelling against, e.g. a Hunspell dictionary's own unmunch output")
    parser.add_argument("--out", required=True)
    arguments = parser.parse_args()

    reference: set[str] = set()
    with open(arguments.reference, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            word = line.strip()
            if word:
                reference.add(word.lower())
    print(f"reference wordlist: {len(reference)} words", file=sys.stderr)

    rows = read_rows(arguments.words)
    by_word = {word: (freq, is_name) for word, freq, is_name in rows}

    # Every accented sibling a no-diacritic spelling could fold into, keyed by that spelling --
    # more than one when the corpus also carries an encoding-variant diacritic (ş vs ș), which
    # build_dict.py's own fold-and-merge already reconciles at compile time; this only needs to
    # pick the current highest-frequency one to add the noise into, not settle that question.
    siblings: dict[str, list[str]] = {}
    for word, _freq, _is_name in rows:
        stripped = strip_diacritics(word)
        if stripped != word:
            siblings.setdefault(stripped, []).append(word)

    folded_frequency: dict[str, int] = {}
    folded_name: dict[str, bool] = {}
    dropped: set[str] = set()
    kept_as_real_word = 0

    for word, freq, is_name in rows:
        if strip_diacritics(word) != word:
            continue  # has its own diacritics; not a candidate to be folded away
        if is_name:
            continue  # a general-vocabulary wordlist has no opinion on proper nouns; a name
            # absent from Hunspell is not evidence of anything (confirmed 2026-09-13: folding
            # without this guard cost real name variants -- "Benoit" into "Benoît", "Lazar" into
            # "Lazăr", "Salim" into "Salîm" -- three different real spellings, not noise)
        candidates = siblings.get(word)
        if not candidates:
            continue  # no accented sibling in this corpus at all
        if word.lower() in reference:
            kept_as_real_word += 1
            continue  # a real word in its own right -- leave it alone
        target = max(candidates, key=lambda w: by_word[w][0])
        folded_frequency[target] = folded_frequency.get(target, 0) + freq
        folded_name[target] = folded_name.get(target, False) or is_name
        dropped.add(word)

    written = 0
    with open(arguments.out, "w", encoding="utf-8") as handle:
        for word, freq, is_name in rows:
            if word in dropped:
                continue
            freq += folded_frequency.get(word, 0)
            is_name = is_name or folded_name.get(word, False)
            handle.write(f"{word}\t{freq}\tname\n" if is_name else f"{word}\t{freq}\n")
            written += 1

    print(f"folded {len(dropped)} no-diacritic noise entries into their accented sibling "
          f"({kept_as_real_word} candidate pairs left alone as real words); "
          f"wrote {written} words to {arguments.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
