#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Checks that every engine constant a document names still exists in the code.

A constant is a `k` followed by a capital letter and word characters, written inside backticks
in a Markdown file under `docs/`, as `kCompletionPenalty` is. It exists when some source file
writes it as a word: a C++ file of the engine or the native tests, or a Kotlin file. Each one
named and found nowhere is printed with its file and line; the exit status is then non-zero.
Runs in CI beside the other self-tests.

    python3 tools/check_doc_constants.py
    python3 tools/check_doc_constants.py --selftest
"""

import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# A backticked span of Markdown, and an engine constant's name inside one.
CODE_SPAN = re.compile(r"`([^`\n]+)`")
CONSTANT = re.compile(r"\bk[A-Z]\w*\b")

# Where the documents and the sources live, under the repository root.
DOCS = ("docs",)
SOURCE_GLOBS = ("keyboard/src/main/cpp/**/*.cpp", "keyboard/src/main/cpp/**/*.hpp",
                "native-tests/*.cpp", "native-tests/*.hpp", "**/src/*/java/**/*.kt")


def named_constants(documents: list[Path]) -> list[tuple[Path, int, str]]:
    """Each constant the documents name in a backticked span, with its file and line."""
    found = []
    for document in documents:
        for number, line in enumerate(document.read_text(encoding="utf-8").splitlines(), 1):
            for span in CODE_SPAN.findall(line):
                found.extend((document, number, name) for name in CONSTANT.findall(span))
    return found


def written_words(sources: list[Path]) -> set[str]:
    """Every constant-shaped word the sources write."""
    words: set[str] = set()
    for source in sources:
        words.update(CONSTANT.findall(source.read_text(encoding="utf-8", errors="replace")))
    return words


def missing(root: Path, docs: tuple = DOCS, globs: tuple = SOURCE_GLOBS) -> list[tuple[Path, int, str]]:
    """The named constants no source writes, in document order."""
    documents = sorted(path for folder in docs for path in (root / folder).rglob("*.md"))
    sources = sorted({path for pattern in globs for path in root.glob(pattern) if path.is_file()})
    written = written_words(sources)
    return [entry for entry in named_constants(documents) if entry[2] not in written]


def selftest() -> int:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        (root / "docs").mkdir()
        (root / "src").mkdir()
        (root / "docs" / "a.md").write_text(
            "The weight `kAlive` stays.\n"
            "Two in one span: `kAlive + kGone`, and kBare outside backticks.\n",
            encoding="utf-8")
        (root / "src" / "x.hpp").write_text("constexpr float kAlive = 1.0f;\n", encoding="utf-8")
        found = [(path.name, line, name)
                 for path, line, name in missing(root, ("docs",), ("src/*.hpp",))]
        assert found == [("a.md", 2, "kGone")], found
    print("check_doc_constants selftest: ok")
    return 0


def main() -> int:
    if "--selftest" in sys.argv[1:]:
        return selftest()
    found = missing(ROOT)
    for path, line, name in found:
        print(f"{path.relative_to(ROOT)}:{line}: {name} is named, and no source writes it")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
