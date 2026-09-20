#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Marks the proper nouns already sitting in a pack's ordinary vocabulary.

`make_names.py` asks Wikidata for given names and family names. Persons, and only persons, so
companies, places and brands were never candidates -- `paribas`, `softbank`, `ubisoft`,
`fuerteventura` and thousands more sit in the vocabulary rows of every pack with no flag on
them. A whole further class is outside Wikidata's model altogether: `friday`, `april`,
`american`, `british`, `maoist` and `newtonian` are capitalised in English and are not proper
*names*, so no widening of that query would ever reach them.

An unflagged name costs three things. It is never auto-capitalised. It is not covered by the
proper-noun guard in `AutoCorrection.correctionFor`, so a surname can be corrected into
something else. And it is indistinguishable from a misspelling to any dictionary-cleaning rule,
which is why `drop_misspellings.py` had to be made review-only.

The rule
--------
**A word its own spell checker refuses in lower case and accepts capitalised is a name.**

That is the exact inverse of what `make_ordinary.py` already asks, and it inherits each
language's conventions for free: English flags `friday` and `april`, Romanian does not, because
Romanian does not capitalise them. Nothing here encodes a rule about weekdays.

The obvious alternative is wrong and was measured to be. The treebank tags `president`,
`states`, `united` and `court` as proper nouns, because they occur inside multi-word names, and
Italian's `del`, `dal` and `nello` likewise; flagging on that tag would be worse than the gap.
The spell checker refuses all of them for the right reason -- they are ordinary lower-case words
of the language.

What it cannot reach is a name no dictionary holds in any case, `softbank` and `bytedance` among
them. That is the Wikidata query's job, not this one's.

Review
------
`dictionaries/<tag>.names-exclude` is read and honoured, and is where a wrong flag goes. It
already holds `south`, `north`, `court`, `park`, `bank` and `king` for this exact purpose, from
the Wikidata side of the same problem.

Usage
-----
  tools/flag_names.py --hunspell en_US=/path/en_US --hunspell en_US=/path/en_GB
  tools/flag_names.py --hunspell en_US=/path/en_US --apply
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

BUNDLED = ("en_US", "ro_RO", "de_DE", "es_ES", "fr_FR", "it_IT")

# Below this there is not enough word to judge. Two-letter tokens are overwhelmingly
# abbreviations and particles, and a spell checker's verdict on them says more about its own
# abbreviation list than about the language.
MIN_LENGTH = 3

# Languages this rule cannot be used on, and why.
#
# German capitalises every noun, so its spell checker refuses "haus", "jahr", "stadt", "kind" and
# "arbeit" in lower case and accepts all of them capitalised -- exactly the signature of a name.
# The asymmetry the rule reads carries no information there, and running it would flag the entire
# German noun vocabulary. Measured, not assumed.
#
# German's own treebank does separate the two (NE against NN, so "berlin" against "haus"), which
# is the opposite of English where the treebank is the unreliable witness and the spell checker
# the good one. That is a different tool.
CAPITALISES_EVERY_NOUN = {"de_DE"}


def read_rows(path: Path) -> list[tuple[str, str, bool]]:
    """Every line as (word, raw line, is already a name), order preserved."""
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            rows.append(("", line, False))
            continue
        rows.append((parts[0], line, len(parts) >= 3 and parts[2] == "name"))
    return rows


def refused(words: list[str], dictionary: Path) -> set[str]:
    """What this dictionary will not accept. Empty on failure, which flags nothing rather than
    flagging everything."""
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


def looks_like_a_name(words: list[str], dictionaries: list[Path]) -> set[str]:
    """Refused in lower case by every dictionary, accepted capitalised by at least one.

    Both halves are needed and they are asymmetric on purpose. *Every* dictionary has to refuse
    the lower-case form, so a word that is ordinary in British but not American English is not
    called a name. *One* accepting the capitalised form is enough, because a name known to any
    of them is a name.
    """
    refused_lower = None
    accepted_capital: set[str] = set()
    capitals = [word.capitalize() for word in words]
    for dictionary in dictionaries:
        here = refused(words, dictionary)
        refused_lower = here if refused_lower is None else (refused_lower & here)
        rejected_capitals = refused(capitals, dictionary)
        accepted_capital |= {w for w in words if w.capitalize() not in rejected_capitals}
    if not refused_lower:
        return set()
    return {w for w in words if w in refused_lower and w in accepted_capital}


def read_exclusions(path: Path) -> set[str]:
    if not path.is_file():
        return set()
    words = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        word = line.split("#", 1)[0].strip().lower()
        if word:
            words.add(word)
    return words


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dictionaries", type=Path, default=Path("dictionaries"))
    parser.add_argument("--tag", action="append", default=None)
    parser.add_argument("--hunspell", action="append", default=[], metavar="TAG=PATH",
                        help="a spell checker for a language; repeat the tag to add more. Every "
                             "one must refuse the lower-case form before a word is a candidate.")
    parser.add_argument("--sample", type=int, default=30)
    parser.add_argument("--report", type=Path, default=None,
                        help="write the full list here, most frequent first, for review")
    parser.add_argument("--apply", action="store_true")
    arguments = parser.parse_args()

    oracles: dict[str, list[Path]] = {}
    for entry in arguments.hunspell:
        tag, _, path = entry.partition("=")
        if not path:
            print(f"--hunspell wants TAG=PATH, got {entry!r}", file=sys.stderr)
            return 1
        oracles.setdefault(tag, []).append(Path(path))
    if not oracles:
        print("this needs at least one --hunspell TAG=PATH", file=sys.stderr)
        return 1

    for tag in (arguments.tag or [t for t in BUNDLED if t in oracles]):
        path = arguments.dictionaries / f"{tag}.tsv"
        if tag not in oracles or not path.is_file():
            print(f"{tag}: no spell checker or no word list, skipped")
            continue
        if tag in CAPITALISES_EVERY_NOUN:
            print(f"{tag}: refused -- this language capitalises every noun, so the rule would "
                  f"flag its whole vocabulary. See CAPITALISES_EVERY_NOUN.")
            continue

        rows = read_rows(path)
        counts: dict[str, int] = {}
        for word, line, _ in rows:
            fields = line.split("\t")
            if word and len(fields) >= 2:
                try:
                    counts[word] = int(fields[1])
                except ValueError:
                    pass

        excluded = read_exclusions(arguments.dictionaries / f"{tag}.names-exclude")
        candidates = [
            word for word, _, is_name in rows
            if word and not is_name and word.isalpha() and word[:1].islower()
            and len(word) >= MIN_LENGTH and word.lower() not in excluded
        ]
        found = looks_like_a_name(sorted(set(candidates)), oracles[tag])

        names_before = sum(1 for _, _, is_name in rows if is_name)
        share = 100.0 * len(found) / max(len(rows), 1)
        print(f"{tag}: {len(found):,} of {len(candidates):,} vocabulary rows look like names "
              f"({share:.1f}% of the pack); {names_before:,} were already flagged")
        ordered = sorted(found, key=lambda w: -counts.get(w, 0))
        for word in ordered[:arguments.sample]:
            print(f"    {word:<22}{counts.get(word, 0):>9,}")

        if arguments.report:
            out = (arguments.report if len(oracles) == 1 else
                   arguments.report.with_suffix(f".{tag}{arguments.report.suffix}"))
            out.write_text(
                "# word\tcount -- most frequent first, which is the end worth reading\n" +
                "".join(f"{w}\t{counts.get(w, 0)}\n" for w in ordered), encoding="utf-8")
            print(f"    review written: {out}")

        if arguments.apply:
            # Only the third column changes. No row is added, removed or reordered, so a diff
            # of this file is exactly the set of words whose classification moved.
            written = []
            for word, line, is_name in rows:
                written.append(f"{line}\tname" if (word in found and not is_name) else line)
            path.write_text("\n".join(written) + "\n", encoding="utf-8")
            print(f"    flagged in place: {path}")
        print()

    if not arguments.apply:
        print("nothing written -- pass --apply")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
