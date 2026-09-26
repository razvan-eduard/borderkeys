#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Removes another language's vocabulary from a word list.

A corpus of Romanian web text is full of English. Film titles, band names, citation titles and
quoted sentences all get counted as Romanian words by `make_pack.py`, which counts tokens and
has no notion of what language any one of them belongs to. `docs/dictionaries.md` already says
so, in those words, about the proper-name pipeline; this is the same filter applied to the
vocabulary itself, which is where it actually matters.

Why it matters beyond a slightly larger pack: the engine decides which language is being
written by counting words that exactly one enabled dictionary knows (see
`Engine::observeContextLanguage`). English words sitting in the Romanian pack are known by
both, so they are evidence of nothing, and the most common English words are exactly the ones
most likely to appear inside a quoted fragment -- so the overlap lands precisely where the
traffic is. Measured on a phone with both packs on: seven ordinary English words produced zero
evidence and the language verdict never changed, which left every feature built on it inert.

Nothing here is a judgement about loanwords. "mouse", "internet", "weekend" and "hot" are
Romanian words now, and the rule below keeps all four. What it removes is "the", "of", "and",
"there", "life" and "service" -- words no Romanian dictionary would list, present only because
they were counted out of English text.

The rule
--------
One accusation and two chances to answer it. A word goes only if it is accused and neither
witness speaks for it.

The accusation is relative frequency: a word is *suspect* when some other language knows it as
genuinely common (Zipf above --floor) and knows it better than this language by more than
--margin Zipf points. One point is a factor of ten, which is a wide moat on purpose -- a word a
language really uses will not be ten times rarer in it than somewhere else. On its own this is
badly wrong for related languages: Romanian shares "e", "el", "le", "da", "sus" and "place"
with Italian, Spanish and French, which all weight them more heavily, and every one of those is
core Romanian vocabulary.

The first witness is the language's own spell checker, asked in lower case. That answers for
most of them, and for Romanian it rescues 1,724 of 6,599 suspects.

The second witness is the language's own treebank (`<tag>.pos`), and it exists because the
first one cannot answer for German. German capitalises every noun, so its dictionary refuses
the lower-cased "mai", "million" and "tempo" that these lists are written in, while asking the
capitalised form instead readmits "New", "City" and "San". A treebank tag says what the word
was *doing* in running text: "mai" is NN and "city" is NE. It settles Romanian's last false
positive in the same stroke, where "sua" (the USA) is tagged Yn, an abbreviation.

Zipf is computed from the word lists themselves rather than an external frequency package, so
this stays standard library only like everything else in tools/. That is self-consistent by
construction: every pack is measured on the same scale, against the same six corpora.

Usage
-----
  tools/drop_foreign.py                          # report what would go, change nothing
  tools/drop_foreign.py --tag ro-RO --sample 40  # look closely at one language first
  tools/drop_foreign.py --apply                  # rewrite the lists
"""

from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
from pathlib import Path

# The six that ship. Each is both a target to clean and a witness against the others.
BUNDLED = ("en_US", "ro_RO", "de_DE", "es_ES", "fr_FR", "it_IT")
BUNDLED += tuple(
    json.loads(manifest.read_text(encoding="utf-8"))["tag"].replace("-", "_")
    for manifest in sorted((Path(__file__).resolve().parent / "languages").glob("*.json"))
)

# A word has to be genuinely common somewhere else before its absence here means anything. Below
# this, the other language's own count is too thin to be evidence of where the word belongs.
DEFAULT_FLOOR = 3.0

# One Zipf point is a factor of ten. A narrower margin starts taking real loanwords: "hot" sits
# 0.84 apart between Romanian and English, "mouse" 0.75, and both belong in Romanian.
DEFAULT_MARGIN = 1.0


def read_list(path: Path) -> tuple[dict[str, int], int, set[str]]:
    """A `word<TAB>count[<TAB>name]` file: its counts, the total, and which rows are names.

    The third column is `make_pack.py --names` output, merged in after the frequency cutoffs
    and flagged so the pack capitalises it. Names are counted towards the total -- they are
    real occurrences -- but never removed: they come from a separate, deliberately-chosen
    source with its own cross-language handling (`make_names.py`, and the `.names-ordinary` /
    `.names-exclude` files beside these lists), and a rule about vocabulary has no business
    second-guessing it. "John" belongs in every pack.
    """
    counts: dict[str, int] = {}
    names: set[str] = set()
    total = 0
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        try:
            count = int(parts[1])
        except ValueError:
            continue
        counts[parts[0]] = count
        total += count
        if len(parts) >= 3 and parts[2] == "name":
            names.add(parts[0])
    return counts, total, names


def zipf_table(counts: dict[str, int], total: int) -> dict[str, float]:
    """Zipf: log10 of occurrences per billion tokens, the scale the rule's margin is in."""
    if total <= 0:
        return {}
    scale = 1e9 / total
    return {word: math.log10(count * scale) for word, count in counts.items()}


def accepted_by(words: list[str], dictionary: Path) -> set[str]:
    """Which of [words] the language's own spell checker accepts.

    `hunspell -l` prints the words it does *not* know, so what it stays silent about is what
    the language vouches for. Acceptance rather than `make_ordinary.py`'s headword test
    (`hunspell -s`) on purpose: an inflected form is still the language's own word, and this
    gate is asking "is this word Romanian at all", not "is this the dictionary's citation
    form".

    This gate is not optional. The frequency rule on its own flags every short word Romanian
    shares with Italian, Spanish or French -- "e", "el", "le", "da", "sus", "place" -- because
    those corpora weight them more heavily. Every one of them is a core Romanian word, and
    every one is rescued here. Measured on Spanish, where a dictionary was to hand: 6,463
    suspect, 969 rescued, and the rescues are "le", "e", "da", "sur", "di", "dato", "sale".

    The word is asked exactly as the list spells it, which is lower case, and no other case is
    tried. That is not an oversight, and `make_ordinary.py` states the same rule beside its own
    hunspell call: a lower-case entry accepts both spellings, a capitalised one only its own, so
    a lower-case question is what makes the answer mean "an ordinary word of this language".

    Trying the capitalised form was measured and rejected. These dictionaries do refuse
    capitalised nonsense, so it is not a blanket loophole, but they carry real proper-noun
    entries for name particles -- Romanian hunspell rejects "the" and accepts "The", "City",
    "San" and "New", because those appear in names it knows. Rescuing on those put every one of
    them straight back into the Romanian pack. The cost of the stricter question is a German
    noun whose lower-cased form its dictionary will not vouch for; `--allow` is for those.
    """
    if not words:
        return set()
    result = subprocess.run(
        ["hunspell", "-d", str(dictionary), "-i", "utf-8", "-l"],
        input="\n".join(words) + "\n", capture_output=True, text=True, check=True,
    )
    rejected = {line.strip() for line in result.stdout.splitlines() if line.strip()}
    return {word for word in words if word not in rejected}


# Which treebank tags are *not* evidence that the language owns a word, per tagset. Proper
# nouns are the loophole the spell checkers fall through -- Romanian's dictionary knows "The",
# "City" and "San" because they appear inside names it lists -- and each tagset's own foreign or
# residual marker says outright that the token was not the language's own. Everything else is.
#
# Spelled out per language rather than pattern-matched: these are four different tagsets and the
# same letters mean different things in them. Romanian's "Sp..." is a preposition ("sub", "la")
# while Italian's "SP" is a proper noun, so a prefix rule would quietly gut Romanian.
POS_NOT_EVIDENCE: dict[str, tuple[tuple[str, ...], frozenset[str]]] = {
    "en_US": (("NNP",), frozenset({"FW"})),          # Penn: NNP/NNPS proper, FW foreign
    "de_DE": (("NE",), frozenset({"FM", "XY"})),     # STTS: NE proper, FM foreign material
    "ro_RO": (("Np",), frozenset({"X"})),            # MSD: Np proper; Yn (abbreviation) counts
    "es_ES": (("PROPN",), frozenset({"X"})),
    "fr_FR": (("PROPN",), frozenset({"X"})),
    "it_IT": (("SP",), frozenset({"X"})),            # S is a common noun, SP a proper one
}


def treebank_vouches(tag: str, words: list[str], pos_directory: Path) -> set[str]:
    """Which of [words] this language's own treebank tagged as one of its ordinary words.

    The third witness, and the one that settles German. German capitalises every noun, so its
    spell checker holds "Mai", "Million" and "Tempo" while these lists are lower-cased -- the
    lower-case question its dictionary is asked therefore refuses real German nouns. Asking the
    capitalised form instead is no good either, because that readmits "New", "City" and "San".
    The treebank separates them on what the word was actually *doing* in running text: "mai" is
    tagged NN and "city" NE, so one is kept and the other is not.

    It settles Romanian's remaining false positive too: "sua" (the USA) is tagged Yn, an
    abbreviation, while "the", "city" and "san" are all Np. One rule, both languages, no
    per-language script.
    """
    path = pos_directory / f"{tag}.pos"
    if tag not in POS_NOT_EVIDENCE or not path.is_file():
        return set()
    data = json.loads(path.read_text(encoding="utf-8"))
    tagset = data.get("tagset") or []
    tagged = data.get("tags") or {}
    prefixes, exact = POS_NOT_EVIDENCE[tag]
    vouched = set()
    for word in words:
        index = tagged.get(word)
        if not isinstance(index, int) or not 0 <= index < len(tagset):
            continue
        name = tagset[index]
        if name.startswith(prefixes) or name in exact:
            continue
        vouched.add(word)
    return vouched


def read_allowlist(path: Path | None) -> set[str]:
    if path is None:
        return set()
    words = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        word = line.split("#", 1)[0].strip()
        if word:
            words.add(word)
    return words


def foreign_words(
    tag: str,
    zipf: dict[str, dict[str, float]],
    floor: float,
    margin: float,
    allowed: set[str],
    names: set[str],
) -> dict[str, tuple[str, float, float]]:
    """Every word of [tag] that belongs to another language, and which one claims it."""
    mine = zipf[tag]
    others = [other for other in zipf if other != tag]
    verdict: dict[str, tuple[str, float, float]] = {}
    for word, own in mine.items():
        if word in allowed or word in names:
            continue
        best_tag, best = "", 0.0
        for other in others:
            score = zipf[other].get(word)
            if score is not None and score > best:
                best_tag, best = other, score
        if best > floor and best > own + margin:
            verdict[word] = (best_tag, best, own)
    return verdict


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dictionaries", type=Path, default=Path("dictionaries"),
                        help="directory holding <tag>.tsv, default ./dictionaries")
    parser.add_argument("--tag", action="append", default=None,
                        help="which list to clean, repeatable; default every bundled one")
    parser.add_argument("--floor", type=float, default=DEFAULT_FLOOR,
                        help=f"how common a word must be elsewhere to count, default {DEFAULT_FLOOR}")
    parser.add_argument("--margin", type=float, default=DEFAULT_MARGIN,
                        help=f"Zipf points the other language must win by, default {DEFAULT_MARGIN}")
    parser.add_argument("--allow", type=Path, default=None,
                        help="a file of words to keep whatever the rule says, one per line")
    parser.add_argument("--hunspell", action="append", default=[], metavar="TAG=PATH",
                        help="the language's own spell checker, e.g. "
                             "ro_RO=/path/to/ro_RO for ro_RO.dic + ro_RO.aff. A flagged word it "
                             "accepts is kept. Repeatable, one per language.")
    parser.add_argument("--pos", type=Path, default=None,
                        help="directory holding <tag>.pos treebank files (usually the same as "
                             "--dictionaries). A suspect word its own treebank tagged as an "
                             "ordinary word is kept; this is what saves German nouns.")
    parser.add_argument("--no-oracle", action="store_true",
                        help="allow --apply for a language with no --hunspell. Do not: the Zipf "
                             "rule alone removes core vocabulary a related language weights more "
                             "heavily, which for Romanian is 'e', 'el', 'da', 'sus' and 'place'.")
    parser.add_argument("--sample", type=int, default=15,
                        help="how many of the heaviest removals to print per language")
    parser.add_argument("--apply", action="store_true",
                        help="rewrite the lists; without it nothing is written")
    arguments = parser.parse_args()

    available = [tag for tag in BUNDLED if (arguments.dictionaries / f"{tag}.tsv").is_file()]
    if len(available) < 2:
        print(f"need at least two word lists in {arguments.dictionaries}, found {len(available)}",
              file=sys.stderr)
        return 1

    counts: dict[str, dict[str, int]] = {}
    zipf: dict[str, dict[str, float]] = {}
    names: dict[str, set[str]] = {}
    for tag in available:
        word_counts, total, flagged = read_list(arguments.dictionaries / f"{tag}.tsv")
        counts[tag] = word_counts
        names[tag] = flagged
        zipf[tag] = zipf_table(word_counts, total)
        print(f"{tag}: {len(word_counts):,} words "
              f"({len(word_counts) - len(flagged):,} vocabulary, {len(flagged):,} names), "
              f"{total:,} tokens")

    allowed = read_allowlist(arguments.allow)
    oracles: dict[str, Path] = {}
    for entry in arguments.hunspell:
        tag, _, path = entry.partition("=")
        if not path:
            print(f"--hunspell wants TAG=PATH, got {entry!r}", file=sys.stderr)
            return 1
        oracles[tag] = Path(path)
    targets = arguments.tag or available
    print()

    for tag in targets:
        if tag not in zipf:
            print(f"{tag}: no word list, skipped", file=sys.stderr)
            continue
        verdict = foreign_words(
            tag, zipf, arguments.floor, arguments.margin, allowed, names[tag],
        )
        flagged = len(verdict)
        rescued: set[str] = set()
        if tag in oracles:
            rescued |= accepted_by(sorted(verdict), oracles[tag])
        if arguments.pos:
            rescued |= treebank_vouches(tag, sorted(verdict), arguments.pos)
        for word in rescued:
            verdict.pop(word, None)
        vocabulary = len(counts[tag]) - len(names[tag])
        kept = vocabulary - len(verdict)
        share = 100.0 * len(verdict) / max(vocabulary, 1)
        witnesses = []
        if tag in oracles:
            witnesses.append("spell checker")
        if arguments.pos:
            witnesses.append("treebank")
        oracle_note = (f"{len(rescued):,} rescued by its own {' and '.join(witnesses)}"
                       if witnesses else "NO WITNESS -- unsafe to apply")
        print(f"{tag}: {flagged:,} flagged, {oracle_note}, "
              f"{len(verdict):,} removed of {vocabulary:,} vocabulary words "
              f"({share:.1f}%), {kept:,} kept, names untouched")
        # Heaviest first: the ones that were doing the most damage, and the ones a mistake in
        # the rule would be most visible in.
        worst = sorted(verdict.items(), key=lambda item: -counts[tag][item[0]])
        for word, (other, other_zipf, own_zipf) in worst[:arguments.sample]:
            print(f"    {word:<20} {counts[tag][word]:>9,}  "
                  f"{tag} {own_zipf:.2f} vs {other} {other_zipf:.2f}")
        if arguments.apply and tag not in oracles and not arguments.no_oracle:
            print(f"    refusing to write {tag}: no --hunspell for it, and the Zipf rule alone "
                  f"removes real vocabulary. Pass --no-oracle only if you mean it.")
        elif arguments.apply:
            path = arguments.dictionaries / f"{tag}.tsv"
            lines = [
                line for line in path.read_text(encoding="utf-8").splitlines()
                if line.split("\t", 1)[0] not in verdict
            ]
            path.write_text("\n".join(lines) + "\n", encoding="utf-8")
            print(f"    written: {path}")
        print()

    if not arguments.apply:
        print("nothing written -- pass --apply to rewrite the lists")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
