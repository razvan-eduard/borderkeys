#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Checks that each language's committed lists can store what the next-word score defines: every
n-gram a relative frequency count / count(context).

For every `<tag>.ngrams` under `dictionaries/` and `dictionaries/extra/`, read beside its `.tsv`,
`.known` and its manifest `tools/languages/<tag>.json`:

  spellings   every word of a counted n-gram is listed under its own token form; one listed only
              under another spelling (a capitalised row, a Romanian cedilla or tilde form) loses
              the n-gram in tools/build_dict.py
  contexts    no listed word's stored successors add up to more than its own count, which a
              count / count(context) cannot exceed
  starts      the manifest records `sentence_starts`, the sentences the lists were counted from,
              and the kept start pairs add up to no more than that

Each failure is printed with its language, its size and an example; the exit status is then
non-zero. Runs in CI beside the other self-tests.

    python3 tools/check_pack_definitions.py
    python3 tools/check_pack_definitions.py --selftest
"""

from __future__ import annotations

import json
import sys
import tempfile
import unicodedata
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from build_dict import (SENTENCE_START, fold_word, load_known, plain_apostrophes,  # noqa: E402
                        without_vowel_marks)
from normalise_diacritics import CEDILLA, TILDE  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent

# The spellings a language's normaliser, tools/normalise_diacritics.py, writes another way, by
# tag.
RESPELLED = {"ro_RO": {**CEDILLA, **TILDE}}

# The manifest key naming the sentences the lists were counted from.
STARTS_KEY = "sentence_starts"

# How many examples a failure shows.
EXAMPLES = 3


def token_form(word: str) -> str:
    """[word] as tools/make_pack.py counts it in a corpus: NFC, lower case, its apostrophes plain,
    without vowel marks."""
    return without_vowel_marks(plain_apostrophes(unicodedata.normalize("NFC", word).lower()))


def listed_words(tsv: Path, known: Path) -> dict[str, int]:
    """Each spelling tools/build_dict.py lists, with the count it divides by: spellings that
    differ only by case merged into the commonest, their counts summed, the first written on a
    tie; known-only words, read as tools/build_dict.py reads them, left out."""
    known_only = {word for word, _ in load_known(known)} if known.is_file() else set()
    groups: dict[tuple[tuple[int, ...], str], list] = {}
    for line in tsv.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) < 2 or parts[0] in known_only:
            continue
        word, count = parts[0], int(parts[1])
        member = groups.setdefault((tuple(fold_word(word)), word.casefold()), [word, 0])
        if count > member[1]:
            member[0] = word
        member[1] += count
    return {word: count for word, count in groups.values()}


def read_ngrams(path: Path) -> list[tuple[tuple[str, ...], int]]:
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) >= 3:
            out.append((tuple(parts[:-1]), int(parts[-1])))
    return out


def spelling_failures(tag: str, listed: dict[str, int], ngrams) -> list[str]:
    forms = {token_form(word) for word in listed}
    respell = str.maketrans(RESPELLED.get(tag, {}))
    lost: dict[str, int] = defaultdict(int)
    examples: list[str] = []
    for parts, count in ngrams:
        for part in parts:
            if part == SENTENCE_START or part in listed:
                continue
            if part in forms or part.translate(respell) in listed:
                kind = "start pairs" if parts[0] == SENTENCE_START else \
                    "pairs" if len(parts) == 2 else "triples"
                lost[kind] += 1
                if len(examples) < EXAMPLES:
                    examples.append(f"{' '.join(parts)} {count}")
                break
    if not lost:
        return []
    sizes = ", ".join(f"{number:,} {kind}" for kind, number in sorted(lost.items()))
    return [f"spellings: {sizes} counted under a form the list writes another way "
            f"(e.g. {'; '.join(examples)})"]


def context_failures(listed: dict[str, int], ngrams) -> list[str]:
    successors: dict[str, int] = defaultdict(int)
    for parts, count in ngrams:
        if len(parts) == 2 and parts[0] != SENTENCE_START and all(p in listed for p in parts):
            successors[parts[0]] += count
    over = sorted(((total / listed[word], word, total) for word, total in successors.items()
                   if total > listed[word]), reverse=True)
    if not over:
        return []
    examples = "; ".join(f"{word} {listed[word]} against {total}" for _, word, total in over[:EXAMPLES])
    return [f"contexts: {len(over):,} listed words whose stored successors add up to more than "
            f"their count (e.g. {examples})"]


def start_failures(manifest: Path, ngrams) -> list[str]:
    kept = sum(count for parts, count in ngrams if parts[0] == SENTENCE_START)
    if not kept:
        return []
    data = json.loads(manifest.read_text(encoding="utf-8")) if manifest.is_file() else {}
    starts = data.get(STARTS_KEY)
    if starts is None:
        return [f"starts: {manifest.name if manifest.is_file() else 'no manifest'} records no "
                f"{STARTS_KEY}, so the start pairs are divided by the {kept:,} kept"]
    if kept > starts:
        return [f"starts: the kept start pairs add up to {kept:,}, more than the {starts:,} "
                f"sentences counted"]
    return []


def failures_of(tag: str, directory: Path, manifests: Path) -> list[str]:
    ngrams = read_ngrams(directory / f"{tag}.ngrams")
    listed = listed_words(directory / f"{tag}.tsv", directory / f"{tag}.known")
    return (spelling_failures(tag, listed, ngrams) + context_failures(listed, ngrams) +
            start_failures(manifests / f"{tag}.json", ngrams))


def check(root: Path) -> list[tuple[str, str]]:
    """Every failure under [root]'s dictionaries, as (tag, failure), in tag order."""
    found = []
    for directory in (root / "dictionaries", root / "dictionaries" / "extra"):
        for ngrams in sorted(directory.glob("*.ngrams")):
            tag = ngrams.stem
            if (directory / f"{tag}.tsv").is_file():
                found.extend((tag, failure)
                             for failure in failures_of(tag, directory, root / "tools" / "languages"))
    return found


def selftest() -> int:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        lists, manifests = root / "dictionaries", root / "tools" / "languages"
        lists.mkdir(parents=True)
        manifests.mkdir(parents=True)

        def write(tag: str, tsv: str, ngrams: str, starts: int | None) -> None:
            (lists / f"{tag}.tsv").write_text(tsv, encoding="utf-8")
            (lists / f"{tag}.ngrams").write_text(ngrams, encoding="utf-8")
            manifest = {"tag": tag} if starts is None else {"tag": tag, STARTS_KEY: starts}
            (manifests / f"{tag}.json").write_text(json.dumps(manifest), encoding="utf-8")

        clean_tsv = "the\t100\ncat\t30\ndog\t20\n"
        clean_ngrams = f"{SENTENCE_START}\tthe\t40\nthe\tcat\t25\nthe\tdog\t15\nthe\tcat\tdog\t3\n"
        write("xx_XX", clean_tsv, clean_ngrams, 50)
        assert check(root) == [], check(root)
        # Spellings that differ only by case are one word: their counts add up to the context's.
        write("xx_XX", "the\t20\nThe\t20\ncat\t30\ndog\t20\n", clean_ngrams, 50)
        assert check(root) == [], check(root)

        # Each rule broken once: a capitalised row, a context counted above its count, and no
        # sentence count recorded.
        write("xx_XX", "the\t100\nCat\t30\ndog\t20\n", clean_ngrams, 50)
        found = check(root)
        assert len(found) == 1 and found[0][1].startswith("spellings: 1 pairs, 1 triples"), found
        write("xx_XX", "the\t30\ncat\t30\ndog\t20\n", clean_ngrams, 50)
        found = check(root)
        assert len(found) == 1 and "contexts: 1 listed" in found[0][1] and "the 30 against 40" in found[0][1], found
        # A word of the .known file's header comment is not known-only: its row stays checked.
        (lists / "xx_XX.known").write_text("# Words the pack knows but never offers\n"
                                           "# tokens\t1000\nzebra\t5\n", encoding="utf-8")
        found = check(root)
        assert len(found) == 1 and "the 30 against 40" in found[0][1], found
        (lists / "xx_XX.known").unlink()
        write("xx_XX", clean_tsv, clean_ngrams, None)
        found = check(root)
        assert len(found) == 1 and f"records no {STARTS_KEY}" in found[0][1], found
        write("xx_XX", clean_tsv, clean_ngrams, 30)
        found = check(root)
        assert len(found) == 1 and "more than the 30 sentences" in found[0][1], found

        # A Romanian pair counted under its cedilla spelling is lost to the comma one.
        (lists / "xx_XX.ngrams").unlink()
        write("ro_RO", "și\t50\nde\t40\n", f"{SENTENCE_START}\tși\t5\nşi\tde\t4\nși\tde\t9\n", 10)
        found = check(root)
        assert len(found) == 1 and found[0][0] == "ro_RO", found
        assert found[0][1].startswith("spellings: 1 pairs"), found
    print("check_pack_definitions selftest: ok")
    return 0


def main() -> int:
    if "--selftest" in sys.argv[1:]:
        return selftest()
    found = check(ROOT)
    for tag, failure in found:
        print(f"{tag}: {failure}")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
