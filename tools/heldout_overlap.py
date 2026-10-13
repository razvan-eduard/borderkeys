#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Counts the sentences a language's held-out Leipzig file shares with the corpora its lists are
counted from, and says whether the language may be measured on that file.

The held-out file and the counted corpora are the language's manifest's (language_manifest.py):
`heldout`, and every corpus of `corpora`, `known_corpora`, `case_corpora` and `capitals`, each
fetched and checked against its SHA-256. A language is admitted when its manifest names a held-out
corpus and its word list's corpora, and no counted corpus is the held-out one or from its year.
Sentences are compared with their text normalised to NFC and single spaces.

    python3 tools/heldout_overlap.py en_US ro_RO es_ES
"""

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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("tags", nargs="*", default=language_manifest.with_key("heldout"))
    parser.add_argument("--cache", type=Path, default=language_manifest.CACHE)
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
        for name, prints in shared(manifest, args.cache).items():
            print(f"  {name}: {len(prints)} held-out sentences also written there")
        print("  admitted")
    return 1 if refused else 0


if __name__ == "__main__":
    sys.exit(main())
