#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Counts the sentences a language's held-out Leipzig file shares with the corpora its bundled
list is counted from, and says whether the language may be measured on that file.

The held-out file is the one classify_wordlist.py counts capitals in. The counted corpora are
the ones make_known_words.py names for the list, with those the word-list cache records for it
(`<cache>/wordlist/<tag>.corpora`, one name per line) when that record exists. A language is
admitted when every counted corpus is cached and none is from the held-out file's year.
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
from classify_wordlist import SOURCES  # noqa: E402
from make_known_words import CORPORA  # noqa: E402

CACHE = Path.home() / ".cache" / "borderkeys"
LEIPZIG = CACHE / "leipzig"
HELDOUT = CACHE / "heldout"
WORDLIST = CACHE / "wordlist"

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


def heldout_name(tag: str) -> str:
    return SOURCES[tag].capitals


def heldout_path(tag: str, heldout: Path = HELDOUT) -> Path:
    name = heldout_name(tag)
    return heldout / name / f"{name}-sentences.txt"


def counted_corpora(tag: str, wordlist: Path = WORDLIST) -> list[str]:
    """The corpora [tag]'s list is counted from, in the order first named."""
    names = list(CORPORA[tag])
    record = wordlist / f"{tag}.corpora"
    if record.is_file():
        for line in record.read_text(encoding="utf-8").split():
            if line not in names:
                names.append(line)
    return names


@dataclass
class Verdict:
    tag: str
    heldout: str
    corpora: list[str]
    missing: list[str]
    same_year: list[str]

    @property
    def admitted(self) -> bool:
        return not self.missing and not self.same_year


def verdict(tag: str, leipzig: Path = LEIPZIG, heldout: Path = HELDOUT,
            wordlist: Path = WORDLIST) -> Verdict:
    corpora = counted_corpora(tag, wordlist)
    missing = [name for name in corpora if not (leipzig / f"{name}-sentences.txt").is_file()]
    if not heldout_path(tag, heldout).is_file():
        missing.append(heldout_name(tag))
    year = year_of(heldout_name(tag))
    same_year = [name for name in corpora if year_of(name) == year]
    return Verdict(tag, heldout_name(tag), corpora, missing, same_year)


def shared(tag: str, leipzig: Path = LEIPZIG, heldout: Path = HELDOUT,
           wordlist: Path = WORDLIST) -> dict[str, set[bytes]]:
    """For each counted corpus of [tag], the fingerprints of the held-out sentences it writes."""
    held = set()
    with heldout_path(tag, heldout).open(encoding="utf-8", errors="replace") as source:
        for line in source:
            held.add(fingerprint(sentence_text(line)))
    out = {}
    for name in counted_corpora(tag, wordlist):
        found = set()
        with (leipzig / f"{name}-sentences.txt").open(encoding="utf-8", errors="replace") as source:
            for line in source:
                mark = fingerprint(sentence_text(line))
                if mark in held:
                    found.add(mark)
        out[name] = found
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("tags", nargs="*", default=sorted(CORPORA))
    parser.add_argument("--leipzig", type=Path, default=LEIPZIG)
    parser.add_argument("--heldout", type=Path, default=HELDOUT)
    parser.add_argument("--wordlist", type=Path, default=WORDLIST)
    args = parser.parse_args()

    refused = 0
    for tag in args.tags:
        found = verdict(tag, args.leipzig, args.heldout, args.wordlist)
        print(f"{tag}: held out {found.heldout}; counted from {', '.join(found.corpora)}")
        if found.missing:
            print(f"  refused: not cached: {', '.join(found.missing)}")
        if found.same_year:
            print(f"  refused: counted from the held-out year: {', '.join(found.same_year)}")
        if not found.admitted:
            refused += 1
            continue
        for name, prints in shared(tag, args.leipzig, args.heldout, args.wordlist).items():
            print(f"  {name}: {len(prints)} held-out sentences also written there")
        print("  admitted")
    return 1 if refused else 0


if __name__ == "__main__":
    sys.exit(main())
