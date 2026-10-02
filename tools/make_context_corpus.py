#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Builds the held-out corpora `suggest_eval --context` reads, one per bundled language.

Fetches each language's Universal Dependencies test split at a pinned release, checked against
its SHA-256, and splits every sentence's `# text =` line the way the keyboard reads the words
before the caret (TypingOrchestrator.contextWordsBefore): a word is a run of letters, apostrophes
and hyphens; anything else between two words is passed over, except `.`, `!`, `?` and a line
break, after which the next word has no context. Writes `context_<language>.txt` into the output
directory, one run of words per line, separated by single spaces.

    python3 tools/make_context_corpus.py native-tests/build/context
"""

import argparse
import hashlib
import sys
import unicodedata
import urllib.request
from pathlib import Path

RELEASE = "r2.18"
URL = "https://raw.githubusercontent.com/UniversalDependencies/{repository}/{release}/{file}"

# Language: the treebank, its test split, and that file's SHA-256 at RELEASE.
TREEBANKS = {
    "en": ("UD_English-EWT", "en_ewt-ud-test.conllu",
           "fa024f43dc5da3c5ac02563bc9bd0e974f46cbb1560823976a8f342a37dc494a"),
    "ro": ("UD_Romanian-RRT", "ro_rrt-ud-test.conllu",
           "32a2f7b9fc82c1e82d9959ce8c5ec7b7840f725177aa06b497b51f6834801989"),
    "de": ("UD_German-GSD", "de_gsd-ud-test.conllu",
           "595070aa50b706a91dc66f17c296f7a9a25cbc75269f177c27680fb1c21528ab"),
    "fr": ("UD_French-GSD", "fr_gsd-ud-test.conllu",
           "eee5a599b429658b6ee8582fae9993eb07161247fc64bad57d16b2050ed4eb1a"),
    "es": ("UD_Spanish-GSD", "es_gsd-ud-test.conllu",
           "ecce253f44bffaa9803ae7ec0c10911c13e4f1fc3cb439dbd1db2cebe0a12741"),
    "it": ("UD_Italian-PUD", "it_pud-ud-test.conllu",
           "ad5d302bdfd05194155d9ad43dc54c3cb915076d66b3eb6ef33eca9a11f1372a"),
}

SENTENCE_MARKS = ".!?\n"

# Written as the plain apostrophe.
APOSTROPHES = {"’": "'"}


def fetch(directory, repository, name, sha256):
    """[name] in [directory], downloaded from [repository] at RELEASE when absent, checked."""
    path = directory / name
    if not path.is_file():
        directory.mkdir(parents=True, exist_ok=True)
        url = URL.format(repository=repository, release=RELEASE, file=name)
        print(f"fetching {url}", file=sys.stderr)
        partial = path.with_name(path.name + ".partial")
        with urllib.request.urlopen(url, timeout=600) as response, partial.open("wb") as out:
            while chunk := response.read(1 << 20):
                out.write(chunk)
        partial.replace(path)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != sha256:
        raise SystemExit(f"{path}: SHA-256 {digest} is not the pinned {sha256}")
    return path


def is_word_character(ch):
    """TypingOrchestrator.isWordCharacter: a letter, an apostrophe or a hyphen."""
    return ch.isalpha() or ch == "'" or ch == "-"


def runs(text):
    """The runs of words in `text` that each carry the context into the next."""
    text = unicodedata.normalize("NFC", text)
    for typographic, plain in APOSTROPHES.items():
        text = text.replace(typographic, plain)
    out = []
    current = []
    at = 0
    while at < len(text):
        if is_word_character(text[at]):
            end = at
            while end < len(text) and is_word_character(text[end]):
                end += 1
            current.append(text[at:end])
            at = end
            continue
        if text[at] in SENTENCE_MARKS and current:
            out.append(current)
            current = []
        at += 1
    if current:
        out.append(current)
    return out


def sentences(path):
    """Every `# text =` line of a CoNLL-U file."""
    with open(path, encoding="utf-8") as source:
        for line in source:
            if line.startswith("# text = "):
                yield line[len("# text = "):].rstrip("\n")


def write_corpus(treebank, repository, out_path):
    read = 0
    written = []
    for sentence in sentences(treebank):
        read += 1
        written.extend(runs(sentence))
    words = sum(len(run) for run in written)
    with_context = sum(len(run) - 1 for run in written)
    with open(out_path, "w", encoding="utf-8") as out:
        out.write(f"# The test split of {repository}, release {RELEASE[1:]}, split by "
                  "tools/make_context_corpus.py.\n")
        out.write(f"# {read} sentences, {len(written)} runs, {words} words, "
                  f"{with_context} of them with a word before.\n")
        for run in written:
            out.write(" ".join(run) + "\n")
    print(f"{out_path}: {with_context} words with a word before", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("out", type=Path, help="where the corpora are written")
    parser.add_argument("--cache", type=Path,
                        help="where the treebanks are kept; <out>/treebanks by default")
    parser.add_argument("--language", choices=sorted(TREEBANKS), action="append",
                        help="only this language; repeatable")
    args = parser.parse_args()

    cache = args.cache or args.out / "treebanks"
    args.out.mkdir(parents=True, exist_ok=True)
    for language in args.language or TREEBANKS:
        repository, name, sha256 = TREEBANKS[language]
        treebank = fetch(cache, repository, name, sha256)
        write_corpus(treebank, repository, args.out / f"context_{language}.txt")


if __name__ == "__main__":
    main()
