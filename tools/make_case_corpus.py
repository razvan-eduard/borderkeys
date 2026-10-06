#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Writes a capitalisation corpus for PipelineCorpusTest from held-out sentences.

Every word the list has ever flagged as a name is counted inside the held-out sentences, as
`make_case_evidence.py` counts it. A word those sentences write capitalised with 95% confidence
becomes a row expecting its capitalised spelling; one they write in lower case with 95%
confidence, a row expecting the lower case; the rest are left out. The most frequent of each
kind are kept, half the rows each. Each row is typed in lower case after the word the held-out
sentence has before it: `typed<TAB>expected<TAB>-<TAB>word before`.

The sentences must not be the ones `make_case_evidence.py` counted.

Usage
-----
  tools/make_case_corpus.py --words dictionaries/en_US.tsv \\
      --sentences eng_news_2020_1M-sentences.txt --out native-tests/data/autocorrect_case_en.tsv
"""

from __future__ import annotations

import argparse
import math
import sys
from collections import Counter
from pathlib import Path

from make_case_evidence import OPENS_A_SENTENCE, WORD, name_keys

# The rows a corpus holds, half written capitalised and half in lower case.
ROWS = 300

# The normal quantile of the confidence a row's case must reach, as build_dict.py's.
CONFIDENCE_Z = 1.96


def share_bounds(capitalised: int, lower: int) -> tuple[float, float]:
    """The 95% Wilson interval of the capitalised share."""
    total = capitalised + lower
    share = capitalised / total
    z2 = CONFIDENCE_Z * CONFIDENCE_Z
    centre = share + z2 / (2 * total)
    spread = CONFIDENCE_Z * math.sqrt(share * (1 - share) / total + z2 / (4 * total * total))
    denominator = 1 + z2 / total
    return (centre - spread) / denominator, (centre + spread) / denominator


def observe(sentences: Path, keys: set[str]):
    """For each key: its capitalised spellings counted, its lower-case count, and the word before
    its first capitalised and first lower-case occurrence inside a sentence."""
    spellings: dict[str, Counter] = {}
    lower: Counter = Counter()
    before: dict[tuple[str, bool], str] = {}
    with sentences.open(encoding="utf-8", errors="replace") as source:
        for line in source:
            sentence = line.split("\t", 1)[-1]
            previous_end = 0
            previous_word = None
            for match in WORD.finditer(sentence):
                gap = sentence[previous_end:match.start()]
                previous_end = match.end()
                token = match.group(0)
                opens = previous_word is None or any(character in OPENS_A_SENTENCE for character in gap)
                word_before = previous_word
                previous_word = token
                if opens or (len(token) > 1 and token.isupper()):
                    continue
                key = token.casefold()
                if key not in keys:
                    continue
                capitalised = token[0].isupper()
                if capitalised:
                    spellings.setdefault(key, Counter())[token] += 1
                else:
                    lower[key] += 1
                before.setdefault((key, capitalised), word_before)
    return spellings, lower, before


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--words", type=Path, required=True)
    parser.add_argument("--sentences", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    arguments = parser.parse_args(argv)

    keys = {key for key in name_keys(arguments.words) if key.isalpha()}
    spellings, lower, before = observe(arguments.sentences, keys)
    capitalised_rows, lower_rows = [], []
    for key in keys:
        capitalised = sum(spellings.get(key, Counter()).values())
        total = capitalised + lower[key]
        if total == 0:
            continue
        low, high = share_bounds(capitalised, lower[key])
        if low > 0.5:
            spelling = spellings[key].most_common(1)[0][0]
            capitalised_rows.append((total, key, spelling, before[(key, True)]))
        elif high < 0.5:
            lower_rows.append((total, key, key, before[(key, False)]))
    chosen = []
    for rows in (capitalised_rows, lower_rows):
        rows.sort(key=lambda row: (-row[0], row[1]))
        chosen += rows[:ROWS // 2]
    lines = [
        f"# Capitalisation inside a sentence, from {arguments.sentences.name}: the names "
        f"{arguments.words.name} has flagged that it writes capitalised, then those it writes in "
        "lower case, most frequent first. typed<TAB>expected<TAB>-<TAB>word before.",
    ]
    lines += [f"{key}\t{expected}\t-\t{word_before}" for _, key, expected, word_before in chosen]
    arguments.out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{arguments.out.name}: {min(len(capitalised_rows), ROWS // 2)} capitalised, "
          f"{min(len(lower_rows), ROWS // 2)} lower case")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
