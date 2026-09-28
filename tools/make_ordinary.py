#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Writes the list of corpus words a language's spelling dictionary accepts in lower case.

A word is listed when it is a lower-case headword of one of the dictionaries given, read by
`hunspell -s` (the word itself among its stems); a form Hunspell only builds with an affix rule
is not. Several dictionaries can be given, English beside the language's own.
`make_pack.py --names-ordinary` reads the list and refuses the flag to a word on it that the
treebank does not know.

Restricted to the corpus words that are also in the name list. Needs the `hunspell` binary on
PATH and a .dic/.aff pair per dictionary; only the yes/no per word is kept.

    python3 tools/make_ordinary.py --words dictionaries/ro_RO.tsv --names names_ro.tsv \\
        --dictionary /path/to/ro_RO /path/to/en_US --out dictionaries/ro_RO.names-ordinary
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from make_pack import WORD, read_names  # noqa: E402


def corpus_words(path: Path) -> set[str]:
    """The two-column rows of a dictionaries/<tag>.tsv: the corpus's own words, lower-cased.
    Name rows (a third column) are what the output exists to judge, so they are skipped."""
    words = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) == 2:
            words.add(parts[0].lower())
    return words


def spelled_lower_case(words: list[str], dictionary: Path) -> set[str]:
    """The words that are a lower-case headword of the dictionary.

    `hunspell -s` prints "word stem" for every analysis it has and the bare word for one it
    cannot analyse; the stem comes back in the dictionary's own case."""
    result = subprocess.run(
        ["hunspell", "-d", str(dictionary), "-i", "utf-8", "-s"],
        input="\n".join(words) + "\n", capture_output=True, text=True, check=True,
    )
    stems: dict[str, set[str]] = {}
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) == 2:
            stems.setdefault(parts[0], set()).add(parts[1])
    return {word for word in words if word in stems.get(word, ())}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--words", type=Path, required=True,
                        help="the language's dictionaries/<tag>.tsv (its two-column corpus rows)")
    parser.add_argument("--names", type=Path,
                        help="a make_names.py output; only corpus words it names are tested "
                             "(default: every corpus word, a much longer file)")
    parser.add_argument("--dictionary", type=Path, required=True, nargs="+",
                        help="Hunspell dictionary base paths: /x/ro_RO for /x/ro_RO.dic + .aff; "
                             "a word any of them lists in lower case is ordinary (give the "
                             "English one beside the language's own, for the loanwords)")
    parser.add_argument("--out", type=Path, required=True)
    arguments = parser.parse_args()

    words = corpus_words(arguments.words)
    if arguments.names:
        candidates = {name.lower() for name, _, _ in read_names(arguments.names)}
        words &= candidates
    tested = sorted(w for w in words if WORD.fullmatch(w))
    ordinary: set[str] = set()
    for dictionary in arguments.dictionary:
        ordinary |= spelled_lower_case(tested, dictionary)
    names = ", ".join(d.name for d in arguments.dictionary)
    header = (
        f"# Corpus words of {arguments.words.name} that are a lower-case headword of the "
        f"{names} Hunspell\n# dictionaries: ordinary words whatever the name lists say. Generated "
        "by tools/make_ordinary.py;\n# regenerate rather than edit. Read by make_pack.py "
        "--names-ordinary (docs/dictionaries.md, Names).\n"
    )
    arguments.out.write_text(header + "\n".join(sorted(ordinary)) + "\n", encoding="utf-8")
    print(f"{arguments.out}: {len(ordinary)} of {len(tested)} candidate words are lower-case "
          f"headwords of {names}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
