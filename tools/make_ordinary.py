#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Writes the list of corpus words a language's spelling dictionary accepts in lower case.

`make_pack.py --names` decides, word by word, whether a name from Wikidata may capitalise itself
every time it is typed. Its first witness is the treebank behind `dictionaries/<tag>.pos`, and
that witness has a blind spot: it keeps one tag per word, the one the treebank used most, and
a treebank of web text meets "Apple", "Cloud" and "Hidden" (the brand, the service, the valley)
more often than the fruit, the sky and the adjective. Every one of those came out flagged, and
so did several thousand words past the treebank's own vocabulary that Wikidata knows a single
person by ("thunder", "needle", "wage" are all somebody's family name).

A spelling dictionary is the second witness: Hunspell lists the ordinary words of a language
in the case they are written, so a lower-case "cloud" is accepted and a lower-case "sadoveanu"
is not. Not every acceptance counts, though. Hunspell also accepts a word it can build from a
headword with an affix rule, and the rules are generous: Romanian "maria" is a form of the
verb "a maria", "popescu" is "popesc" (priestly) with an article, Spanish "patricia" is a form
of "patricio". What this script writes is the corpus words that ARE a lower-case headword
("cloud", "needle", "wage"), read by `hunspell -s` (print each word's stems): the word itself
among its stems. Everything else Hunspell merely tolerates stays a name -- including a
headword's inflections, on purpose: half of Romania's surnames are an ordinary word with the
article on the end ("lupu", "ciobanu", "moraru", "rotaru" -- wolf, shepherd, miller,
wheelwright), and a rule that read "wages" as a form of "wage" read every one of them as a
word too. The few English plurals that slip through this way ("wages", "trucks") go in the
hand-kept exclude list.

`make_pack.py --names-ordinary` reads the list and refuses the flag to a word on it that the
treebank does not know: where the treebank has met the word, its own verdict stands, and a
treebank that met "Dan" and "Ion" mostly as names outranks a dictionary that also knows a
martial-arts rank and a charged particle.

Several dictionaries can be given, and the English one belongs beside every other language's:
a corpus of Romanian, German or Spanish web text is full of English -- "hot", "life",
"service", "happy", "end" -- that the language's own dictionary has never heard of, its
treebank has no tag for, and Wikidata has a family name for. Every one of those was flagged.
A word any of the dictionaries given lists in lower case is an ordinary word.

Restricted to the words the name list could touch (corpus words that are also in the fetch),
so the committed file stays a few thousand lines rather than the whole vocabulary. Needs the
`hunspell` binary on PATH and a .dic/.aff pair per dictionary -- LibreOffice ships one per
language; nothing from them is copied, only the yes/no per word.

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
    cannot analyse; the stem comes back in the dictionary's own case, so "Maria" never
    answers for "maria". A lower-case input is what makes the answer mean "an ordinary word":
    a lower-case entry accepts both spellings, a capitalised one only its own."""
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
