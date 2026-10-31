#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Writes a capitalisation corpus for PipelineCorpusTest from a language's held-out sentences.

The held-out file and the corpora the language's lists are counted from are its manifest's
(language_manifest.py), each checked against its SHA-256; the language must be admitted by
heldout_overlap.py, and only the case part of its held-out split is read (heldout_overlap.kept:
no counted corpus writes those sentences, each read once). Every word the list has ever flagged
as a name is counted inside those sentences, as `make_case_evidence.py` counts it. A word those
sentences write capitalised with 95% confidence becomes a row expecting its capitalised spelling;
one they write in lower case with 95% confidence, a row expecting the lower case; the rest are
left out. The most frequent of each kind are kept, half the rows each. Each row is typed in lower
case after the word the held-out sentence has before it: `typed<TAB>expected<TAB>-<TAB>word
before`.

Usage
-----
  tools/make_case_corpus.py en_US --out native-tests/data/autocorrect_case_en.tsv \\
      --cache ~/.cache/borderkeys/leipzig-pinned
  tools/make_case_corpus.py --selftest
"""

from __future__ import annotations

import argparse
import math
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import heldout_overlap  # noqa: E402
import language_manifest  # noqa: E402
from make_case_evidence import OPENS_A_SENTENCE, WORD, name_keys  # noqa: E402

# The rows a corpus holds, half written capitalised and half in lower case.
ROWS = 300

# The normal quantile of the confidence a row's case must reach, as build_dict.py's.
CONFIDENCE_Z = 1.96

# The share of the capitalised writings a row's case is judged against.
EVEN = 0.5


def share_bounds(capitalised: int, lower: int) -> tuple[float, float]:
    """The 95% Wilson interval of the capitalised share."""
    total = capitalised + lower
    share = capitalised / total
    z2 = CONFIDENCE_Z * CONFIDENCE_Z
    centre = share + z2 / (2 * total)
    spread = CONFIDENCE_Z * math.sqrt(share * (1 - share) / total + z2 / (4 * total * total))
    denominator = 1 + z2 / total
    return (centre - spread) / denominator, (centre + spread) / denominator


class Observed:
    """How the case part writes each key: its capitalised spellings counted, its lower-case
    count, the word before its first capitalised and first lower-case occurrence inside a
    sentence, and the sentences read."""

    def __init__(self):
        self.spellings: dict[str, Counter] = {}
        self.lower: Counter = Counter()
        self.before: dict[tuple[str, bool], str] = {}
        self.sentences = 0


def observe(lines, excluded: set, split: dict, keys: set[str]) -> Observed:
    """How the case part of [split] of the Leipzig `id<TAB>sentence` [lines] writes each of
    [keys], [excluded] left out."""
    found = Observed()
    for _, sentence in heldout_overlap.kept(lines, excluded, split, language_manifest.CASE_PART):
        found.sentences += 1
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
                found.spellings.setdefault(key, Counter())[token] += 1
            else:
                found.lower[key] += 1
            found.before.setdefault((key, capitalised), word_before)
    return found


def decided(found: Observed, keys: set[str]) -> tuple[list, list]:
    """The rows [found] decides, `(occurrences, key, expected, word before)`: those written
    capitalised, then those written in lower case, each most frequent first."""
    capitalised_rows, lower_rows = [], []
    for key in keys:
        capitalised = sum(found.spellings.get(key, Counter()).values())
        total = capitalised + found.lower[key]
        if total == 0:
            continue
        low, high = share_bounds(capitalised, found.lower[key])
        if low > EVEN:
            spelling = found.spellings[key].most_common(1)[0][0]
            capitalised_rows.append((total, key, spelling, found.before[(key, True)]))
        elif high < EVEN:
            lower_rows.append((total, key, key, found.before[(key, False)]))
    for rows in (capitalised_rows, lower_rows):
        rows.sort(key=lambda row: (-row[0], row[1]))
    return capitalised_rows, lower_rows


def selftest() -> int:
    """Checks the interval; that only the case part is read, each sentence once, a sentence a
    counted corpus writes left out; that a sentence's first word, a word after a sentence mark
    and a word in capitals are not counted; the word before each kind's first occurrence; and
    the rows each kind decides, most frequent first."""
    low, high = share_bounds(8, 2)
    assert round(low, 3) == 0.490 and round(high, 3) == 0.943, (low, high)
    assert share_bounds(10, 0)[0] > EVEN and share_bounds(0, 10)[1] < EVEN

    split = {"dev": 300, "personal": 300, "case": 400}

    def part(text):
        return heldout_overlap.part_of(heldout_overlap.fingerprint(text), split)

    texts = [f"We saw Paris and rome {n} times." for n in range(300)]
    in_case = [text for text in texts if part(text) == "case"]
    elsewhere = [text for text in texts if part(text) != "case"]
    assert in_case and elsewhere
    lines = [f"{n}\t{text}\n" for n, text in enumerate(texts + in_case[:3])]
    keys = {"paris", "rome", "bill", "may"}
    found = observe(lines, set(), split, keys)
    assert found.sentences == len(in_case), (found.sentences, len(in_case))
    assert found.spellings["paris"]["Paris"] == len(in_case), found.spellings
    assert found.lower["rome"] == len(in_case), found.lower
    left_out = {heldout_overlap.fingerprint(text) for text in in_case[:2]}
    assert observe(lines, left_out, split, keys).lower["rome"] == len(in_case) - 2

    def in_case_part(template):
        return [text for text in (template.format(n) for n in range(40)) if part(text) == "case"]

    opening = (in_case_part("Then BILL and may, said Bill {0}.")[:1]
               + in_case_part("Bill came {0}. May we? Then BILL or may, met Bill {0}."))
    found = observe([f"{n}\t{text}\n" for n, text in enumerate(opening)], set(), split, keys)
    assert found.spellings["bill"]["Bill"] == len(opening), found.spellings
    assert found.lower["may"] == len(opening) and "bill" not in found.lower, found.lower
    assert found.before[("bill", True)] == "said" and found.before[("may", False)] == "and"

    found = Observed()
    found.spellings = {"paris": Counter({"Paris": 9, "PAris": 1}), "nice": Counter({"Nice": 5}),
                       "bill": Counter({"Bill": 1})}
    found.lower = Counter({"nice": 5, "bill": 30, "may": 30, "rome": 1})
    found.before = {(key, case): "w" for key in keys | {"nice"} for case in (True, False)}
    capitalised_rows, lower_rows = decided(found, keys | {"nice"})
    assert capitalised_rows == [(10, "paris", "Paris", "w")], capitalised_rows
    assert lower_rows == [(31, "bill", "bill", "w"), (30, "may", "may", "w")], lower_rows
    print("make_case_corpus selftest: ok")
    return 0


def main(argv: list[str]) -> int:
    if "--selftest" in argv:
        return selftest()
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("tag", choices=language_manifest.with_key("heldout"))
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--cache", type=Path, default=language_manifest.CACHE)
    arguments = parser.parse_args(argv)

    manifest = language_manifest.load(arguments.tag)
    verdict = heldout_overlap.verdict(manifest)
    if not verdict.admitted:
        sys.exit(f"{arguments.tag}: refused ({'; '.join(verdict.refusals)}); "
                 f"see tools/heldout_overlap.py {arguments.tag}")
    excluded = set().union(*heldout_overlap.shared(manifest, arguments.cache).values())
    words = language_manifest.ROOT / "dictionaries" / f"{arguments.tag}.tsv"
    keys = {key for key in name_keys(words) if key.isalpha()}
    with heldout_overlap.heldout_sentences(manifest, arguments.cache).open(
            encoding="utf-8", errors="replace") as lines:
        found = observe(lines, excluded, manifest.heldout_split, keys)
    capitalised_rows, lower_rows = decided(found, keys)
    chosen = capitalised_rows[:ROWS // 2] + lower_rows[:ROWS // 2]
    pin = manifest.sha256.get(language_manifest.archive_name(manifest.heldout))
    share = manifest.heldout_split[language_manifest.CASE_PART]
    lines = [
        f"# Capitalisation inside a sentence, from the case part ({share} of "
        f"{language_manifest.SPLIT_UNITS} by fingerprint) of {manifest.heldout} (SHA-256 {pin}), "
        f"{found.sentences} sentences none of {', '.join(manifest.counted)} writes: the names "
        f"{words.name} has flagged that it writes capitalised, then those it writes in lower "
        "case, most frequent first. typed<TAB>expected<TAB>-<TAB>word before.",
    ]
    lines += [f"{key}\t{expected}\t-\t{word_before}" for _, key, expected, word_before in chosen]
    arguments.out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{arguments.out.name}: {min(len(capitalised_rows), ROWS // 2)} capitalised of "
          f"{len(capitalised_rows)}, {min(len(lower_rows), ROWS // 2)} lower case of "
          f"{len(lower_rows)}, from {found.sentences} sentences")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
