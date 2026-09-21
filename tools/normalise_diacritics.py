#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Folds Romanian spellings that differ only in how a diacritic was encoded.

Measured 2026-09-21, while working out why "cana" was committed as "canal": `ro_RO.tsv` spells
8,822 words two or more different ways, splitting 10.2 million occurrences between the copies.
"și" alone carries 1,044,916 under one spelling and 441,688 under another.

The engine never sees the difference -- its fold maps every one of these onto the same plain
letter, so all spellings are *found*. The damage is to frequency and to what gets shown:
build_dict.py keeps one spelling per folded key, so a word can be ranked on a fraction of its
real count, and the spelling that survives can be the wrong one. That is why restoring the
accent on "sanatatii" produced "sănătăţii" rather than "sănătății".

Two classes, and they need different rules, which is the whole reason this is a script rather
than a search and replace.

**Cedilla, "ş" and "ţ" (U+015F, U+0163).** Not Romanian letters. Unicode shipped only the
cedilla forms at first and Romanian used them for years before the comma-below letters (U+0219,
U+021B) were added; most fonts draw the two nearly identically. Any Romanian word spelled with
one is simply spelled wrong, so these are normalised whether or not the correct spelling already
exists -- *unless* the word still contains a letter Romanian does not use after normalising.
That exception is not hypothetical: it keeps Ayşegül, Barış, Gümüş, Güneş, Işık, Çalışkan,
Şentürk and Şükrü intact, where the cedilla is the correct Turkish letter and this script has no
business touching it.

**A-tilde, "ã" (U+00E3).** A different problem wearing the same clothes. It is a real letter in
other languages, and it arrived here because people without a Romanian layout reached for the
nearest key rather than because of any encoding fault -- a bad decode of Latin-2 as Latin-1 would
have produced "º" and "þ" in the same quantity, and there are three of each against 1,412. So
the evidence has to be positive: fold it only when the properly spelled word is *already* in the
dictionary, which both proves it is the same word and is what recovers the split frequency.

That narrower rule is what protects "são" and "joão", which are Portuguese and would otherwise
become "săo" and "joăo". It also declines to half-repair words degraded more than one way:
"învãtãmânt" is missing its "ț" as well, so folding the tilde alone would yield "învătământ",
which is still not "învățământ" and no more useful than what is there.

Nothing else is touched. An audit of every non-Romanian letter in the file found these three at
the top by two orders of magnitude; below them sit á, é, ö, ü, í, ó, š, ä, č, ł and ć, which are
foreign names a Romanian corpus legitimately contains.

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
    # Index by word so a merge can find the surviving row and add to it in place, which keeps
    # the file's existing order -- a frequency-sorted body with the name list appended after it.
    position = {}
    for index, (entry, _) in enumerate(lines):
        if entry is not None:
            position.setdefault(entry[0], index)

    for index, (entry, raw) in enumerate(lines):
        if entry is None:
            continue
        word, count, flag = entry

        # Foreignness is decided on the word as written, and the substitution characters are
        # excused from the test. Checking a half-folded word instead marked "faţã", "viaţã" and
        # "ţarã" as foreign -- Romanian words degraded twice over, whose leftover tilde looked
        # like evidence of another language. What actually marks a word foreign is a letter that
        # is neither Romanian nor one of the three spellings this script knows about.
        if not any(ch in word for ch in SUBSTITUTIONS):
            continue
        if not set(word) - SUBSTITUTIONS <= ROMANIAN:
            kept_foreign += 1
            continue
        folded = translate(word, CEDILLA)
        # A name is where a foreign spelling legitimately lives, and spelling alone cannot tell
        # a Turkish "Ayşe" from a Romanian word: both are ASCII plus a cedilla, so the foreign
        # test above sees nothing to object to. "Ayşegül" is caught only because of its "ü", and
        # "Ayşe" is not caught at all -- which quietly turned 25 Turkish names into Romanian
        # ones. So names need the same positive evidence the tilde does. Measured: 33
        # name-flagged entries carry a cedilla, 32 have no comma-below twin, and every one of
        # those is Turkish (Akbaş, Aktaş, Ateş, Ayşe, Barış, Coşkun, Gümüş, Güneş).
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
