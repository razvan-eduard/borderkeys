#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Writes every apostrophe in a language's lists as the plain one and merges the rows that then agree.

The keyboard types the plain apostrophe, and the pack compiler folds the typographic apostrophes
and the modifier letter apostrophe onto it, so a word holding one is one row of a list however
the corpus wrote it. Given the word list, every sibling file of the same language is rewritten
with it:

  * <tag>.tsv      the counts of rows that now agree are added; the name flag stands when either had it
  * <tag>.ngrams   the same for the pair and triple counts
  * <tag>.pos      the plain spelling's tag stands where both spellings were tagged
  * <tag>.names-ordinary, .names-exclude, .names-include, .misspellings, .nonwords
                   one line per word, comments kept, a word that now repeats an earlier one dropped

    python3 tools/merge_apostrophes.py dictionaries/en_US.tsv [--dry-run]
    python3 tools/merge_apostrophes.py --selftest
"""

import argparse
import json
import sys
from pathlib import Path

from build_dict import plain_apostrophes

SIBLINGS = (".ngrams", ".pos", ".names-ordinary", ".names-exclude", ".names-include",
            ".misspellings", ".nonwords")


def merge_words(text: str) -> tuple[str, int, int]:
    """The word list with one row per spelling, each in the place of its first row.

    Returns the text, the rows rewritten and the rows merged into an earlier one.
    """
    rows: dict[str, list] = {}
    rewritten = merged = 0
    for line in text.splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        word = plain_apostrophes(parts[0])
        rewritten += word != parts[0]
        count = int(parts[1])
        extra = parts[2:]
        row = rows.get(word)
        if row is None:
            rows[word] = [count, extra]
        else:
            merged += 1
            row[0] += count
            row[1] += [column for column in extra if column not in row[1]]
    return ("".join("\t".join([word, str(count), *extra]) + "\n" for word, (count, extra) in rows.items()),
            rewritten, merged)


def merge_ngrams(text: str) -> tuple[str, int, int]:
    """The pair and triple counts with one row per spelling, each in the place of its first row."""
    rows: dict[tuple, int] = {}
    rewritten = merged = 0
    for line in text.splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        key = tuple(plain_apostrophes(part) for part in parts[:-1])
        rewritten += key != tuple(parts[:-1])
        count = int(parts[-1])
        if key in rows:
            merged += 1
            rows[key] += count
        else:
            rows[key] = count
    return "".join("\t".join(key) + f"\t{count}\n" for key, count in rows.items()), rewritten, merged


def merge_grammar(text: str) -> tuple[str, int, int]:
    """The grammar with its tags keyed by the plain spelling."""
    grammar = json.loads(text)
    tags: dict[str, int] = {}
    rewritten = merged = 0
    for word, tag in grammar["tags"].items():
        plain = plain_apostrophes(word)
        rewritten += plain != word
        if plain in tags:
            merged += 1
            if plain != word:
                continue
        tags[plain] = tag
    grammar["tags"] = tags
    return json.dumps(grammar, ensure_ascii=False), rewritten, merged


def merge_lines(text: str) -> tuple[str, int, int]:
    """A hand list, one word a line with an optional comment, each word once."""
    seen: set[str] = set()
    kept: list[str] = []
    rewritten = merged = 0
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            kept.append(line)
            continue
        word = stripped.split()[0]
        plain = plain_apostrophes(word)
        rewritten += plain != word
        if plain in seen:
            merged += 1
            continue
        seen.add(plain)
        kept.append(line.replace(word, plain, 1))
    return "".join(f"{line}\n" for line in kept), rewritten, merged


def merger_for(path: Path):
    if path.suffix == ".tsv":
        return merge_words
    if path.suffix == ".ngrams":
        return merge_ngrams
    if path.suffix == ".pos":
        return merge_grammar
    return merge_lines


def merge_language(words: Path, dry_run: bool) -> int:
    """Rewrites the word list and every sibling file it has. Returns the rows merged in all."""
    total = 0
    for path in [words, *(words.with_suffix(suffix) for suffix in SIBLINGS)]:
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        merged_text, rewritten, merged = merger_for(path)(text)
        total += merged
        print(f"{path.name}: {rewritten} rows rewritten, {merged} merged into another")
        if not dry_run and merged_text != text:
            path.write_text(merged_text, encoding="utf-8")
    return total


def selftest() -> None:
    words, rewritten, merged = merge_words("don’t\t3\nit's\t2\nthat's\t2\tname\ndon't\t2\nthat’s\t1\n")
    assert words == "don't\t5\nit's\t2\nthat's\t3\tname\n", words
    assert (rewritten, merged) == (2, 2), (rewritten, merged)

    ngrams, rewritten, merged = merge_ngrams("i\tdon’t\t4\ni\tdon't\t3\n\x02start\tit’s\t2\n")
    assert ngrams == "i\tdon't\t7\n\x02start\tit's\t2\n", ngrams
    assert (rewritten, merged) == (2, 1), (rewritten, merged)

    grammar, rewritten, merged = merge_grammar(
        """{"tag_count": 2, "tagset": ["A", "B"], "tags": {"don’t": 1, "don't": 0, "it’s": 1}, "transitions": ""}""")
    assert json.loads(grammar)["tags"] == {"don't": 0, "it's": 1}, grammar
    assert (rewritten, merged) == (2, 1), (rewritten, merged)

    lines, rewritten, merged = merge_lines("# kept\nteh   # the\no’brien\no'brien\n")
    assert lines == "# kept\nteh   # the\no'brien\n", lines
    assert (rewritten, merged) == (1, 1), (rewritten, merged)
    print("selftest ok: word list, n-grams, grammar and hand lists merge onto the plain apostrophe")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("words", nargs="?", type=Path, help="the language's dictionaries/<tag>.tsv")
    parser.add_argument("--dry-run", action="store_true", help="report without writing")
    parser.add_argument("--selftest", action="store_true")
    arguments = parser.parse_args()
    if arguments.selftest:
        selftest()
        return 0
    if arguments.words is None:
        parser.error("the word list is required")
    if not arguments.words.is_file():
        raise SystemExit(f"merge_apostrophes: {arguments.words} is not a file")
    merge_language(arguments.words, arguments.dry_run)
    return 0


if __name__ == "__main__":
    sys.exit(main())
