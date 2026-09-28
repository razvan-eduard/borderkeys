#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Removes another language's vocabulary from a word list.

A word is suspect when another bundled language knows it as common (Zipf above --floor) and
better than this language by more than --margin Zipf points. A suspect is removed unless the
language's own spell checker accepts it in lower case or its treebank (`<tag>.pos`) tagged it as
one of its ordinary words. Zipf is computed from the word lists themselves.

Usage
-----
  tools/drop_foreign.py                          # report what would go, change nothing
  tools/drop_foreign.py --tag ro-RO --sample 40  # look closely at one language first
  tools/drop_foreign.py --apply                  # rewrite the lists
"""

from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
from pathlib import Path

# The six that ship. Each is both a target to clean and a witness against the others.
BUNDLED = ("en_US", "ro_RO", "de_DE", "es_ES", "fr_FR", "it_IT")
BUNDLED += tuple(
    json.loads(manifest.read_text(encoding="utf-8"))["tag"].replace("-", "_")
    for manifest in sorted((Path(__file__).resolve().parent / "languages").glob("*.json"))
)

# How common, in Zipf, a word has to be in another language before that language can own it.
DEFAULT_FLOOR = 3.0

# In Zipf points; one is a factor of ten.
DEFAULT_MARGIN = 1.0


def read_list(path: Path) -> tuple[dict[str, int], int, set[str]]:
    """A `word<TAB>count[<TAB>name]` file: its counts, the total, and which rows are names.

    Names count towards the total and are never removed.
    """
    counts: dict[str, int] = {}
    names: set[str] = set()
    total = 0
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        try:
            count = int(parts[1])
        except ValueError:
            continue
        counts[parts[0]] = count
        total += count
        if len(parts) >= 3 and parts[2] == "name":
            names.add(parts[0])
    return counts, total, names


def zipf_table(counts: dict[str, int], total: int) -> dict[str, float]:
    """Zipf: log10 of occurrences per billion tokens, the scale the rule's margin is in."""
    if total <= 0:
        return {}
    scale = 1e9 / total
    return {word: math.log10(count * scale) for word, count in counts.items()}


def accepted_by(words: list[str], dictionary: Path) -> set[str]:
    """Which of [words] the language's own spell checker accepts.

    `hunspell -l` prints the words it does not know; the rest are accepted, inflected forms
    included. The word is asked exactly as the list spells it, in lower case. `--allow` covers a
    word this refuses wrongly.
    """
    if not words:
        return set()
    result = subprocess.run(
        ["hunspell", "-d", str(dictionary), "-i", "utf-8", "-l"],
        input="\n".join(words) + "\n", capture_output=True, text=True, check=True,
    )
    rejected = {line.strip() for line in result.stdout.splitlines() if line.strip()}
    return {word for word in words if word not in rejected}


# Which treebank tags are not evidence that the language owns a word, per tagset: proper nouns,
# and each tagset's foreign or residual marker. Spelled out per language; the same letters mean
# different things in different tagsets.
POS_NOT_EVIDENCE: dict[str, tuple[tuple[str, ...], frozenset[str]]] = {
    "en_US": (("NNP",), frozenset({"FW"})),          # Penn: NNP/NNPS proper, FW foreign
    "de_DE": (("NE",), frozenset({"FM", "XY"})),     # STTS: NE proper, FM foreign material
    "ro_RO": (("Np",), frozenset({"X"})),            # MSD: Np proper; Yn (abbreviation) counts
    "es_ES": (("PROPN",), frozenset({"X"})),
    "fr_FR": (("PROPN",), frozenset({"X"})),
    "it_IT": (("SP",), frozenset({"X"})),            # S is a common noun, SP a proper one
}


def treebank_vouches(tag: str, words: list[str], pos_directory: Path) -> set[str]:
    """Which of [words] this language's own treebank tagged as one of its ordinary words.

    A tag in POS_NOT_EVIDENCE does not vouch.
    """
    path = pos_directory / f"{tag}.pos"
    if tag not in POS_NOT_EVIDENCE or not path.is_file():
        return set()
    data = json.loads(path.read_text(encoding="utf-8"))
    tagset = data.get("tagset") or []
    tagged = data.get("tags") or {}
    prefixes, exact = POS_NOT_EVIDENCE[tag]
    vouched = set()
    for word in words:
        index = tagged.get(word)
        if not isinstance(index, int) or not 0 <= index < len(tagset):
            continue
        name = tagset[index]
        if name.startswith(prefixes) or name in exact:
            continue
        vouched.add(word)
    return vouched


def read_allowlist(path: Path | None) -> set[str]:
    if path is None:
        return set()
    words = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        word = line.split("#", 1)[0].strip()
        if word:
            words.add(word)
    return words


def foreign_words(
    tag: str,
    zipf: dict[str, dict[str, float]],
    floor: float,
    margin: float,
    allowed: set[str],
    names: set[str],
) -> dict[str, tuple[str, float, float]]:
    """Every word of [tag] that belongs to another language, and which one claims it."""
    mine = zipf[tag]
    others = [other for other in zipf if other != tag]
    verdict: dict[str, tuple[str, float, float]] = {}
    for word, own in mine.items():
        if word in allowed or word in names:
            continue
        best_tag, best = "", 0.0
        for other in others:
            score = zipf[other].get(word)
            if score is not None and score > best:
                best_tag, best = other, score
        if best > floor and best > own + margin:
            verdict[word] = (best_tag, best, own)
    return verdict


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dictionaries", type=Path, default=Path("dictionaries"),
                        help="directory holding <tag>.tsv, default ./dictionaries")
    parser.add_argument("--tag", action="append", default=None,
                        help="which list to clean, repeatable; default every bundled one")
    parser.add_argument("--floor", type=float, default=DEFAULT_FLOOR,
                        help=f"how common a word must be elsewhere to count, default {DEFAULT_FLOOR}")
    parser.add_argument("--margin", type=float, default=DEFAULT_MARGIN,
                        help=f"Zipf points the other language must win by, default {DEFAULT_MARGIN}")
    parser.add_argument("--allow", type=Path, default=None,
                        help="a file of words to keep whatever the rule says, one per line")
    parser.add_argument("--hunspell", action="append", default=[], metavar="TAG=PATH",
                        help="the language's own spell checker, e.g. "
                             "ro_RO=/path/to/ro_RO for ro_RO.dic + ro_RO.aff. A flagged word it "
                             "accepts is kept. Repeatable, one per language.")
    parser.add_argument("--pos", type=Path, default=None,
                        help="directory holding <tag>.pos treebank files (usually the same as "
                             "--dictionaries). A suspect word its own treebank tagged as an "
                             "ordinary word is kept; this is what saves German nouns.")
    parser.add_argument("--no-oracle", action="store_true",
                        help="allow --apply for a language with no --hunspell. Do not: the Zipf "
                             "rule alone removes core vocabulary a related language weights more "
                             "heavily, which for Romanian is 'e', 'el', 'da', 'sus' and 'place'.")
    parser.add_argument("--sample", type=int, default=15,
                        help="how many of the heaviest removals to print per language")
    parser.add_argument("--apply", action="store_true",
                        help="rewrite the lists; without it nothing is written")
    arguments = parser.parse_args()

    available = [tag for tag in BUNDLED if (arguments.dictionaries / f"{tag}.tsv").is_file()]
    if len(available) < 2:
        print(f"need at least two word lists in {arguments.dictionaries}, found {len(available)}",
              file=sys.stderr)
        return 1

    counts: dict[str, dict[str, int]] = {}
    zipf: dict[str, dict[str, float]] = {}
    names: dict[str, set[str]] = {}
    for tag in available:
        word_counts, total, flagged = read_list(arguments.dictionaries / f"{tag}.tsv")
        counts[tag] = word_counts
        names[tag] = flagged
        zipf[tag] = zipf_table(word_counts, total)
        print(f"{tag}: {len(word_counts):,} words "
              f"({len(word_counts) - len(flagged):,} vocabulary, {len(flagged):,} names), "
              f"{total:,} tokens")

    allowed = read_allowlist(arguments.allow)
    oracles: dict[str, Path] = {}
    for entry in arguments.hunspell:
        tag, _, path = entry.partition("=")
        if not path:
            print(f"--hunspell wants TAG=PATH, got {entry!r}", file=sys.stderr)
            return 1
        oracles[tag] = Path(path)
    targets = arguments.tag or available
    print()

    for tag in targets:
        if tag not in zipf:
            print(f"{tag}: no word list, skipped", file=sys.stderr)
            continue
        verdict = foreign_words(
            tag, zipf, arguments.floor, arguments.margin, allowed, names[tag],
        )
        flagged = len(verdict)
        rescued: set[str] = set()
        if tag in oracles:
            rescued |= accepted_by(sorted(verdict), oracles[tag])
        if arguments.pos:
            rescued |= treebank_vouches(tag, sorted(verdict), arguments.pos)
        for word in rescued:
            verdict.pop(word, None)
        vocabulary = len(counts[tag]) - len(names[tag])
        kept = vocabulary - len(verdict)
        share = 100.0 * len(verdict) / max(vocabulary, 1)
        witnesses = []
        if tag in oracles:
            witnesses.append("spell checker")
        if arguments.pos:
            witnesses.append("treebank")
        oracle_note = (f"{len(rescued):,} rescued by its own {' and '.join(witnesses)}"
                       if witnesses else "NO WITNESS -- unsafe to apply")
        print(f"{tag}: {flagged:,} flagged, {oracle_note}, "
              f"{len(verdict):,} removed of {vocabulary:,} vocabulary words "
              f"({share:.1f}%), {kept:,} kept, names untouched")
        # Heaviest first.
        worst = sorted(verdict.items(), key=lambda item: -counts[tag][item[0]])
        for word, (other, other_zipf, own_zipf) in worst[:arguments.sample]:
            print(f"    {word:<20} {counts[tag][word]:>9,}  "
                  f"{tag} {own_zipf:.2f} vs {other} {other_zipf:.2f}")
        if arguments.apply and tag not in oracles and not arguments.no_oracle:
            print(f"    refusing to write {tag}: no --hunspell for it, and the Zipf rule alone "
                  f"removes real vocabulary. Pass --no-oracle only if you mean it.")
        elif arguments.apply:
            path = arguments.dictionaries / f"{tag}.tsv"
            lines = [
                line for line in path.read_text(encoding="utf-8").splitlines()
                if line.split("\t", 1)[0] not in verdict
            ]
            path.write_text("\n".join(lines) + "\n", encoding="utf-8")
            print(f"    written: {path}")
        print()

    if not arguments.apply:
        print("nothing written -- pass --apply to rewrite the lists")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
