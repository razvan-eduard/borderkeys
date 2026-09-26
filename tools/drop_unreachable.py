#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Removes corpus rows that are not words of the language the list belongs to.

Two rules, both about what the keyboard can actually produce.

**A word must be reachable by typing.** The corpus tokeniser admits any script -- its character
class is "any Unicode letter-ish" -- so a body of text that quotes Greek, Devanagari or Thai
turns those into vocabulary, and unit superscripts, ordinals and mojibake come with them. A word
is kept when every one of its characters folds into the language's own alphabet: a-z, the letters
that language's long-press overlay offers, and the three joiners that live inside words.

Folded, not literal: "naïve" is an English word and no English overlay carries "ï", but it folds
to "i" and is typed that way. The same keeps "Bjørn", "François" and every other name spelled
with a letter this keyboard reaches through folding. What it drops is a character that folds to
itself and is not on the overlay -- Greek alpha, Devanagari kha, a superscript two.

**A bare letter must be a word.** One letter is a complete match for the tokeniser, so every
letter that ever stood alone in the corpus is an entry: initials, list markers, table cells,
spaced-out text. A handful are real words and the rest are debris, and nothing separates them
automatically -- Hunspell lists letters as headwords for spell-checking, and frequency puts
Romanian "i" and "m" a factor of two apart. So the real ones are named here, per language.

    python3 tools/drop_unreachable.py dictionaries/ro_RO.tsv [--dry-run]
"""

import argparse
import importlib.util
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Word-internal characters that are not letters. The same three tools/make_pack.py's own
# tokeniser admits between letters, and leaving them out of the alphabet deletes every
# contraction and hyphenation the corpus holds -- "it's", "c'est", "într-o".
JOINERS = "'’-"

# Single letters that are complete words, lower-cased because make_pack.py lower-cases every
# token it counts. Declared rather than derived: the automatic sources disagree with the
# languages -- Hunspell offers "k", "l" and "x" for English and omits Romanian "a" -- and the
# frequencies of the real ones and the debris overlap.
SINGLE_LETTER_WORDS = {
    "en_US": "aio",      # a; I; O, the vocative
    "ro_RO": "aeo",      # a, the infinitive marker; e, informal "este"; o, the feminine article
    "de_DE": "",         # none in everyday German
    "es_ES": "aeouy",    # a; e and y; o and u
    "fr_FR": "ayàô",     # a, from avoir; y; à; ô
    "it_IT": "aeioè",    # a; e; i, the masculine plural article; o; è, from essere
}

# Which overlay file names the extra letters of each language. A tag with no file -- en_US --
# has no long-press letters of its own and gets a-z alone.
OVERLAY = {
    "ro_RO": "ro-RO", "de_DE": "de-DE", "es_ES": "es-ES",
    "fr_FR": "fr-FR", "it_IT": "it-IT",
}


def load_build_dict():
    """tools/build_dict.py, for the one fold table this project has."""
    spec = importlib.util.spec_from_file_location("build_dict", ROOT / "tools/build_dict.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def alphabet_of(tag, fold_code_point):
    """Every folded code point a word of [tag] may be built from.

    Read from the keyboard's own accent overlay rather than declared again here: what the
    keyboard offers is what the dictionary may hold, and two lists of the same letters drift.
    """
    letters = set("abcdefghijklmnopqrstuvwxyz") | set(JOINERS)
    name = OVERLAY.get(tag)
    if name is not None:
        overlay = json.loads(
            (ROOT / f"keyboard/src/main/assets/accents/{name}.json").read_text(encoding="utf-8"),
        )
        for offered in overlay["keys"].values():
            letters |= set(offered)
    return {fold_code_point(ord(character)) for character in letters}


# The letters each language admits beyond a-z, checked by --selftest.
#
# Only a letter that folds to *itself* can be here, and only those decide anything: "ț" folds to
# "t" and "é" to "e", so taking either off an overlay changes nothing -- the words stay reachable
# by typing the bare letter. "ß", "æ" and "œ" have no such twin, so their place on an overlay is
# the only reason "Straße" and "cœur" are words. An overlay is a decision about long-press
# ergonomics; for those three it is also a decision about vocabulary, and trimming one would
# delete words with nothing to say so.
EXPECTED_BEYOND_ASCII = {
    "en_US": [],
    "ro_RO": [],
    "de_DE": [0x00DF],            # ß
    "es_ES": [],
    "fr_FR": [0x00E6, 0x0153],    # æ, œ
    "it_IT": [],
}


def load_manifests() -> None:
    """The downloadable languages, described in tools/languages/<tag>.json, join the tables."""
    for manifest in sorted((ROOT / "tools" / "languages").glob("*.json")):
        language = json.loads(manifest.read_text(encoding="utf-8"))
        tag = language["tag"].replace("-", "_")
        SINGLE_LETTER_WORDS.setdefault(tag, language.get("single_letter_words", ""))
        OVERLAY.setdefault(tag, language["tag"])
        EXPECTED_BEYOND_ASCII.setdefault(tag, list(language.get("expected_beyond_ascii", [])))


load_manifests()


def selftest():
    """Every declared language still admits exactly the letters it was measured against."""
    build_dict = load_build_dict()
    for tag, expected in EXPECTED_BEYOND_ASCII.items():
        alphabet = alphabet_of(tag, build_dict.fold_code_point)
        joiners = {build_dict.fold_code_point(ord(c)) for c in JOINERS}
        beyond = sorted(c for c in alphabet if c > 0x7F and c not in joiners)
        if beyond != expected:
            raise SystemExit(
                f"{tag}: admits {[chr(c) for c in beyond]} beyond ASCII, expected "
                f"{[chr(c) for c in expected]} -- an accent overlay changed, and with it which "
                f"words this language may hold",
            )
        for letter in SINGLE_LETTER_WORDS[tag]:
            if build_dict.fold_code_point(ord(letter)) not in alphabet:
                raise SystemExit(f"{tag}: {letter!r} is a word here but not in its own alphabet")
    print(f"selftest ok: {len(EXPECTED_BEYOND_ASCII)} alphabets, "
          f"{sum(len(w) for w in SINGLE_LETTER_WORDS.values())} single-letter words")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("dictionary", nargs="?")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--selftest", action="store_true",
                        help="check every declared alphabet against what the overlays now say")
    arguments = parser.parse_args()

    if arguments.selftest:
        selftest()
        return
    if arguments.dictionary is None:
        parser.error("a dictionary is required unless --selftest is given")

    path = Path(arguments.dictionary)
    tag = path.stem
    if tag not in SINGLE_LETTER_WORDS:
        raise SystemExit(f"{tag}: no alphabet is declared for this language")

    build_dict = load_build_dict()
    allowed = alphabet_of(tag, build_dict.fold_code_point)
    words = set(SINGLE_LETTER_WORDS[tag])

    kept = []
    unreachable = []
    bare = []
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            row = line.rstrip("\n")
            parts = row.split("\t")
            if len(parts) < 2:
                kept.append(row)
                continue
            try:
                count = int(parts[1])
            except ValueError:
                kept.append(row)
                continue
            word = parts[0]
            if any(folded not in allowed for folded in build_dict.fold_word(word)):
                unreachable.append((word, count))
                continue
            if len(word) == 1 and word.lower() not in words:
                bare.append((word, count))
                continue
            kept.append(row)

    def report(title, rows):
        rows.sort(key=lambda row: -row[1])
        print(f"  {title:34}: {len(rows)} ({sum(c for _, c in rows):,} occurrences)",
              file=sys.stderr)
        for word, count in rows[:8]:
            print(f"      {word} ({count:,})", file=sys.stderr)

    print(f"{tag}: keeping single letters {sorted(words) or '(none)'}", file=sys.stderr)
    report("no keystroke produces it", unreachable)
    report("a bare letter, not a word", bare)
    print(f"  kept                              : {len(kept):,} rows", file=sys.stderr)

    if arguments.dry_run:
        return
    with path.open("w", encoding="utf-8") as handle:
        for row in kept:
            handle.write(row + "\n")


if __name__ == "__main__":
    main()
