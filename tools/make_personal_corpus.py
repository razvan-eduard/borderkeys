#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Builds a personal-history corpus from a language's held-out Leipzig sentences.

The held-out file and the corpora the language's lists are counted from are its manifest's
(language_manifest.py), each checked against its SHA-256; the language must be admitted by
heldout_overlap.py, and only the personal part of its held-out split is read
(heldout_overlap.kept: no counted corpus writes those sentences, each read once). Of those, the
persona is every sentence that mentions the name the list flags that those sentences write most,
which must be the manifest's `persona`. Romanian words are spelled as normalise_diacritics.py
spells the list.

Each persona sentence goes to the history or to the rows by a hash of its normalised text, the
history taking HISTORY_SIDES of SIDES, so a sentence keeps its side whatever else the file holds.
The history is its sentences' runs of words (make_context_corpus.runs), one per line, each word
spelled as the list spells it. The rows side gives candidate rows: each of the list's ordinary
words with two words before it in its run, typed as written, and typed with the typo of the slip,
omitted, extra and midtypo corpora where its length suits that corpus, the typo not a word of the
list, each word and context once. Each typo is drawn from a seed made of that corpus's own seed,
its kind and the word. The candidates carry no class. The rows are drawn from the candidates: for
each typo kind in turn, the ROWS_PER_KIND words with their context that have a typo of that kind
and were not drawn for an earlier kind, by a hash of the word and its context, each typed as
written and with that typo.

Writes `personal_history_<language>.txt`, `personal_candidates_<language>.tsv` and
`personal_rows_<language>.tsv`, both `typed<TAB>meant<TAB>kind<TAB>previous2 previous1`, into
--out; the history and the rows are the corpus PersonalCorpusTest reads.

    python3 tools/make_personal_corpus.py en_US --cache ~/.cache/borderkeys/leipzig-pinned
    python3 tools/make_personal_corpus.py --selftest
"""

import argparse
import hashlib
import random
import sys
import unicodedata
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import corpus_words  # noqa: E402
import heldout_overlap  # noqa: E402
import language_manifest  # noqa: E402
import make_extra_corpus  # noqa: E402
import make_midtypo_corpus  # noqa: E402
import make_omitted_corpus  # noqa: E402
import make_slip_corpus  # noqa: E402
import normalise_diacritics  # noqa: E402
from make_context_corpus import runs  # noqa: E402

REPOSITORY = Path(__file__).resolve().parent.parent

# The split's seed: a sentence's side is drawn from it and the sentence's text, and a row's draw
# from it, the word and its context.
SEED = 20261011

# The bytes of a hash a side or a typo seed is drawn from.
HASH_BYTES = 8

# How many sides a sentence is split between, and how many of them are the history's.
SIDES = 10
HISTORY_SIDES = 9

# A row's word has its previous2 and previous1 in its run.
WORDS_BEFORE = 2

# The kind of a row typed as written.
CLEAN = "clean"

# The words the rows take per typo kind, each typed as written and with that kind's typo.
ROWS_PER_KIND = 150

# A sentence's side, a word's typos by kind, and the rows drawn from the selftest's candidates,
# as every run and machine draws them.
SELFTEST_SIDES = (False, True, True, True, True, True, True, True)
SELFTEST_TYPOS = ("quextion", "questin", "questioyn", "quetsio")
SELFTEST_ROWS = ("word31", "slip31", "word37", "slip37", "word5", "slip5", "word13", "extra13",
                 "word15", "omitted15", "word6", "omitted6", "word35", "omitted35", "word28",
                 "extra28", "word25", "extra25", "word14", "midtypo14", "word29", "midtypo29",
                 "word0", "midtypo0")

# The typo corpora whose typos the candidates take.
TYPOS = (
    ("slip", make_slip_corpus),
    ("omitted", make_omitted_corpus),
    ("extra", make_extra_corpus),
    ("midtypo", make_midtypo_corpus),
)

# The tag whose text is spelled through normalise_diacritics.
ROMANIAN = "ro_RO"

DEFAULT_OUT = REPOSITORY / "build" / "personal-corpus"


def hashed(*parts) -> int:
    """A number drawn from [parts], the same on every run and machine."""
    text = "\t".join(str(part) for part in parts)
    digest = hashlib.blake2b(text.encode("utf-8"), digest_size=HASH_BYTES).digest()
    return int.from_bytes(digest, "big")


def history_side(text: str) -> bool:
    """Whether the sentence [text] goes to the history rather than the rows."""
    return hashed(SEED, heldout_overlap.normalise(text)) % SIDES < HISTORY_SIDES


def split(sentences) -> tuple[list, list]:
    """[sentences] into the history's and the rows', each in the order given."""
    history = [text for text in sentences if history_side(text)]
    rows_side = [text for text in sentences if not history_side(text)]
    return history, rows_side


def speller(tag, spelled, names):
    """How [tag]'s text is spelled as its list spells it: Romanian through normalise_diacritics."""
    if tag != ROMANIAN:
        return lambda word: word
    return lambda word: normalise_diacritics.normalised(word, spelled, word in names)[0]


@dataclass(frozen=True)
class Lexicon:
    """A language's list as the rows are drawn from it: [words] tells a word of the list
    (`is_word`), [spell] spells a word as the list does, [ordinary] holds its ordinary words and
    [eligible] those each typo kind admits, by kind."""
    words: object
    spell: object
    ordinary: set
    eligible: dict
    names: frozenset = frozenset()


def lexicon(tag) -> Lexicon:
    """[tag]'s shipped list, `dictionaries/<tag>.tsv`, as the rows are drawn from it."""
    words = corpus_words.load(list_path(tag))
    names = frozenset(word for word, name in words.rows if name)
    spelled = {word for word, _ in words.rows}
    eligible = {kind: set(words.ordinary(None, module.MIN_LENGTH, module.MAX_LENGTH))
                for kind, module in TYPOS}
    return Lexicon(words, speller(tag, spelled, names), set(words.ordinary()), eligible, names)


def list_path(tag) -> Path:
    """[tag]'s shipped list."""
    return REPOSITORY / "dictionaries" / f"{tag}.tsv"


def spelled_runs(text, spell):
    """[text]'s runs, each word as [spell] spells it."""
    return [[spell(word) for word in run] for run in runs(text)]


def history_runs(sentences, spell):
    """The runs of words of [sentences], in order, each word as [spell] spells it."""
    return [run for text in sentences for run in spelled_runs(text, spell)]


def named_sentences(lines, excluded, heldout_split, spell, names):
    """The sentences of the personal part of [heldout_split] of the Leipzig `id<TAB>sentence`
    [lines], as heldout_overlap.kept gives the part, each with the words of [names] it writes as
    [spell] spells them."""
    return [(text, {word for run in spelled_runs(text, spell) for word in run if word in names})
            for _, text in heldout_overlap.kept(lines, excluded, heldout_split,
                                                language_manifest.PERSONAL_PART)]


def checked_persona(found: str, manifest: language_manifest.Manifest) -> str:
    if found != manifest.persona:
        raise SystemExit(f"{manifest.path.name}: the persona is {manifest.persona!r}, "
                         f"the sentences make it {found!r}")
    return found


def typo(word, module, kind, words):
    """[word] with [module]'s typo drawn from [module]'s seed, [kind] and the word, or None when
    [module] gives none, the typo leaves the word unchanged, or the typo is a word of the list."""
    case = module.mangled(word, random.Random(hashed(module.SEED, kind, word)))
    if case is None or case == word or words.is_word(case):
        return None
    return case


def candidate_rows(sentences, spell, ordinary, eligible, words):
    """The candidate rows of [sentences]: each of [ordinary] after two words in its run, as
    written and with each typo whose corpus admits it ([eligible] by kind), each word and context
    once."""
    rows = []
    seen = set()
    for text in sentences:
        for run in spelled_runs(text, spell):
            for at in range(WORDS_BEFORE, len(run)):
                word = run[at]
                if word not in ordinary:
                    continue
                previous2, previous1 = run[at - WORDS_BEFORE:at]
                if (word, previous2, previous1) in seen:
                    continue
                seen.add((word, previous2, previous1))
                context = f"{previous2} {previous1}"
                rows.append((word, word, CLEAN, context))
                for kind, module in TYPOS:
                    if word not in eligible[kind]:
                        continue
                    case = typo(word, module, kind, words)
                    if case is not None:
                        rows.append((case, word, kind, context))
    return rows


def drawn(candidates, per_kind=ROWS_PER_KIND):
    """The rows drawn from [candidates], candidate_rows' rows: for each kind of TYPOS in turn,
    the [per_kind] words with their context that have a typo of that kind and were not drawn for
    an earlier kind, smallest by a hash of the word and its context; each as written, then with
    its kind's typo, in the order of that hash."""
    typos = {}
    for typed, meant, kind, context in candidates:
        if kind != CLEAN:
            typos.setdefault((meant, context), {})[kind] = typed
    order = sorted(typos, key=lambda entry: (hashed(SEED, *entry), entry))
    kinds = {}
    for kind, _ in TYPOS:
        fitting = [entry for entry in order if entry not in kinds and kind in typos[entry]]
        kinds.update((entry, kind) for entry in fitting[:per_kind])
    rows = []
    for entry in order:
        if entry in kinds:
            meant, context = entry
            rows += [(meant, meant, CLEAN, context),
                     (typos[entry][kinds[entry]], meant, kinds[entry], context)]
    return rows


def pins(manifest, names):
    """[names], each with its archive's pin."""
    return ", ".join(
        f"{name} (SHA-256 {manifest.sha256.get(language_manifest.archive_name(name))})"
        for name in names)


def selftest() -> int:
    """Checks that only the held-out split's personal part is read, each sentence once, with the
    names it writes; the split (a sentence keeps its side when others join or come first, spacing
    and composition aside, as every run draws it, the history taking its share), the typos (the
    same whatever comes before, as every run draws them, never a word of the list, only where
    their corpus admits the word), the rows drawn (each kind's words by hash, whatever the
    candidates' order, each word and context once, as written and with its kind's typo), the
    history as runs in the list's Romanian spelling, and that a persona other than the
    manifest's stops."""
    parts = {"dev": 100, "personal": 500, "case": 400}
    texts = [f"Friday came {n} times. Then Monday." for n in range(300)]
    lines = [f"{n}\t{text}\n" for n, text in enumerate(texts + texts[:5])]
    marks = {text: heldout_overlap.fingerprint(text) for text in texts}
    found = named_sentences(lines, {marks[texts[0]]}, parts, lambda word: word,
                            {"Friday", "Monday"})
    personal = [text for text in texts[1:]
                if heldout_overlap.part_of(marks[text], parts) == "personal"]
    assert [text for text, _ in found] == personal, "not the personal part, each once"
    assert all(named == {"Friday", "Monday"} for _, named in found)

    sentences = [f"Sentence number {n} about Friday." for n in range(200)]
    sides = {text: history_side(text) for text in sentences}
    assert tuple(sides[text] for text in sentences[:len(SELFTEST_SIDES)]) == SELFTEST_SIDES
    share = sum(sides.values()) / len(sides)
    assert abs(share - HISTORY_SIDES / SIDES) < 0.05, share
    more = [f"Another line {n} before." for n in range(50)] + list(reversed(sentences))
    history, rows_side = split(more)
    assert all(sides[text] for text in history if text in sides)
    assert not any(sides[text] for text in rows_side if text in sides)
    assert history_side("a  b") == history_side("a b")
    composed = unicodedata.normalize("NFC", "Café Friday")
    assert history_side(composed) == history_side(unicodedata.normalize("NFD", composed))

    class Words:
        words = {"questin"}

        @classmethod
        def is_word(cls, text):
            return text in cls.words

    class NoWords:
        @staticmethod
        def is_word(text):
            return False

    first = [typo("question", module, kind, NoWords) for kind, module in TYPOS]
    assert tuple(first) == SELFTEST_TYPOS, first
    for kind, module in reversed(TYPOS):
        typo("whatever", module, kind, NoWords)
    assert first == [typo("question", module, kind, NoWords) for kind, module in TYPOS]

    eligible = {kind: {"question"} for kind, _ in TYPOS}
    eligible["extra"] = set()
    rows = candidate_rows(["I asked a question. A question again."], lambda word: word,
                          {"question"}, eligible, Words)
    assert rows == [("question", "question", CLEAN, "asked a"),
                    ("quextion", "question", "slip", "asked a"),
                    ("quetsio", "question", "midtypo", "asked a")], rows

    kinds = [kind for kind, _ in TYPOS]
    candidates = []
    for n in range(40):
        context = f"before {n}"
        candidates.append((f"word{n}", f"word{n}", CLEAN, context))
        candidates += [(f"{kind}{n}", f"word{n}", kind, context)
                       for kind in kinds if n % 4 != kinds.index(kind)]
    candidates.append(("alone", "alone", CLEAN, "no typo"))
    rows = drawn(candidates, 3)
    assert tuple(typed for typed, _, _, _ in rows) == SELFTEST_ROWS, rows
    assert drawn(list(reversed(candidates)), 3) == rows, "the draw followed the candidates' order"
    typed = [row for row in rows if row[2] != CLEAN]
    assert sorted(kind for _, _, kind, _ in typed) == sorted(kinds * 3), typed
    entries = [(meant, context) for _, meant, _, context in typed]
    assert len(set(entries)) == len(entries), "a word and context drawn twice"
    assert rows[::2] == [(meant, meant, CLEAN, context) for meant, context in entries]
    assert all(typo == f"{kind}{meant[4:]}" for typo, meant, kind, _ in typed)

    spell = speller(ROMANIAN, {"și", "noi"}, set())
    assert history_runs(["Ei şi noi’s. Apoi pleacă!"], spell) == [["Ei", "și", "noi's"],
                                                                   ["Apoi", "pleacă"]]

    manifest = language_manifest.Manifest(path=Path("xx_XX.json"), tag="xx-XX", corpora=[],
                                          known_corpora=[], case_corpora=[], capitals=None,
                                          heldout=None, persona="Friday", sha256={})
    assert checked_persona("Friday", manifest) == "Friday"
    try:
        checked_persona("Monday", manifest)
    except SystemExit:
        pass
    else:
        raise AssertionError("a persona other than the manifest's was accepted")
    print("make_personal_corpus selftest: ok")
    return 0


@dataclass(frozen=True)
class Personal:
    """A persona's corpus: the personal part's sentences ([held], each with the names it writes),
    the [persona] and the sentences [mentioning] it, split into the [history] (as
    [history_lines], runs of words) and the [rows_side], whose [candidates] the [rows] are drawn
    from."""
    held: list
    persona: str
    mentioning: list
    history: list
    rows_side: list
    history_lines: list
    candidates: list
    rows: list


def personal(manifest, overlap, cache, words: Lexicon) -> Personal:
    """[manifest]'s persona corpus from the personal part of its held-out file in [cache], the
    sentences [overlap] holds left out, each word as [words] spells it."""
    shared = set().union(*overlap.values())
    with heldout_overlap.heldout_sentences(manifest, cache).open(
            encoding="utf-8", errors="replace") as lines:
        held = named_sentences(lines, shared, manifest.heldout_split, words.spell, words.names)
    counts = {}
    for _, named in held:
        for word in named:
            counts[word] = counts.get(word, 0) + 1
    best = max(counts.values())
    persona = checked_persona(min(word for word, count in counts.items() if count == best),
                              manifest)

    mentioning = [text for text, named in held if persona in named]
    history, rows_side = split(mentioning)
    history_lines = history_runs(history, words.spell)
    candidates = candidate_rows(rows_side, words.spell, words.ordinary, words.eligible,
                                words.words)
    return Personal(held, persona, mentioning, history, rows_side, history_lines, candidates,
                    drawn(candidates))


def write_rows(path, about, rows):
    """[rows] into [path] under a line saying [about] and the line naming the columns:
    `typed<TAB>meant<TAB>kind<TAB>previous2 previous1`."""
    with path.open("w", encoding="utf-8") as out:
        out.write(f"# {about}\n")
        out.write("# typed<TAB>meant<TAB>kind<TAB>previous2 previous1\n")
        for typed_word, meant, kind, context in rows:
            out.write(f"{typed_word}\t{meant}\t{kind}\t{context}\n")


def main() -> int:
    if "--selftest" in sys.argv[1:]:
        return selftest()
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("tag", choices=language_manifest.with_key("persona"))
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--cache", type=Path, default=language_manifest.CACHE)
    args = parser.parse_args()

    manifest = language_manifest.load(args.tag)
    verdict = heldout_overlap.verdict(manifest)
    if not verdict.admitted:
        sys.exit(f"{args.tag}: refused ({'; '.join(verdict.refusals)}); "
                 f"see tools/heldout_overlap.py {args.tag}")
    overlap = heldout_overlap.shared(manifest, args.cache)
    corpus = personal(manifest, overlap, args.cache, lexicon(args.tag))
    persona = corpus.persona

    language = manifest.tag.split("-")[0]
    share = manifest.heldout_split[language_manifest.PERSONAL_PART]
    source = (f"the personal part ({share} of {language_manifest.SPLIT_UNITS} by fingerprint, "
              f"{len(corpus.held)} sentences) of {pins(manifest, [manifest.heldout])}")
    args.out.mkdir(parents=True, exist_ok=True)
    history_path = args.out / f"personal_history_{language}.txt"
    with history_path.open("w", encoding="utf-8") as out:
        out.write(f"# A persona's history for {args.tag} ({list_path(args.tag).name}) from "
                  f"{source}: the sentences mentioning \"{persona}\" on the history side of the "
                  f"split by a hash of each sentence (seed {SEED}, {HISTORY_SIDES} of {SIDES} "
                  f"sides), {len(corpus.history)} of {len(corpus.mentioning)}, as "
                  f"{len(corpus.history_lines)} runs of words.\n")
        out.write(f"# Generated by tools/make_personal_corpus.py. Left out as also written in "
                  f"{pins(manifest, manifest.counted)}: of the held-out file, "
                  + ", ".join(f"{name} {len(prints)}" for name, prints in overlap.items())
                  + ".\n")
        for run in corpus.history_lines:
            out.write(" ".join(run) + "\n")
    candidates_path = args.out / f"personal_candidates_{language}.tsv"
    rows_path = args.out / f"personal_rows_{language}.tsv"
    sides = len(corpus.rows_side)
    write_rows(candidates_path,
               f"Candidate rows for {args.tag} after personal_history_{language}.txt: the "
               f"{sides} sentences mentioning \"{persona}\" on the rows side, from {source}. "
               f"Generated by tools/make_personal_corpus.py; each typo drawn from its corpus's "
               f"seed, its kind and the word.", corpus.candidates)
    write_rows(rows_path,
               f"Rows after the history in personal_history_{language}.txt, from the {sides} "
               f"sentences mentioning \"{persona}\" on the rows side, from {source}. Generated "
               f"by tools/make_personal_corpus.py, seed {SEED}: of {len(corpus.candidates)} "
               f"candidates, {ROWS_PER_KIND} words per typo kind "
               f"({', '.join(kind for kind, _ in TYPOS)}) by a hash of the word and its context, "
               f"each typed as written and with that typo.", corpus.rows)
    print(f"{history_path}: {len(corpus.history)} sentences, {len(corpus.history_lines)} runs; "
          f"{candidates_path}: {len(corpus.candidates)} candidates; {rows_path}: "
          f"{len(corpus.rows)} rows", file=sys.stderr)
    return 0

if __name__ == "__main__":
    sys.exit(main())
