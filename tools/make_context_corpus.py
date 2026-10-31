#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Builds the held-out corpora `suggest_eval --context` reads, one per bundled language.

Fetches each language's Universal Dependencies test split at a pinned release, checked against
its SHA-256, and splits every sentence's `# text =` line the way the keyboard reads the words
before the caret (TypingOrchestrator.contextWordsBefore): a word is a run of letters, apostrophes
and hyphens; anything else between two words is passed over, except `.`, `!`, `?` and a line
break, after which the next word has no context. Writes `context_<language>.txt` into the output
directory: for each sentence with a word, a line `# sentence <genre> <sent_id>`, then its runs of
words, one per line, separated by single spaces. A treebank with no dev split keeps half its
test split's sentences of each genre for DEV (`halves`; tools/make_dev_corpus.py) and writes the
other half here.

    python3 tools/make_context_corpus.py native-tests/build/context
    python3 tools/make_context_corpus.py --selftest
"""

from __future__ import annotations

import argparse
import hashlib
import sys
import tempfile
import unicodedata
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

sys.path.insert(0, str(Path(__file__).resolve().parent))
import heldout_overlap  # noqa: E402

RELEASE = "r2.18"
URL = "https://raw.githubusercontent.com/UniversalDependencies/{repository}/{release}/{file}"

# The line that opens each sentence's runs; suggest_eval writes its genre and id on every row.
SENTENCE_LINE = "# sentence "


def by_prefix(sent_id: str) -> str:
    """The genre a sent_id opens with, before its first hyphen."""
    return sent_id.split("-", 1)[0]


# A Parallel UD sentence's genre, by its sent_id's first letter.
PUD_GENRES = {"n": "news", "w": "wiki"}


def by_pud_letter(sent_id: str) -> str:
    return PUD_GENRES[sent_id[0]]


def named(genre: str) -> Callable[[str], str]:
    """Every sentence in [genre], for a treebank whose sent_id names none."""
    return lambda _: genre


@dataclass(frozen=True)
class Treebank:
    repository: str
    test: str
    test_sha256: str
    # Each sentence's genre, from its sent_id.
    genre: Callable[[str], str]
    # The dev split and its SHA-256 at RELEASE; without one, half the test split is DEV.
    dev: str | None = None
    dev_sha256: str | None = None


TREEBANKS = {
    "en": Treebank("UD_English-EWT", "en_ewt-ud-test.conllu",
                   "fa024f43dc5da3c5ac02563bc9bd0e974f46cbb1560823976a8f342a37dc494a", by_prefix,
                   "en_ewt-ud-dev.conllu",
                   "39239e0a60db3ae68f4b7036189f11b6692741d10ff8240dd91f74f2760d90f8"),
    "ro": Treebank("UD_Romanian-RRT", "ro_rrt-ud-test.conllu",
                   "32a2f7b9fc82c1e82d9959ce8c5ec7b7840f725177aa06b497b51f6834801989", named("rrt"),
                   "ro_rrt-ud-dev.conllu",
                   "b81e3848e9e9fb06afd32867a8795208edc3305df981aedc361c8273dc84a3bf"),
    "de": Treebank("UD_German-GSD", "de_gsd-ud-test.conllu",
                   "595070aa50b706a91dc66f17c296f7a9a25cbc75269f177c27680fb1c21528ab", named("gsd"),
                   "de_gsd-ud-dev.conllu",
                   "01e8e674973592747ffe9a8c77fcf9d2f5936a8484e731ad4f76254318a8952c"),
    "fr": Treebank("UD_French-GSD", "fr_gsd-ud-test.conllu",
                   "eee5a599b429658b6ee8582fae9993eb07161247fc64bad57d16b2050ed4eb1a", named("gsd"),
                   "fr_gsd-ud-dev.conllu",
                   "9221e5084cc6a1b1540671cfbd2fc0456efc223b2fe8dd2146c2bad921be02d4"),
    "es": Treebank("UD_Spanish-GSD", "es_gsd-ud-test.conllu",
                   "ecce253f44bffaa9803ae7ec0c10911c13e4f1fc3cb439dbd1db2cebe0a12741", named("gsd"),
                   "es_gsd-ud-dev.conllu",
                   "704fb19afc0a34cff476e1a70351b7026c4364f862e8e8388b02873852567c22"),
    "it": Treebank("UD_Italian-PUD", "it_pud-ud-test.conllu",
                   "ad5d302bdfd05194155d9ad43dc54c3cb915076d66b3eb6ef33eca9a11f1372a",
                   by_pud_letter),
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
    """WordShape.isWordCharacter: a letter, an apostrophe or a hyphen."""
    return ch.isalpha() or ch == "'" or ch == "-"


def pieces(text):
    """`text` in NFC with its apostrophes plain, as `(is_word, piece)`: each run of word
    characters, and each other character on its own."""
    text = unicodedata.normalize("NFC", text)
    for typographic, plain in APOSTROPHES.items():
        text = text.replace(typographic, plain)
    at = 0
    while at < len(text):
        if is_word_character(text[at]):
            end = at
            while end < len(text) and is_word_character(text[end]):
                end += 1
            yield True, text[at:end]
            at = end
            continue
        yield False, text[at]
        at += 1


def runs(text):
    """The runs of words in `text` that each carry the context into the next."""
    out = []
    current = []
    for is_word, piece in pieces(text):
        if is_word:
            current.append(piece)
        elif piece in SENTENCE_MARKS and current:
            out.append(current)
            current = []
    if current:
        out.append(current)
    return out


def genre_of(treebank: Treebank, sent_id: str) -> str:
    return treebank.genre(sent_id)


def sentences(path: Path, treebank: Treebank) -> list[tuple[str, str, str]]:
    """Every sentence of a CoNLL-U file as (genre, sent_id, its `# text =` line)."""
    out = []
    sent_id = None
    with open(path, encoding="utf-8") as source:
        for line in source:
            if line.startswith("# sent_id = "):
                sent_id = line[len("# sent_id = "):].strip()
            elif line.startswith("# text = "):
                text = line[len("# text = "):].rstrip("\n")
                out.append((genre_of(treebank, sent_id), sent_id, text))
    return out


def halves(read: list[tuple[str, str, str]]) -> tuple[list, list]:
    """[read] as DEV and TEST: of each genre's sentences, the half with the smallest fingerprints
    is DEV. Both keep [read]'s order."""
    dev = set()
    for genre in {genre for genre, _, _ in read}:
        alike = sorted((heldout_overlap.fingerprint(sentence[2]), sentence)
                       for sentence in read if sentence[0] == genre)
        dev.update(sentence for _, sentence in alike[:len(alike) // 2])
    return ([sentence for sentence in read if sentence in dev],
            [sentence for sentence in read if sentence not in dev])


def test_sentences(language: str, cache: Path) -> list[tuple[str, str, str]]:
    """[language]'s TEST sentences: its treebank's test split, or the half DEV leaves."""
    treebank = TREEBANKS[language]
    read = sentences(fetch(cache, treebank.repository, treebank.test, treebank.test_sha256),
                     treebank)
    return read if treebank.dev else halves(read)[1]


def dev_sentences(language: str, cache: Path) -> list[tuple[str, str, str]]:
    """[language]'s treebank DEV sentences: its dev split, or half its test split; none for a
    language without a treebank here."""
    treebank = TREEBANKS.get(language)
    if treebank is None:
        return []
    if treebank.dev is None:
        read = sentences(fetch(cache, treebank.repository, treebank.test, treebank.test_sha256),
                         treebank)
        return halves(read)[0]
    return sentences(fetch(cache, treebank.repository, treebank.dev, treebank.dev_sha256), treebank)


def write_corpus(read: list[tuple[str, str, str]], source: str, out_path: Path) -> None:
    """Writes each of [read]'s sentences with a word as its sentence line and its runs, under a
    header naming [source]."""
    written = [(genre, sent_id, runs(text)) for genre, sent_id, text in read]
    written = [sentence for sentence in written if sentence[2]]
    every_run = [run for _, _, sentence_runs in written for run in sentence_runs]
    words = sum(len(run) for run in every_run)
    with_context = sum(len(run) - 1 for run in every_run)
    with open(out_path, "w", encoding="utf-8") as out:
        out.write(f"# {source}\n")
        out.write(f"# {len(read)} sentences, {len(every_run)} runs, {words} words, "
                  f"{with_context} of them with a word before.\n")
        for genre, sent_id, sentence_runs in written:
            out.write(f"{SENTENCE_LINE}{genre} {sent_id}\n")
            for run in sentence_runs:
                out.write(" ".join(run) + "\n")
    print(f"{out_path}: {with_context} words with a word before", file=sys.stderr)


def selftest() -> int:
    """Checks each sentence's line and genre, the runs under it, and the split of a treebank
    with no dev split into two halves."""
    assert genre_of(TREEBANKS["en"], "reviews-301610-0004") == "reviews"
    assert genre_of(TREEBANKS["it"], "n01001011") == "news"
    assert genre_of(TREEBANKS["it"], "w01007029") == "wiki"
    assert genre_of(TREEBANKS["de"], "test-s1") == "gsd"
    assert genre_of(TREEBANKS["ro"], "test-1") == "rrt"

    conllu = ("# sent_id = answers-1-0001\n# text = What if Google Morphed? Then what.\n"
              "1\tWhat\n\n# sent_id = email-2-0002\n# text = ’Tis it\n1\t'Tis\n\n"
              "# sent_id = email-2-0003\n# text = ...\n1\t...\n\n")
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "x-ud-test.conllu"
        path.write_text(conllu, encoding="utf-8")
        read = sentences(path, TREEBANKS["en"])
        assert read == [("answers", "answers-1-0001", "What if Google Morphed? Then what."),
                        ("email", "email-2-0002", "’Tis it"),
                        ("email", "email-2-0003", "...")], read
        out = Path(directory) / "context_xx.txt"
        write_corpus(read, "a test", out)
        body = [line for line in out.read_text(encoding="utf-8").splitlines()
                if not line.startswith("# ") or line.startswith(SENTENCE_LINE)]
        assert body == [f"{SENTENCE_LINE}answers answers-1-0001", "What if Google Morphed",
                        "Then what", f"{SENTENCE_LINE}email email-2-0002", "'Tis it"], body

    news = [("news", f"n{index}", f"News sentence {index}.") for index in range(6)]
    wiki = [("wiki", f"w{index}", f"Wiki sentence {index}.") for index in range(4)]
    dev, test = halves(wiki + news)
    flipped = halves(list(reversed(wiki + news)))
    assert [set(half) for half in flipped] == [set(dev), set(test)], "the halves followed the order"
    assert dev == [sentence for sentence in wiki + news if sentence in dev], "not in corpus order"
    assert [genre for genre, _, _ in dev].count("news") == 3, dev
    assert [genre for genre, _, _ in dev].count("wiki") == 2, dev
    assert sorted(dev + test) == sorted(news + wiki) and not set(dev) & set(test)

    # A pinned treebank without a dev split gives DEV one half and TEST the other; one with a
    # dev split gives TEST its whole test split and DEV its dev split.
    def conllu_of(names: list[str]) -> str:
        return "".join(f"# sent_id = {name}\n# text = Words of {name} here.\n1\tWords\n\n"
                       for name in names)
    with tempfile.TemporaryDirectory() as directory:
        cache = Path(directory)
        halved = [f"n{i}" for i in range(4)] + [f"w{i}" for i in range(4)]
        texts = {"x-test.conllu": conllu_of(halved),
                 "y-test.conllu": conllu_of(["a-1", "b-2"]), "y-dev.conllu": conllu_of(["c-3"])}
        pins = {}
        for name, text in texts.items():
            (cache / name).write_text(text, encoding="utf-8")
            pins[name] = hashlib.sha256(text.encode("utf-8")).hexdigest()
        TREEBANKS["xx"] = Treebank("UD_X", "x-test.conllu", pins["x-test.conllu"], by_pud_letter)
        TREEBANKS["yy"] = Treebank("UD_Y", "y-test.conllu", pins["y-test.conllu"], by_prefix,
                                   "y-dev.conllu", pins["y-dev.conllu"])
        try:
            test, dev = test_sentences("xx", cache), dev_sentences("xx", cache)
            assert len(test) == len(dev) == 4 and not set(test) & set(dev), (test, dev)
            assert sorted(genre for genre, _, _ in dev) == ["news", "news", "wiki", "wiki"], dev
            assert [i for _, i, _ in test_sentences("yy", cache)] == ["a-1", "b-2"]
            assert dev_sentences("yy", cache) == [("c", "c-3", "Words of c-3 here.")]
            assert dev_sentences("zz", cache) == []
        finally:
            del TREEBANKS["xx"], TREEBANKS["yy"]
    print("make_context_corpus selftest: ok")
    return 0


def main():
    if "--selftest" in sys.argv[1:]:
        return selftest()
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
        treebank = TREEBANKS[language]
        half = "" if treebank.dev else ", the half DEV does not read"
        source = (f"The test split of {treebank.repository}, release {RELEASE[1:]}{half}, split by "
                  "tools/make_context_corpus.py.")
        write_corpus(test_sentences(language, cache), source, args.out / f"context_{language}.txt")


if __name__ == "__main__":
    sys.exit(main())
