#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Writes the words a bundled pack knows but never offers: words its language's Leipzig corpora
write that the pack's list lacks, which one of the language's Hunspell dictionaries accepts as
written, or capitalised where it accepts nouns only so. The keyboard counts one as spelled once
it is as common as the "Rare words" setting reaches; it never suggests, completes or corrects to
one.

Kept: written at least twice, three letters or more, typeable on the language's keyboard, a
folded key the list does not hold even with its apostrophes and hyphens set aside and ß written
ss, not in the language's misspellings, exclusion or offensive lists. One spelling per folded
key, the most frequent. Each row is `word<TAB>count`, after a `# tokens<TAB>N` line giving how
many words the counts are out of. Compiled by build_dict.py --known.

    python3 tools/make_known_words.py en_US > dictionaries/en_US.known
"""

import argparse
import collections
import contextlib
import pathlib
import shutil
import sys
import tarfile

import classify_wordlist
import make_pack
import new_language
from build_dict import fold_word
from make_unknown_corpus import packed_keys

REPOSITORY = pathlib.Path(__file__).resolve().parent.parent

# The Leipzig corpora each bundled list's extra words are counted from: the newest wikipedia,
# news and newscrawl collection of a million sentences each.
CORPORA = {
    "en_US": ("eng_wikipedia_2016_1M", "eng_news_2025_1M", "eng_newscrawl_2018_1M"),
    "ro_RO": ("ron_wikipedia_2021_1M", "ron_news_2024_1M", "ron_newscrawl_2015_1M"),
    "de_DE": ("deu_wikipedia_2021_1M", "deu_news_2025_1M", "deu_newscrawl_2020_1M"),
    "fr_FR": ("fra_wikipedia_2021_1M", "fra_news_2024_1M", "fra_newscrawl_2020_1M"),
    "es_ES": ("spa_wikipedia_2021_1M", "spa_news_2024_1M", "spa_newscrawl_2021_1M"),
    "it_IT": ("ita_wikipedia_2021_1M", "ita_news_2024_1M", "ita_newscrawl_2020_1M"),
}

MIN_LENGTH = 3
MIN_COUNT = 2


def sentences(name: str, cache: pathlib.Path) -> pathlib.Path:
    """The sentence file of Leipzig corpus `name`, fetched into `cache` when absent."""
    target = cache / f"{name}-sentences.txt"
    if not target.is_file():
        archive = cache / f"{name}.tar.gz"
        with contextlib.redirect_stdout(sys.stderr):
            new_language.download(new_language.LEIPZIG.format(name=name), archive)
        with tarfile.open(archive) as tar:
            member = next(m for m in tar.getmembers() if m.name.endswith("-sentences.txt"))
            with tar.extractfile(member) as source, target.open("wb") as out:
                shutil.copyfileobj(source, out, length=1 << 20)
        archive.unlink()
    return target


def count_words(paths: list[pathlib.Path], alphabet) -> collections.Counter:
    """How often each word is written in the sentences of `paths`, lower-cased."""
    counts: collections.Counter = collections.Counter()
    for path in paths:
        with path.open(encoding="utf-8", errors="replace") as handle:
            for line in handle:
                counts.update(make_pack.tokenise(line.split("\t", 1)[-1], alphabet))
    return counts


def bare(key: tuple[int, ...]) -> tuple[int, ...]:
    """A folded key with its apostrophes and hyphens set aside and ß written ss."""
    out: list[int] = []
    for code in key:
        if chr(code) in "'-":
            continue
        out.extend((ord("s"), ord("s")) if chr(code) == "ß" else (code,))
    return tuple(out)


def spelling_forms(tag: str) -> list:
    """The case forms in which a spelling dictionary accepting a word makes it a word of the
    language: as written, and capitalised where the dictionaries accept nouns only so."""
    forms = [lambda word: word]
    if tag in classify_wordlist.CAPITALISES_EVERY_NOUN:
        forms.append(lambda word: word[:1].upper() + word[1:])
    return forms


def left_out(tag: str) -> set[str]:
    """The folded keys of the language's misspellings, exclusions and offensive words."""
    paths = [REPOSITORY / "dictionaries" / f"{tag}.{kind}" for kind in ("misspellings", "words-exclude")]
    paths.append(REPOSITORY / "keyboard/src/main/assets/offensive" / f"{tag.replace('_', '-')}.txt")
    return {fold_word(word) for path in paths if path.is_file() for word in make_pack.read_word_list(path)}


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("list", choices=sorted(CORPORA))
    parser.add_argument("--leipzig", type=pathlib.Path,
                        default=pathlib.Path.home() / ".cache" / "borderkeys" / "leipzig")
    parser.add_argument("--evidence", type=pathlib.Path,
                        default=pathlib.Path.home() / ".cache" / "borderkeys" / "word-evidence")
    args = parser.parse_args()

    args.leipzig.mkdir(parents=True, exist_ok=True)
    corpora = CORPORA[args.list]
    counts = count_words([sentences(name, args.leipzig) for name in corpora],
                         make_pack.alphabet_for(args.list.replace("_", "-")))
    tokens = sum(counts.values())

    listed = {bare(key)
              for key in packed_keys(REPOSITORY / "dictionaries" / f"{args.list}.tsv", fold_word)}
    excluded = left_out(args.list)
    best: dict[tuple[int, ...], str] = {}
    for word, count in counts.items():
        key = fold_word(word)
        if (count < MIN_COUNT or len(word) < MIN_LENGTH or bare(key) in listed
                or key in excluded):
            continue
        if key not in best or count > counts[best[key]]:
            best[key] = word
    candidates = sorted(best.values())
    accepted: set[str] = set()
    sources = classify_wordlist.SOURCES[args.list]
    for dictionary in classify_wordlist.spelling_dictionaries(args.evidence, sources):
        for form in spelling_forms(args.list):
            accepted |= classify_wordlist.hunspell_accepted(candidates, dictionary, form)
    kept = sorted((word for word in candidates if word in accepted), key=lambda w: (-counts[w], w))

    spelling = ", ".join(path.split("/")[-1] for path in sources.spelling)
    print("# SPDX-" "License-Identifier: CC-BY-4.0")
    print("#")
    print(f"# Words the {args.list} pack knows but never offers: written in the Wortschatz Leipzig")
    print(f"# corpora {', '.join(corpora)} (CC BY 4.0), missing from {args.list}.tsv, and accepted")
    print(f"# as written by the {spelling} spelling dictionaries. Generated by")
    print("# tools/make_known_words.py.")
    print(f"# tokens\t{tokens}")
    for word in kept:
        print(f"{word}\t{counts[word]}")
    print(f"{len(kept):,} known words from {tokens:,} tokens", file=sys.stderr)


if __name__ == "__main__":
    main()
