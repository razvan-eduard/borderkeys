#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Folds Romanian spellings that differ only in how a diacritic was encoded.

**Cedilla, "ş" and "ţ" (U+015F, U+0163).** Normalised to the comma-below letters (U+0219,
U+021B) whether or not the correct spelling already exists, unless the word still holds a letter
Romanian does not use, or is a name whose comma-below twin is not in the list.

**A-tilde, "ã" (U+00E3).** Folded to "ă" only when the properly spelled word is already in the
dictionary; its count joins that row. A word degraded more than one way is left alone.

Nothing else is touched.

    python3 tools/normalise_diacritics.py dictionaries/ro_RO.tsv
"""

import argparse
import sys

CEDILLA = {"ş": "ș", "ţ": "ț", "Ş": "Ș", "Ţ": "Ț"}
TILDE = {"ã": "ă", "Ã": "Ă"}

# The three spellings this script knows how to read. Excused from the foreign test below, so
# that a word degraded in two ways at once is still recognised as the Romanian word it is.
SUBSTITUTIONS = set(CEDILLA) | set(TILDE)

# The letters a Romanian word may be built from. A word that still holds something else after
# the cedilla has been folded is not Romanian, and its cedilla is its own business.
ROMANIAN = set("abcdefghijklmnopqrstuvwxyzăâîșț" "ABCDEFGHIJKLMNOPQRSTUVWXYZĂÂÎȘȚ" "-'.")


def translate(word, table):
    return "".join(table.get(c, c) for c in word)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("dictionary")
    parser.add_argument("--dry-run", action="store_true",
                        help="report what would change and write nothing")
    args = parser.parse_args()

    lines = []
    with open(args.dictionary, encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 2:
                lines.append((None, line.rstrip("\n")))
                continue
            try:
                count = int(parts[1])
            except ValueError:
                lines.append((None, line.rstrip("\n")))
                continue
            lines.append(((parts[0], count, parts[2] if len(parts) > 2 else None), None))

    known = {entry[0] for entry, _ in lines if entry is not None}

    merged = renamed = kept_foreign = kept_no_twin = 0
    recovered = 0
    # Indexed by word, so a merge adds to the surviving row in place and the file keeps its order.
    position = {}
    for index, (entry, _) in enumerate(lines):
        if entry is not None:
            position.setdefault(entry[0], index)

    for index, (entry, raw) in enumerate(lines):
        if entry is None:
            continue
        word, count, flag = entry

        # Foreignness is decided on the word as written, with the substitution characters
        # excused: a letter that is neither Romanian nor one of them marks a word foreign.
        if not any(ch in word for ch in SUBSTITUTIONS):
            continue
        if not set(word) - SUBSTITUTIONS <= ROMANIAN:
            kept_foreign += 1
            continue
        folded = translate(word, CEDILLA)
        # A name's cedilla is folded only when its comma-below twin is already known.
        if folded != word and flag == "name" and folded not in known:
            kept_foreign += 1
            folded = word
        # The tilde needs positive evidence too; an ordinary word's cedilla does not.
        tilded = translate(folded, TILDE)
        if tilded != folded:
            if tilded in known:
                folded = tilded
            else:
                kept_no_twin += 1

        if folded == word:
            continue

        target = position.get(folded)
        if target is not None and target != index:
            other, otherCount, otherFlag = lines[target][0]
            lines[target] = ((other, otherCount + count, otherFlag), None)
            lines[index] = (None, None)  # dropped
            merged += 1
            recovered += count
        else:
            lines[index] = ((folded, count, flag), None)
            position[folded] = index
            known.add(folded)
            renamed += 1

    print(f"  merged into the correct spelling : {merged}", file=sys.stderr)
    print(f"  respelled in place (no twin)     : {renamed}", file=sys.stderr)
    print(f"  left alone, foreign word         : {kept_foreign}", file=sys.stderr)
    print(f"  left alone, tilde with no twin   : {kept_no_twin}", file=sys.stderr)
    print(f"  occurrences returned to the right spelling: {recovered:,}", file=sys.stderr)
    if args.dry_run:
        return

    with open(args.dictionary, "w", encoding="utf-8") as handle:
        for entry, raw in lines:
            if entry is None:
                if raw is not None:
                    handle.write(raw + "\n")
                continue
            word, count, flag = entry
            handle.write(f"{word}\t{count}\t{flag}\n" if flag else f"{word}\t{count}\n")


if __name__ == "__main__":
    main()
