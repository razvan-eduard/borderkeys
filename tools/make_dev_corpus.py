#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Builds the dev corpora the language model's constants are chosen on, one per language whose
manifest names a held-out corpus.

A language is taken as tools/heldout_overlap.py admits it. Of its held-out Leipzig file, the
sentences of the split's dev part are kept (heldout_overlap.kept: no counted corpus writes them,
each once), and of those the DEV_SENTENCES with the smallest fingerprints, so the choice does not
depend on the file's order. After them come the
DEV sentences of the language's test treebank (tools/make_context_corpus.py: its dev split, or
the half of its test split TEST leaves). Each sentence is split into runs as
tools/make_context_corpus.py splits the test text, under its sentence line: a held-out sentence's
genre is its Leipzig corpus and its id its fingerprint. `dev_<language>.txt` is written into the
output directory in the format `suggest_eval --context` reads.

Of the same held-out sentences, `corrections_<language>.tsv` holds the correction rows, drawn as
tools/make_personal_corpus.py draws its rows: each ordinary word of the list after the two words
before it, typed as written and with a slip, omitted, extra or midtypo typo that is not a word of
the list, ROWS_PER_KIND words per kind. For a language with a persona, `personal_dev_<language>.tsv`
is a provisional personal DEV from the persona's personal part, typed after the TEST history: rows
drawn the same way from the persona's candidate rows, none with a TEST row's word or a TEST row's
context, until a second held-out edition gives the personal DEV a part of its own. Both are
`typed<TAB>meant<TAB>kind<TAB>previous2 previous1`, the rows CorrectionDevReadingsTest types
through the app's own commit.

    python3 tools/make_dev_corpus.py native-tests/build/dev --cache ~/.cache/borderkeys/leipzig-pinned
    python3 tools/make_dev_corpus.py native-tests/build/dev --tag en_US
    python3 tools/make_dev_corpus.py --selftest
"""

import argparse
import heapq
import random
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import heldout_overlap  # noqa: E402
import language_manifest  # noqa: E402
import make_context_corpus  # noqa: E402
import make_personal_corpus  # noqa: E402

# The held-out sentences kept per language: about four times the words of the largest test
# corpus, few enough that one measurement of one language takes about a minute and a half.
DEV_SENTENCES = 4000


def chosen(lines, excluded: set, split: dict, count: int = DEV_SENTENCES) -> list[str]:
    """The [count] sentences of the dev part of [split] of the Leipzig `id<TAB>sentence` [lines]
    with the smallest fingerprints, as heldout_overlap.kept gives the part; in fingerprint
    order."""
    # The largest kept fingerprint on top, negated, so it is the one a smaller one replaces.
    heap = []
    for mark, text in heldout_overlap.kept(lines, excluded, split, language_manifest.DEV_PART):
        entry = (-int.from_bytes(mark, "big"), text)
        if len(heap) < count:
            heapq.heappush(heap, entry)
        elif entry[0] > heap[0][0]:
            heapq.heapreplace(heap, entry)
    return [text for _, text in sorted(heap, reverse=True)]


def write_dev(sentences: list[str], heldout: str, treebank: list[tuple[str, str, str]],
              source: str, out_path: Path) -> None:
    """Writes the held-out [sentences], each in genre [heldout], then the [treebank] sentences,
    as make_context_corpus writes a corpus, under a header naming [source]."""
    held = [(heldout, heldout_overlap.fingerprint(text).hex(), text) for text in sentences]
    make_context_corpus.write_corpus(held + treebank, f"{source}, chosen and split by "
                                     "tools/make_dev_corpus.py.", out_path)


def corrections(sentences: list[str], words: make_personal_corpus.Lexicon,
                per_kind: int = make_personal_corpus.ROWS_PER_KIND) -> list[tuple]:
    """The correction rows of the held-out [sentences]: make_personal_corpus's candidate rows of
    them, each word as [words] spells it, and its draw of [per_kind] words per typo kind."""
    candidates = make_personal_corpus.candidate_rows(sentences, words.spell, words.ordinary,
                                                     words.eligible, words.words)
    return make_personal_corpus.drawn(candidates, per_kind)


def personal_dev(candidates: list[tuple], rows: list[tuple],
                 per_kind: int = make_personal_corpus.ROWS_PER_KIND) -> list[tuple]:
    """The personal DEV rows: make_personal_corpus's draw of [per_kind] words per typo kind from
    the [candidates] whose word is no TEST row's word and whose context is no TEST row's context,
    the TEST rows being [rows]."""
    words = {meant for _, meant, _, _ in rows}
    contexts = {context for _, _, _, context in rows}
    left = [row for row in candidates if row[1] not in words and row[3] not in contexts]
    return make_personal_corpus.drawn(left, per_kind)


def build(tag: str, out: Path, cache: Path, treebanks: Path) -> bool:
    """Writes [tag]'s dev corpus into [out], its treebank's DEV sentences fetched into
    [treebanks]; false when its held-out file is refused."""
    manifest = language_manifest.load(tag)
    found = heldout_overlap.verdict(manifest)
    if not found.admitted:
        for refusal in found.refusals:
            print(f"{tag} refused: {refusal}", file=sys.stderr)
        return False
    overlap = heldout_overlap.shared(manifest, cache)
    excluded = set().union(*overlap.values())
    path = heldout_overlap.heldout_sentences(manifest, cache)
    with path.open(encoding="utf-8", errors="replace") as lines:
        sentences = chosen(lines, excluded, manifest.heldout_split)
    pin = manifest.sha256.get(language_manifest.archive_name(manifest.heldout), "unpinned")
    language = tag.split("_")[0]
    treebank = make_context_corpus.dev_sentences(language, treebanks)
    share = manifest.heldout_split[language_manifest.DEV_PART]
    source = (f"{len(sentences)} sentences of the dev part ({share} of "
              f"{language_manifest.SPLIT_UNITS} by fingerprint) of {manifest.heldout} (SHA-256 "
              f"{pin}) that none of {', '.join(manifest.counted)} writes ({len(excluded)} left "
              f"out), then {len(treebank)} DEV sentences of the test treebank")
    write_dev(sentences, manifest.heldout, treebank, source, out / f"dev_{language}.txt")

    words = make_personal_corpus.lexicon(tag)
    kinds = ", ".join(kind for kind, _ in make_personal_corpus.TYPOS)
    rows = corrections(sentences, words)
    make_personal_corpus.write_rows(
        out / f"corrections_{language}.tsv",
        f"Correction rows for {tag} from the {len(sentences)} held-out sentences of "
        f"dev_{language}.txt: {make_personal_corpus.ROWS_PER_KIND} words per typo kind ({kinds}) "
        f"after their two words, drawn as tools/make_personal_corpus.py draws its rows, each typed "
        f"as written and with that typo. Generated by tools/make_dev_corpus.py.", rows)
    if manifest.persona:
        corpus = make_personal_corpus.personal(manifest, overlap, cache, words)
        dev_rows = personal_dev(corpus.candidates, corpus.rows)
        make_personal_corpus.write_rows(
            out / f"personal_dev_{language}.tsv",
            f"Provisional personal DEV rows for {tag}, from the personal part, typed after "
            f"personal_history_{language}.txt, the TEST history: of the {len(corpus.candidates)} "
            f"candidate rows of the sentences mentioning \"{corpus.persona}\" on the rows side, "
            f"those whose word is no word and whose context is no context of "
            f"personal_rows_{language}.tsv, {make_personal_corpus.ROWS_PER_KIND} words per typo "
            f"kind ({kinds}) drawn as tools/make_personal_corpus.py draws its rows. Generated by "
            f"tools/make_dev_corpus.py.", dev_rows)
    return True


def selftest() -> int:
    """Checks the correction rows: only from the dev part, each typo its kind's draw and never a
    word of the list, the same whatever the file's order, written as the personal rows are; the
    personal DEV rows taking no TEST row's word and no TEST row's context. Then the choice: only
    sentences of the split's dev part, the ones with the smallest fingerprints there, the same
    whatever the file's order, a sentence a counted corpus writes left out with the count still
    kept, a sentence written twice kept once; and that the file holds each held-out sentence,
    then each treebank sentence, under its sentence line."""
    split = {"dev": 300, "personal": 300, "case": 400}

    class Words:
        # The slip of "question" (make_personal_corpus.SELFTEST_TYPOS) is a word of this list.
        words = {"question", "answer", "quextion"}

        @classmethod
        def is_word(cls, text):
            return text in cls.words

    ordinary = {"question", "answer"}
    lexicon = make_personal_corpus.Lexicon(
        words=Words, spell=lambda word: word, ordinary=ordinary,
        eligible={kind: ordinary for kind, _ in make_personal_corpus.TYPOS})
    # Each sentence's mark, its number in letters, is the word before each of its two words.
    codes = ["".join(chr(ord("a") + int(digit)) for digit in str(n)) for n in range(200)]
    texts = [f"Said {code} question for {code} answer." for code in codes]
    numbered = [f"{n}\t{text}\n" for n, text in enumerate(texts)]
    in_dev = {code for code, text in zip(codes, texts)
              if heldout_overlap.part_of(heldout_overlap.fingerprint(text), split) == "dev"}
    rows = corrections(chosen(numbered, set(), split, len(numbered)), lexicon, 10)
    assert rows and all(context.split(" ")[1] in in_dev for _, _, _, context in rows), \
        "a correction row from outside the dev part"
    typos = [row for row in rows if row[2] != make_personal_corpus.CLEAN]
    assert len(typos) == 10 * len(make_personal_corpus.TYPOS), typos
    assert not any(Words.is_word(typed) for typed, _, _, _ in typos), "a typo is a word of the list"
    modules = dict(make_personal_corpus.TYPOS)
    assert all(typed == make_personal_corpus.typo(meant, modules[kind], kind, Words)
               for typed, meant, kind, _ in typos), typos
    reordered = numbered[:]
    random.Random(11).shuffle(reordered)
    assert corrections(chosen(reordered, set(), split, len(reordered)), lexicon, 10) == rows, \
        "the correction rows followed the file's order"
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "corrections_xx.tsv"
        make_personal_corpus.write_rows(path, "A test.", rows)
        written = [line.split("\t") for line in path.read_text(encoding="utf-8").splitlines()
                   if not line.startswith("#")]
        assert [tuple(fields) for fields in written] == rows, written

    # Candidates whose words each follow many contexts, and whose contexts each come before many
    # words.
    candidates = []
    for n in range(200):
        meant, context = f"word{n % 41}", f"before {n % 37}"
        candidates.append((meant, meant, make_personal_corpus.CLEAN, context))
        candidates += [(f"{kind}{n}", meant, kind, context) for kind, _ in make_personal_corpus.TYPOS]
    test_rows = make_personal_corpus.drawn(candidates, 3)
    dev_rows = personal_dev(candidates, test_rows, 3)
    assert len(dev_rows) == len(test_rows), dev_rows
    assert not {row[1] for row in test_rows} & {row[1] for row in dev_rows}, \
        "a personal DEV row takes a TEST row's word"
    assert not {row[3] for row in test_rows} & {row[3] for row in dev_rows}, \
        "a personal DEV row takes a TEST row's context"

    lines = [f"{i}\tSentence number {i} is here.\n" for i in range(200)]
    first = chosen(lines, set(), split, 5)
    marks = [heldout_overlap.fingerprint(text) for text in first]
    assert all(heldout_overlap.part_of(mark, split) == "dev" for mark in marks), first
    in_part = sorted(mark for mark in (heldout_overlap.fingerprint(heldout_overlap.sentence_text(
        line)) for line in lines) if heldout_overlap.part_of(mark, split) == "dev")
    assert marks == in_part[:5], "not the dev part's smallest fingerprints"
    assert chosen(lines, set(), split, len(lines)) == chosen(lines, set(), split, len(in_part))
    shuffled = lines[:]
    random.Random(7).shuffle(shuffled)
    assert chosen(shuffled, set(), split, 5) == first, "the choice followed the file's order"
    assert len(first) == 5

    smallest = heldout_overlap.fingerprint(first[0])
    without = chosen(lines, {smallest}, split, 5)
    assert first[0] not in without and len(without) == 5, without

    doubled = lines + [f"{i + 300}\t{first[1]}\n" for i in range(3)]
    assert chosen(doubled, set(), split, 5) == first, "a sentence written twice was kept twice"

    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "dev_xx.txt"
        sentences = ["Ana are mere. Apoi pleacă!", "Two words"]
        treebank = [("rrt", "dev-1", "Trei cuvinte aici.")]
        write_dev(sentences, "ron_news_2023_1M", treebank, "a test", path)
        line = make_context_corpus.SENTENCE_LINE
        body = [text for text in path.read_text(encoding="utf-8").splitlines()
                if not text.startswith("#") or text.startswith(line)]
        marks = [heldout_overlap.fingerprint(sentence).hex() for sentence in sentences]
        assert body == [f"{line}ron_news_2023_1M {marks[0]}", "Ana are mere", "Apoi pleacă",
                        f"{line}ron_news_2023_1M {marks[1]}", "Two words",
                        f"{line}rrt dev-1", "Trei cuvinte aici"], body
    print("make_dev_corpus selftest: ok")
    return 0


def main() -> int:
    if "--selftest" in sys.argv[1:]:
        return selftest()
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("out", type=Path, help="where the dev corpora are written")
    parser.add_argument("--tag", action="append",
                        help="only this language, e.g. en_US; repeatable")
    parser.add_argument("--cache", type=Path, default=language_manifest.CACHE)
    parser.add_argument("--treebanks", type=Path,
                        help="where the treebanks are kept; <out>/treebanks by default")
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    refused = 0
    for tag in args.tag or language_manifest.with_key("heldout"):
        treebanks = args.treebanks or args.out / "treebanks"
        refused += 0 if build(tag, args.out, args.cache, treebanks) else 1
    return 1 if refused else 0


if __name__ == "__main__":
    sys.exit(main())
