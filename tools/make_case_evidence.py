#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Counts how a word list's names are written inside sentences: capitalised or in lower case.

For every word the list flags as a name, each occurrence in the corpus sentences that is not a
sentence's first word, does not follow a sentence mark, and is not written all in capitals, is
counted as capitalised or as lower case. `build_dict.py --case` reads the result and keeps the
name flag off a word its writers mostly write in lower case, and refuses to build a list with a
name this file does not hold.

Writes `word<TAB>capitalised<TAB>lower case` for every name, 0 and 0 for one the corpora never
show inside a sentence, sorted, after a comment naming the corpora.

Usage
-----
  tools/make_case_evidence.py --words dictionaries/en_US.tsv \\
      --corpus eng_news_2024_1M-sentences.txt eng_wikipedia_2016_1M-sentences.txt \\
      --out dictionaries/en_US.case
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# A word as the corpus writes it: letters, with apostrophes and hyphens inside.
WORD = re.compile(r"[^\W\d_](?:[^\W\d_]|['’-](?=[^\W\d_]))*")

# What ends a sentence, or opens one inside another, so the word after it is not counted.
OPENS_A_SENTENCE = set('.!?:;"“”«»„()[]—–…')


def name_keys(words: Path) -> set[str]:
    """The case-folded spellings the list flags as names."""
    keys = set()
    for line in words.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) >= 3 and parts[2] == "name":
            keys.add(parts[0].casefold())
    return keys


def count(sentences: list[Path], keys: set[str]) -> dict[str, list[int]]:
    """For each key, how often it is written capitalised and in lower case inside a sentence."""
    counts: dict[str, list[int]] = {}
    for path in sentences:
        with path.open(encoding="utf-8", errors="replace") as source:
            for line in source:
                # Leipzig lines are `id<TAB>sentence`.
                sentence = line.split("\t", 1)[-1]
                previous_end = 0
                first = True
                for match in WORD.finditer(sentence):
                    gap = sentence[previous_end:match.start()]
                    previous_end = match.end()
                    opens = first or any(character in OPENS_A_SENTENCE for character in gap)
                    first = False
                    token = match.group(0)
                    if opens or (len(token) > 1 and token.isupper()):
                        continue
                    key = token.casefold()
                    if key not in keys:
                        continue
                    tally = counts.setdefault(key, [0, 0])
                    tally[0 if token[0].isupper() else 1] += 1
    return counts


def selftest() -> None:
    keys = {"gates", "john"}
    sample = Path(__file__).with_name(".case_selftest.txt")
    sample.write_text(
        "1\tGates opened the gates for John.\n"
        "2\tThe gates were shut, said JOHN GATES.\n"
        "3\tWe met john and Gates: Gates spoke.\n",
        encoding="utf-8",
    )
    try:
        counts = count([sample], keys)
    finally:
        sample.unlink()
    # "Gates" first in line 1, after ":" in line 3, and the capitals of line 2 are not counted.
    assert counts["gates"] == [1, 2], counts
    assert counts["john"] == [1, 1], counts
    print("make_case_evidence selftest passed")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--words", type=Path)
    parser.add_argument("--corpus", type=Path, nargs="+")
    parser.add_argument("--out", type=Path)
    parser.add_argument("--selftest", action="store_true")
    arguments = parser.parse_args(argv)
    if arguments.selftest:
        selftest()
        return 0
    if not arguments.words or not arguments.corpus or not arguments.out:
        parser.error("--words, --corpus and --out are required unless --selftest is given")

    keys = name_keys(arguments.words)
    counts = count(arguments.corpus, keys)
    lines = [f"# capitalised and lower-case counts inside sentences, from {', '.join(p.name for p in arguments.corpus)}"]
    lines += [f"{key}\t{counts.get(key, (0, 0))[0]}\t{counts.get(key, (0, 0))[1]}" for key in sorted(keys)]
    arguments.out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{arguments.words.name}: {len(keys):,} names, {len(counts):,} seen inside sentences -> {arguments.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
