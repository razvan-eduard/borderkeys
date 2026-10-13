#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Builds a personal-history corpus from a language's held-out Leipzig sentences.

The held-out file and the corpora the language's lists are counted from are its manifest's
(language_manifest.py), each checked against its SHA-256; the language must be admitted by
heldout_overlap.py, and every held-out sentence a counted corpus also writes is left out. Of the
rest, the persona is every sentence that mentions the name the list flags that those sentences
write most, which must be the manifest's `persona`. Romanian words are spelled as
normalise_diacritics.py spells the list.

Each persona sentence goes to the history or to the rows by a hash of its normalised text, so a
sentence keeps its side whatever else the file holds. The history is its sentences one per line,
in NFC, with plain apostrophes and each word spelled as the list spells it; a sentence that would
start with `#` is left out. The rows side gives candidate rows: each of the list's ordinary words
with two words before it in its run (make_context_corpus.runs), typed as written, and typed with
the typo of the slip, omitted, extra and midtypo corpora where its length suits that corpus, the
typo not a word of the list, each word and context once. Each typo is drawn from a seed made of
that corpus's own seed, its kind and the word. The candidates carry no class.

Writes `personal_history_<language>.txt` and `personal_candidates_<language>.tsv`,
`typed<TAB>meant<TAB>kind<TAB>previous2 previous1`, into --out.

    python3 tools/make_personal_corpus.py en_US
    python3 tools/make_personal_corpus.py --selftest
"""

import argparse
import hashlib
import random
import sys
import unicodedata
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
from make_context_corpus import pieces, runs  # noqa: E402

REPOSITORY = Path(__file__).resolve().parent.parent

# The split's seed: a sentence's side is drawn from it and the sentence's text.
SEED = 20261011

# The bytes of a hash a side or a typo seed is drawn from.
HASH_BYTES = 8

# How many sides a sentence is split between, and the history's.
SIDES = 2
HISTORY_SIDE = 0

# A row's word has its previous2 and previous1 in its run.
WORDS_BEFORE = 2

# The kind of a row typed as written.
CLEAN = "clean"

# What starts a line the corpus readers skip.
COMMENT = "#"

# A sentence's side, and a word's typos by kind, as every run and machine draws them.
SELFTEST_SIDES = (False, False, True, True, True, True, True, False)
SELFTEST_TYPOS = ("quextion", "questin", "questioyn", "quetsio")

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
    return hashed(SEED, heldout_overlap.normalise(text)) % SIDES == HISTORY_SIDE


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


def spelled_runs(text, spell):
    """[text]'s runs, each word as [spell] spells it."""
    return [[spell(word) for word in run] for run in runs(text)]


def written(text, spell):
    """[text] as typed: in NFC, its apostrophes plain, each word as [spell] spells it."""
    return "".join(spell(piece) if is_word else piece for is_word, piece in pieces(text))


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


def pins(manifest, names):
    """[names], each with its archive's pin."""
    return ", ".join(
        f"{name} (SHA-256 {manifest.sha256.get(language_manifest.archive_name(name))})"
        for name in names)


def selftest() -> int:
    """Checks the split (a sentence keeps its side when others join or come first, spacing and
    composition aside, and as every run draws it), the typos (the same whatever comes before, as
    every run draws them, never a word of the list, only where their corpus admits the word), the
    Romanian spelling of the history, and that a persona other than the manifest's stops."""
    sentences = [f"Sentence number {n} about Friday." for n in range(200)]
    sides = {text: history_side(text) for text in sentences}
    assert tuple(sides[text] for text in sentences[:len(SELFTEST_SIDES)]) == SELFTEST_SIDES
    assert 0 < sum(sides.values()) < len(sides), "every sentence on one side"
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

    spell = speller(ROMANIAN, {"și", "noi"}, set())
    assert written("Ei şi noi’s", spell) == "Ei și noi's"

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
    shared = set().union(*overlap.values())

    list_path = REPOSITORY / "dictionaries" / f"{args.tag}.tsv"
    words = corpus_words.load(list_path)
    names = {word for word, name in words.rows if name}
    spelled = {word for word, _ in words.rows}
    spell = speller(args.tag, spelled, names)
    ordinary = set(words.ordinary())
    eligible = {kind: set(words.ordinary(None, module.MIN_LENGTH, module.MAX_LENGTH))
                for kind, module in TYPOS}

    # Each sentence's runs once: the names the kept ones write, by sentence, and the names each
    # left-out one writes.
    held = []
    left_out_names = []
    counts = {}
    with heldout_overlap.heldout_sentences(manifest, args.cache).open(
            encoding="utf-8", errors="replace") as source:
        for line in source:
            text = heldout_overlap.sentence_text(line)
            written_names = [word for run in spelled_runs(text, spell) for word in run
                             if word in names]
            if heldout_overlap.fingerprint(text) in shared:
                left_out_names.append(set(written_names))
                continue
            held.append((text, set(written_names)))
            for word in written_names:
                counts[word] = counts.get(word, 0) + 1
    best = max(counts.values())
    persona = checked_persona(min(word for word, count in counts.items() if count == best),
                              manifest)

    mentioning = [text for text, named in held if persona in named]
    left_out = sum(1 for named in left_out_names if persona in named)
    history, rows_side = split(mentioning)
    typed = [written(text, spell) for text in history]
    commented = sum(1 for text in typed if text.startswith(COMMENT))
    typed = [text for text in typed if not text.startswith(COMMENT)]
    rows = candidate_rows(rows_side, spell, ordinary, eligible, words)

    language = manifest.tag.split("-")[0]
    source = pins(manifest, [manifest.heldout])
    args.out.mkdir(parents=True, exist_ok=True)
    history_path = args.out / f"personal_history_{language}.txt"
    with history_path.open("w", encoding="utf-8") as out:
        out.write(f"# A persona's history for {args.tag} ({list_path.name}) from {source}: the "
                  f"sentences mentioning \"{persona}\" on the history side of the split by a hash "
                  f"of each sentence (seed {SEED}), {len(typed)} of {len(mentioning)}; "
                  f"{commented} left out for starting with \"{COMMENT}\".\n")
        out.write(f"# Generated by tools/make_personal_corpus.py. Left out as also written in "
                  f"{pins(manifest, manifest.counted)}: of the held-out file, "
                  + ", ".join(f"{name} {len(prints)}" for name, prints in overlap.items())
                  + f"; of the sentences mentioning \"{persona}\", {left_out}.\n")
        for text in typed:
            out.write(text + "\n")
    candidates_path = args.out / f"personal_candidates_{language}.tsv"
    with candidates_path.open("w", encoding="utf-8") as out:
        out.write(f"# Candidate rows for {args.tag} after personal_history_{language}.txt: the "
                  f"{len(rows_side)} sentences mentioning \"{persona}\" on the rows side, from "
                  f"{source}. Generated by tools/make_personal_corpus.py; each typo drawn from its "
                  f"corpus's seed, its kind and the word.\n")
        out.write("# typed<TAB>meant<TAB>kind<TAB>previous2 previous1\n")
        for typed_word, meant, kind, context in rows:
            out.write(f"{typed_word}\t{meant}\t{kind}\t{context}\n")
    print(f"{history_path}: {len(typed)} sentences; {candidates_path}: {len(rows)} candidates",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
