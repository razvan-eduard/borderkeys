#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Counts the sentences a language's held-out Leipzig file shares with the corpora its lists are
counted from, and says whether the language may be measured on that file.

The held-out file and the counted corpora are the language's manifest's (language_manifest.py):
`heldout`, and every corpus of `corpora`, `known_corpora`, `case_corpora` and `capitals`, each
fetched and checked against its SHA-256. A language is admitted when its manifest names a held-out
corpus, its split and its word list's corpora, and no counted corpus is the held-out one or from
its year. Sentences are compared with their text normalised to NFC and single spaces.

The sentences no counted corpus writes are split once, by fingerprint, into the manifest's
`heldout_split`: the dev corpora's part, the personal corpus's and the case corpora's take the
fingerprint space in that order from the smallest fingerprint, each its share, so the parts are
disjoint and a sentence keeps its part whatever else the file holds. Each tool that draws on the
held-out file reads its own part, each sentence once.

    python3 tools/heldout_overlap.py en_US ro_RO es_ES     # and each part's sentences
    python3 tools/heldout_overlap.py --check       # every verdict, nothing fetched
    python3 tools/heldout_overlap.py --selftest
"""

from __future__ import annotations

import argparse
import hashlib
import sys
import unicodedata
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import language_manifest  # noqa: E402

# The bytes a sentence's fingerprint keeps.
FINGERPRINT_BYTES = 8


def normalise(text: str) -> str:
    """[text] in NFC, its runs of whitespace as single spaces, trimmed."""
    return " ".join(unicodedata.normalize("NFC", text).split())


def fingerprint(text: str) -> bytes:
    """A short digest of [text] as [normalise] leaves it."""
    return hashlib.blake2b(normalise(text).encode("utf-8"), digest_size=FINGERPRINT_BYTES).digest()


def sentence_text(line: str) -> str:
    """The sentence of a Leipzig `id<TAB>sentence` line."""
    return line.rstrip("\n").split("\t", 1)[-1]


def part_of(mark: bytes, split: dict) -> str:
    """The part of the held-out [split] the sentence of fingerprint [mark] is in."""
    at = int.from_bytes(mark, "big") * language_manifest.SPLIT_UNITS >> (8 * FINGERPRINT_BYTES)
    end = 0
    for part in language_manifest.HELDOUT_PARTS:
        end += split[part]
        if at < end:
            return part
    raise ValueError(f"the split {split} leaves fingerprint {mark.hex()} in no part")


def kept(lines, excluded: set, split: dict, part: str):
    """The sentences of the Leipzig `id<TAB>sentence` [lines] in [part] of [split], as
    `(fingerprint, text)`, each once, in file order, leaving out every sentence whose fingerprint
    is in [excluded]."""
    seen = set()
    for line in lines:
        text = sentence_text(line)
        mark = fingerprint(text)
        if mark in excluded or mark in seen or part_of(mark, split) != part:
            continue
        seen.add(mark)
        yield mark, text


def year_of(corpus: str) -> int:
    """The year in a Leipzig corpus name, `<language>_<source>_<year>_<size>`."""
    return int(corpus.split("_")[2])


@dataclass
class Verdict:
    tag: str
    heldout: str | None
    corpora: list[str]
    refusals: list[str]

    @property
    def admitted(self) -> bool:
        return not self.refusals


def verdict(manifest: language_manifest.Manifest) -> Verdict:
    refusals = []
    if manifest.heldout is None:
        refusals.append("the manifest names no held-out corpus")
    elif manifest.heldout_split is None:
        refusals.append("the manifest records no held-out split")
    if not manifest.corpora:
        refusals.append("the word list's corpora are not recorded")
    if manifest.heldout is not None:
        year = year_of(manifest.heldout)
        same_year = [name for name in manifest.counted if year_of(name) == year]
        if same_year:
            refusals.append(f"counted from the held-out year: {', '.join(same_year)}")
    return Verdict(manifest.tag, manifest.heldout, manifest.counted, refusals)


def heldout_sentences(manifest: language_manifest.Manifest,
                      cache: Path = language_manifest.CACHE) -> Path:
    return language_manifest.sentences(manifest, manifest.heldout, cache)


def part_sentences(manifest: language_manifest.Manifest, part: str, excluded: set,
                   cache: Path = language_manifest.CACHE):
    """[part] of [manifest]'s held-out file as [kept] gives it, [excluded] left out."""
    with heldout_sentences(manifest, cache).open(encoding="utf-8", errors="replace") as lines:
        yield from kept(lines, excluded, manifest.heldout_split, part)


def shared(manifest: language_manifest.Manifest,
           cache: Path = language_manifest.CACHE) -> dict[str, set[bytes]]:
    """For each counted corpus, the fingerprints of the held-out sentences it writes."""
    held = set()
    with heldout_sentences(manifest, cache).open(encoding="utf-8", errors="replace") as source:
        for line in source:
            held.add(fingerprint(sentence_text(line)))
    out = {}
    for name in manifest.counted:
        found = set()
        path = language_manifest.sentences(manifest, name, cache)
        with path.open(encoding="utf-8", errors="replace") as source:
            for line in source:
                mark = fingerprint(sentence_text(line))
                if mark in held:
                    found.add(mark)
        out[name] = found
    return out


def selftest() -> int:
    """Checks the verdict: admitted with a held-out corpus from no counted year and a split,
    refused with no held-out corpus, with no split, with no word-list corpora, and with a counted
    corpus from the held-out year, the capitals corpus and the held-out one itself included. Then
    the split: each part takes its share of the fingerprint space in order from the smallest, its
    bounds where the shares put them; every sentence left is in exactly one part, each once, and
    the parts are disjoint; a sentence a counted corpus writes is in none; an empty part holds
    nothing."""
    split = {"dev": 5, "personal": 600, "case": 395}

    def manifest(corpora=("aaa_wikipedia_2021_1M",), capitals="aaa_news_2020_1M",
                 heldout="aaa_news_2023_1M", heldout_split=split):
        return language_manifest.Manifest(
            path=Path("xx_XX.json"), tag="xx-XX", corpora=list(corpora),
            known_corpora=["aaa_news_2024_1M"], case_corpora=["aaa_newscrawl_2018_1M"],
            capitals=capitals, heldout=heldout, persona=None, sha256={},
            heldout_split=heldout_split)

    assert verdict(manifest()).admitted
    for case, reason in ((manifest(heldout=None), "held-out"),
                         (manifest(heldout_split=None), "split"),
                         (manifest(corpora=()), "corpora"),
                         (manifest(capitals="aaa_news_2023_1M"), "year"),
                         (manifest(heldout="aaa_news_2021_1M"), "year"),
                         (manifest(heldout="aaa_news_2024_1M"), "year"),
                         (manifest(heldout="aaa_newscrawl_2018_1M"), "year")):
        found = verdict(case)
        assert not found.admitted and reason in " ".join(found.refusals), (case, found.refusals)

    space = 1 << (8 * FINGERPRINT_BYTES)
    units = language_manifest.SPLIT_UNITS

    def mark_at(value):
        return value.to_bytes(FINGERPRINT_BYTES, "big")

    def first_of(unit):
        """The smallest fingerprint at [unit] of the space."""
        return mark_at(-(-unit * space // units))

    assert part_of(mark_at(0), split) == "dev"
    assert part_of(mark_at(space - 1), split) == "case"
    for bound, before, after in ((5, "dev", "personal"), (605, "personal", "case")):
        assert part_of(first_of(bound), split) == after, bound
        previous = int.from_bytes(first_of(bound), "big") - 1
        assert part_of(mark_at(previous), split) == before, bound
    assert part_of(mark_at(space - 1), {"dev": 5, "personal": 995, "case": 0}) == "personal"

    lines = [f"{n}\tSentence number {n} is here.\n" for n in range(4000)]
    lines += [f"{n + 4000}\tSentence number {n} is  here.\n" for n in range(0, 4000, 7)]
    excluded = {fingerprint(f"Sentence number {n} is here.") for n in range(0, 4000, 11)}
    wide = {"dev": 200, "personal": 300, "case": 500}
    parts = {part: list(kept(lines, excluded, wide, part))
             for part in language_manifest.HELDOUT_PARTS}
    marks = {part: [mark for mark, _ in found] for part, found in parts.items()}
    every = [mark for found in marks.values() for mark in found]
    assert len(every) == len(set(every)), "a sentence in two parts, or twice in one"
    left = {fingerprint(sentence_text(line)) for line in lines} - excluded
    assert set(every) == left, "a sentence left in no part, or one left out in a part"
    for part, found in marks.items():
        assert all(part_of(mark, wide) == part for mark in found), part
        share = len(found) / len(left)
        assert abs(share - wide[part] / units) < 0.05, (part, share)
    texts = [text for found in parts.values() for _, text in found]
    assert all("  " not in text for text in texts), "a sentence kept where it is written again"
    order = [int(text.split()[2]) for _, text in parts["dev"]]
    assert order == sorted(order), "a part out of the file's order"
    empty = {"dev": 5, "personal": 0, "case": 995}
    assert list(kept(lines, excluded, empty, "personal")) == []
    print("heldout_overlap selftest: ok")
    return 0


def main() -> int:
    if "--selftest" in sys.argv[1:]:
        return selftest()
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("tags", nargs="*", default=language_manifest.with_key("heldout"))
    parser.add_argument("--cache", type=Path, default=language_manifest.CACHE)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    refused = 0
    for tag in args.tags:
        manifest = language_manifest.load(tag)
        found = verdict(manifest)
        print(f"{tag}: held out {found.heldout}; counted from {', '.join(found.corpora)}")
        if not found.admitted:
            for refusal in found.refusals:
                print(f"  refused: {refusal}")
            refused += 1
            continue
        if args.check:
            print("  admitted")
            continue
        overlap = shared(manifest, args.cache)
        for name, prints in overlap.items():
            print(f"  {name}: {len(prints)} held-out sentences also written there")
        excluded = set().union(*overlap.values())
        for part in language_manifest.HELDOUT_PARTS:
            count = sum(1 for _ in part_sentences(manifest, part, excluded, args.cache))
            print(f"  {part} part, {manifest.heldout_split[part]} of "
                  f"{language_manifest.SPLIT_UNITS}: {count} sentences")
        print("  admitted")
    return 1 if refused else 0


if __name__ == "__main__":
    sys.exit(main())
