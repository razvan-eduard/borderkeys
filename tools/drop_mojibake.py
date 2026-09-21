#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Repairs or removes corpus entries that are a decoding accident rather than a word.

Handles two shapes.

**A trailing "â"**, which is a typographic apostrophe read as UTF-8-through-Latin-1: U+2019's
bytes E2 80 99 become "a-circumflex, euro, trademark", and tokenising keeps the first. "it's"
counts as "itâ", French "l'" as "lâ", Italian "dell'" as "dellâ". Deleted with their counts --
no word in any shipped language ends in "â", and "itâ" stands for "it'", which is not a word
either.

**A Romanian letter carrying another codepage's glyph**: "ºi" for "și", "faþã" for "față",
"pånă" for "până". Each glyph in LATIN2 maps to every letter it might stand for, and the entry
is merged into whichever reversal the dictionary already holds, most frequent first. A reversal
that lands on nothing leaves the entry untouched, so "Bjørn", "måneskin" and Spanish "nº" keep
their own spelling.

Membership is tested on the canonical form, so the repair finds its target whether or not
tools/normalise_diacritics.py has already run over the file.

    python3 tools/drop_mojibake.py dictionaries/en_US.tsv [--dry-run]
"""

import argparse
import sys

# Latin-1 glyph -> the Romanian letters whose byte it may have borrowed. Several are ambiguous,
# so each maps to every candidate and the dictionary decides between them: a reversal counts
# only when it lands on a spelling already present, and a glyph that lands on nothing is left
# alone.
LATIN2 = {
    "å": ("â", "ă"),
    "º": ("ș",),
    "þ": ("ț",),
    "ø": ("ț",),
    "ã": ("ă", "â"),
    "š": ("ș",),
}

# Letters that may have lost a comma below on the way through the same codepage. Consulted only
# for words that already carry a LATIN2 glyph, so an ordinary "s" or "t" is never a candidate.
STRIPPED = {
    "s": ("s", "ș"),
    "t": ("t", "ț"),
}

# Cedilla-below to comma-below, the same pairs tools/normalise_diacritics.py writes. Applied only
# to the key a lookup is made under, never to a word this tool writes back.
CEDILLA = {"ş": "ș", "ţ": "ț", "Ş": "Ș", "Ţ": "Ț"}


def canonical(word):
    """The key an entry is looked up under: comma-below wherever a cedilla was typed."""
    return "".join(CEDILLA.get(character, character) for character in word)


def reversals(word):
    """Every spelling `word` could be. Bounded: a long word with many candidates yields none."""
    out = [""]
    for character in word:
        options = LATIN2.get(character) or STRIPPED.get(character, (character,))
        out = [prefix + option for prefix in out for option in options]
        if len(out) > 4096:
            return []
    return [candidate for candidate in out if candidate != word]


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("dictionary")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    rows = []
    with open(args.dictionary, encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 2:
                rows.append((None, line.rstrip("\n")))
                continue
            try:
                count = int(parts[1])
            except ValueError:
                rows.append((None, line.rstrip("\n")))
                continue
            rows.append(((parts[0], count, parts[2] if len(parts) > 2 else None), None))

    position = {}
    for index, (entry, _) in enumerate(rows):
        if entry is not None:
            position.setdefault(canonical(entry[0]), index)

    dropped = merged = kept = 0
    dropped_occurrences = merged_occurrences = 0
    examples = []

    for index, (entry, _) in enumerate(rows):
        if entry is None:
            continue
        word, count, flag = entry

        if word.endswith("â") or "â€" in word:
            rows[index] = (None, None)
            dropped += 1
            dropped_occurrences += count
            if len(examples) < 6:
                examples.append(f"{word} ({count}) deleted")
            continue

        if not any(c in word for c in LATIN2):
            continue
        # A name keeps whatever spelling the corpus counted: "Miloš" and "Miloș" are two
        # renderings of one name, not a word and its corruption, and merging them loses one.
        if flag == "name":
            kept += 1
            continue
        # The most frequent spelling this could be, among those the dictionary already holds.
        # Frequency breaks the tie because the alternatives are the same word under different
        # accents, and the common one is the word the corpus was actually counting.
        candidates = [c for c in reversals(word)
                      if position.get(canonical(c)) is not None
                      and rows[position[canonical(c)]][0] is not None]
        if not candidates:
            kept += 1
            continue
        repaired = max(candidates, key=lambda c: rows[position[canonical(c)]][0][1])
        target = position.get(canonical(repaired))
        if target is not None and target != index and rows[target][0] is not None:
            other, otherCount, otherFlag = rows[target][0]
            rows[target] = ((other, otherCount + count, otherFlag), None)
            rows[index] = (None, None)
            merged += 1
            merged_occurrences += count
            if len(examples) < 6:
                examples.append(f"{word} ({count}) -> {other}")

    print(f"  deleted, a truncated apostrophe : {dropped} ({dropped_occurrences:,} occurrences)",
          file=sys.stderr)
    print(f"  repaired onto a known spelling  : {merged} ({merged_occurrences:,} occurrences)",
          file=sys.stderr)
    print(f"  left alone, a word in its own right: {kept}", file=sys.stderr)
    for line in examples:
        print(f"      {line}", file=sys.stderr)
    if args.dry_run:
        return

    with open(args.dictionary, "w", encoding="utf-8") as handle:
        for entry, raw in rows:
            if entry is None:
                if raw is not None:
                    handle.write(raw + "\n")
                continue
            word, count, flag = entry
            handle.write(f"{word}\t{count}\t{flag}\n" if flag else f"{word}\t{count}\n")


if __name__ == "__main__":
    main()
