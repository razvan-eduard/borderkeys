#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Merges a make_names.py list into an already-built `dictionaries/<tag>.tsv`.

Edits the list in place; `build_dict.py` recompiles the pack from it. The guards are
`make_pack.py`'s own, imported: `name_allowed` and `NAME_ADD_MIN_USES`.

Flagging a word the list already has is also refused when any shipped language's spell checker
accepts it in lower case. Adding a word the list does not have asks only this language's own
dictionary.

A word with a letter outside the language's alphabet is dropped; the alphabet is read off the
pack's ALPHABET_SAMPLE most frequent words.

Usage
-----
Pass every shipped language's spell checker, whichever list is being merged:

  tools/merge_names.py --tag ro_RO --names entities_ro.tsv \\
      --hunspell en_US=/path/en_US --hunspell en_US=/path/en_GB \\
      --hunspell de_DE=/path/de_DE_frami --hunspell es_ES=/path/es_ES \\
      --hunspell fr_FR=/path/fr --hunspell it_IT=/path/it_IT \\
      --hunspell ro_RO=/path/ro_RO --report review_ro.txt --apply
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

import make_pack

# How deep into the pack to look when working out which letters the language writes with.
ALPHABET_SAMPLE = 5_000

# The shortest word judged, as flag_names.py's MIN_LENGTH and make_names.py's MIN_NAME_LENGTH.
MIN_LENGTH = 3


def read_rows(path: Path) -> list[tuple[str, str, bool]]:
    """Every line as (word, raw line, is already a name), order preserved."""
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            rows.append(("", line, False))
            continue
        rows.append((parts[0], line, len(parts) >= 3 and parts[2] == "name"))
    return rows


def accepted_lower_case(words: list[str], dictionaries: list[Path]) -> set[str]:
    """Which of [words] at least one of these spell checkers accepts in lower case.

    A spell checker that will not run vetoes nothing, and says so.
    """
    if not words:
        return set()
    accepted: set[str] = set()
    for dictionary in dictionaries:
        try:
            result = subprocess.run(
                ["hunspell", "-d", str(dictionary), "-i", "utf-8", "-l"],
                input="\n".join(words) + "\n", capture_output=True, text=True, check=True,
            )
        except (OSError, subprocess.CalledProcessError) as error:
            print(f"hunspell failed on {dictionary}: {error}", file=sys.stderr)
            continue
        rejected = {line.strip() for line in result.stdout.splitlines() if line.strip()}
        accepted |= {word for word in words if word not in rejected}
    return accepted


def alphabet_of(rows: list[tuple[str, str, bool]], frequencies: dict[str, int]) -> set[str]:
    """The letters this language's most frequent words are written with."""
    words = sorted((word for word, _, _ in rows if word), key=lambda w: -frequencies.get(w, 0))
    return {c for c in "".join(words[:ALPHABET_SAMPLE]) if c.isalpha()}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0],
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dictionaries", type=Path, default=Path("dictionaries"))
    parser.add_argument("--tag", required=True, help="e.g. en_US, matching dictionaries/<tag>.tsv")
    parser.add_argument("--names", type=Path, required=True, help="a make_names.py output")
    parser.add_argument("--hunspell", action="append", default=[], metavar="TAG=PATH",
                        help="a spell checker and the language it belongs to; repeat once per "
                             "language the project ships, NOT just this list's own. A word any "
                             "one of them accepts in lower case is never flagged. Only the ones "
                             "tagged for --tag decide whether a word may be added -- see the "
                             "module doc for why the two questions differ.")
    parser.add_argument("--flag-only", action="store_true",
                        help="a name the corpus already has gains the flag; one it does not have "
                             "is NOT added. This is what a person-name list wants -- make_pack.py "
                             "keeps the same distinction as --names-flag-only, because a hundred "
                             "thousand surnames at one flat frequency would outrank the corpus's "
                             "own tail. Entity lists are small enough to add from.")
    parser.add_argument("--sample", type=int, default=25)
    parser.add_argument("--report", type=Path, default=None,
                        help="write the full before-and-after here, for review")
    parser.add_argument("--apply", action="store_true")
    arguments = parser.parse_args()

    dictionaries, own = [], []
    for entry in arguments.hunspell:
        tag, _, where = entry.partition("=")
        if not where:
            print(f"--hunspell wants TAG=PATH, got {entry!r}", file=sys.stderr)
            return 1
        dictionaries.append(Path(where))
        if tag == arguments.tag:
            own.append(Path(where))
    if not dictionaries:
        print("this needs at least one --hunspell TAG=PATH", file=sys.stderr)
        return 1
    if not own:
        print(f"none of the spell checkers is tagged {arguments.tag}, so nothing could be added",
              file=sys.stderr)
        return 1

    path = arguments.dictionaries / f"{arguments.tag}.tsv"
    rows = read_rows(path)
    frequencies = {}
    for word, line, _ in rows:
        parts = line.split("\t")
        if word and len(parts) >= 2:
            try:
                frequencies[word] = int(parts[1])
            except ValueError:
                pass
    vocabulary = set(frequencies)
    already = {word for word, _, is_name in rows if is_name}
    ranks = {word: index + 1 for index, (word, _) in
             enumerate(sorted(frequencies.items(), key=lambda pair: -pair[1]))}
    letters = alphabet_of(rows, frequencies)

    ordinary = make_pack.common_words(arguments.dictionaries / f"{arguments.tag}.pos", frequencies)
    excluded = set()
    for suffix, field in ((".names-exclude", "exact"), (".names-ordinary", "spelled")):
        source = arguments.dictionaries / f"{arguments.tag}{suffix}"
        if source.is_file():
            listed = make_pack.read_word_list(source)
            setattr(ordinary, field, getattr(ordinary, field) | listed)
            if suffix == ".names-exclude":
                excluded = listed

    candidates: dict[str, int] = {}
    flat: dict[str, int] = {}
    for name, frequency, uses in make_pack.read_names(arguments.names):
        key = name.lower()
        if len(key) < MIN_LENGTH or key in excluded or not set(key) <= letters:
            continue
        candidates[key] = max(candidates.get(key, 0), uses)
        flat[key] = frequency

    ordinary_anywhere = accepted_lower_case(sorted(candidates), dictionaries)
    ordinary_here = accepted_lower_case(sorted(candidates), own)
    flag: dict[str, int] = {}
    add: dict[str, int] = {}
    for key, uses in candidates.items():
        if not make_pack.name_allowed(key, uses, ranks.get(key), ordinary, frequencies):
            continue
        if key in vocabulary:
            if key not in already and key not in ordinary_anywhere:
                flag[key] = uses
        elif (not arguments.flag_only and uses >= make_pack.NAME_ADD_MIN_USES
              and key not in ordinary_here):
            add[key] = uses

    print(f"{arguments.tag}: {len(candidates):,} candidates, {len(letters)}-letter alphabet, "
          f"{len(ordinary_anywhere):,} ordinary somewhere ({len(ordinary_here):,} here)")
    print(f"    {len(flag):,} rows the pack already has gain the flag")
    print(f"    {len(add):,} rows are added at the flat frequency "
          f"({100.0 * len(add) / max(len(vocabulary), 1):.2f}% growth)"
          + ("  [--flag-only: nothing is added]" if arguments.flag_only else ""))
    by_rank = sorted(flag, key=lambda w: ranks[w])
    for word in by_rank[:arguments.sample]:
        print(f"    flag {word:<24}{frequencies[word]:>9,} in corpus")
    for word in sorted(add, key=lambda w: -add[w])[:arguments.sample]:
        print(f"    add  {word:<24}{add[word]:>9,} witnesses")

    if arguments.report:
        arguments.report.write_text(
            "# flagged: a row the pack already had, most frequent first\n" +
            "".join(f"{w}\t{frequencies[w]}\t{flag[w]}\n" for w in by_rank) +
            "\n# added: a new row, strongest evidence first\n" +
            "".join(f"{w}\t{add[w]}\n" for w in sorted(add, key=lambda w: -add[w])),
            encoding="utf-8")
        print(f"    review written: {arguments.report}")

    if arguments.apply:
        # Existing rows keep their order and frequency; only the third column moves. Added rows
        # go on the end at the flat frequency, as make_pack.py adds them.
        written = [f"{line}\tname" if word in flag else line for word, line, _ in rows]
        written.extend(f"{word}\t{flat[word]}\tname" for word in sorted(add))
        path.write_text("\n".join(written) + "\n", encoding="utf-8")
        print(f"    written: {path}")
    else:
        print("nothing written -- pass --apply")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
