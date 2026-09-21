#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Repairs or removes corpus entries that are a decoding accident rather than a word.

Two accidents, both from text decoded with the wrong codepage before it was ever counted.

**A typographic apostrophe read as UTF-8-through-Latin-1.** U+2019 encodes as the three bytes
E2 80 99, which read as Latin-1 come out as "a-circumflex, euro, trademark". Tokenising then
keeps the first of those and drops the rest, so "it's" is counted as "itâ", "don't" as "donâ",
French "l'" as "lâ" and Italian "dell'" as "dellâ".

No word in any language shipped here ends in "â". French uses it only inside a word (bâtiment)
and Romanian orthography puts "î" at a word's edges and "â" only in the middle, so a trailing
one cannot be anything else. Those entries are deleted rather than repaired: "itâ" stands for
"it'", which is not a word either, and guessing whether the corpus meant "it's" or "it" would be
inventing data. Their counts are dropped with them.

**Romanian letters decoded through the wrong codepage.** "și" was counted as "ºi", "față" as
"faþã", "până" as "pånă" and "când" as "cånd". Some of it is Latin-2 read as Latin-1, where "ă"
(0xE3) comes out as "ã" and "ţ" (0xFE) as "þ"; "å" for "â" does not fit that mapping and comes
from some other legacy encoding in the corpus's history.

Which one is not worth pinning down, and deliberately is not: the reversal below lists every
letter a glyph might stand for and lets the dictionary choose between them. Assuming a codepage
instead is what got "å" wrong the first time -- taken for "ă", it turns "pånă" into "pănă"
rather than "până", and the dictionary happened to hold that misspelling too, so the wrong
answer looked like confirmation.

These are repaired rather than deleted, but only on positive evidence: the reversal has to land
on a spelling the dictionary already holds. That requirement is the whole safety of it, because
the same characters are ordinary letters elsewhere. "Bjørn", "Bård", "Bø" and "Asbjørn" are
Norwegian names; "måneskin" is a band; "º" and "nº" are the masculine ordinal Spanish and
Italian write "nº 5" with. None of them reverses onto a known word, so none of them is touched.

    python3 tools/drop_mojibake.py dictionaries/en_US.tsv [--dry-run]
"""

import argparse
import sys

# Latin-1 glyph -> the Romanian letters whose byte it may have borrowed. Several are ambiguous,
# so each maps to every candidate and the dictionary decides between them: a reversal counts
# only when it lands on a spelling already present, and a glyph that lands on nothing is left
# alone. Guessing the source codepage instead got this wrong -- "å" was taken for "ă", which
# turns "pånă" into "pănă" rather than "până", and the dictionary happened to hold that
# misspelling too, so the wrong answer looked like evidence.
LATIN2 = {
    "å": ("â", "ă"),
    "º": ("ș",),
    "þ": ("ț",),
    "ø": ("ț",),
    "ã": ("ă", "â"),
}


def reversals(word):
    """Every spelling `word` could be, cheapest first. Bounded: few glyphs, few candidates."""
    out = [""]
    for character in word:
        options = LATIN2.get(character, (character,))
        out = [prefix + option for prefix in out for option in options]
        if len(out) > 64:
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

    known = {entry[0] for entry, _ in rows if entry is not None}
    position = {}
    for index, (entry, _) in enumerate(rows):
        if entry is not None:
            position.setdefault(entry[0], index)

    dropped = merged = kept = 0
    dropped_occurrences = merged_occurrences = 0
    examples = []

    for index, (entry, _) in enumerate(rows):
        if entry is None:
            continue
        word, count, _flag = entry

        if word.endswith("â") or "â€" in word:
            rows[index] = (None, None)
            dropped += 1
            dropped_occurrences += count
            if len(examples) < 6:
                examples.append(f"{word} ({count}) deleted")
            continue

        if not any(c in word for c in LATIN2):
            continue
        # The most frequent spelling this could be, among those the dictionary already holds.
        # Frequency breaks the tie because the alternatives are the same word under different
        # accents, and the common one is the word the corpus was actually counting.
        candidates = [c for c in reversals(word)
                      if position.get(c) is not None and rows[position[c]][0] is not None]
        if not candidates:
            kept += 1
            continue
        repaired = max(candidates, key=lambda c: rows[position[c]][0][1])
        target = position.get(repaired)
        if target is not None and target != index and rows[target][0] is not None:
            other, otherCount, otherFlag = rows[target][0]
            rows[target] = ((other, otherCount + count, otherFlag), None)
            rows[index] = (None, None)
            merged += 1
            merged_occurrences += count
            if len(examples) < 6:
                examples.append(f"{word} ({count}) -> {repaired}")

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
