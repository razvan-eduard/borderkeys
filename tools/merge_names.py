#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Merges a make_names.py list into an already-built `dictionaries/<tag>.tsv`.

Why this exists rather than `make_pack.py --names`
--------------------------------------------------
`make_pack.py` merges names while it is building a pack out of a corpus, and the corpora the
bundled packs were built from are not in this repository -- they are tens of gigabytes of text.
`dictionaries/<tag>.tsv` IS the surviving output of that stage, so a new name list has to be
merged into it in place, and `build_dict.py` recompiles the pack from there.

Feeding the `.tsv` back through `make_pack.py --frequencies` is not the same thing and would
lose work: `read_frequencies` reads a name row's count correctly but returns only counts, so
every existing proper-noun flag -- 48,123 of them in English, including everything
`flag_names.py` found -- would be dropped on the floor. Editing in place is also what makes the
result reviewable, because the diff is exactly the set of rows that changed.

The guards are `make_pack.py`'s own, imported rather than copied, so there is one definition of
what may carry the flag: `name_allowed` (the treebank, the spelling list, the frequency tiers)
and `NAME_ADD_MIN_USES` for whether a word the corpus never wrote down may be added at all.

The one guard this adds
-----------------------
**A word that is an ordinary lower-case word in ANY language we ship is never a name, whatever
Wikidata says.** Every spell checker passed on the command line vetoes, not just the one
belonging to the list being merged.

`make_pack.name_allowed` has a version of this rule but lets the treebank overrule it, which is
right for a person's name and wrong for a company's. Real organisations are named after ordinary
words, so the entity list offers `zero`, `joy`, `guard`, `opera`, `blues` and `sentenced` in
English -- and a web treebank has met the company more often than the word, so it tags them as
proper nouns and the veto never fires.

Asking only the list's own language is not enough, and the number is why. Languages borrow, and
the borrowed word arrives in lower case without ever becoming a headword in the borrower's
dictionary: Romanian writes `live`, `punk` and `rap`, Italian writes `blogger` and `ceo`, French
writes `arena` and `ghost`, and no Romanian, Italian or French spell checker has any of them.
Measured across the six bundled languages, 977 flagged words are ordinary somewhere -- 9% of
English, 27% of German -- so a per-language rule leaves a thousand-word hand list to curate and
keep curated. Asking every shipped dictionary costs one subprocess per language and needs no
list at all.

German is the case that makes this necessary rather than merely tidy. German capitalises every
noun, so its own spell checker refuses `panik`, `pilot` and `investor` in lower case exactly as
it refuses a name, and the veto is structurally inert there -- the same reason `flag_names.py`
refuses German outright in `CAPITALISES_EVERY_NOUN`. The other five dictionaries are what still
work on it.

The cost, stated plainly: a word that is both an ordinary word somewhere and a real name here is
refused. English `amazon`, `intel`, `shell`, `orange` and `sky` go, and so does German `island`,
which is German for Iceland. They keep their corpus row and stay uncapitalised, which is what
they already did -- the trade is a name not gained against an ordinary word wrongly capitalised
in the middle of a sentence, and the second is the worse keyboard.

What survives is what no dictionary anywhere holds, which is exactly the class this list exists
for: `ubisoft`, `bytedance`, `paribas`, `xiaomi`, `spacex`, `kaufland`, `transgaz`, `biontech`.

Adding asks a narrower question than flagging
---------------------------------------------
The rule above is for **flagging** -- giving the proper-noun bit to a word the corpus already
has, which changes how something the user already types behaves, and deserves the strictest
test available.

**Adding** a word the corpus never wrote down is a different question, and only this language's
own dictionary is asked. Every-language would be wrong here, and country names are why: `chile`
is a pepper in English, `argentina` is "silvery" in Italian, `ecuador` is the equator in
Spanish. Measured, the strict rule costs 16 of 25 country names while the narrow one costs
none, and it costs nothing in safety -- a word the corpus does not contain is not a word this
language's users are currently typing and having capitalised out from under them.


The alphabet
------------
Letters outside the ones the language actually writes with are dropped -- `islām`, `hokkaidō`,
`mahārāṣṭra` -- because a word that cannot be typed on the layout cannot be looked up either.
The alphabet is read off the pack's own most frequent words rather than declared, which gives
26 letters for English and 34 for Romanian. Read it off too deep a slice and corpus noise
starts contributing: at 20,000 words English picks up `ā` and `α` and the filter stops working.

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

# How deep into the pack to look when working out which letters the language writes with. Deep
# enough to have seen every letter, shallow enough that the corpus's own foreign-word tail has
# not started: measured, 5,000 gives English exactly its 26 and Romanian its 34, while 20,000
# gives English 30 and the extra four are noise.
ALPHABET_SAMPLE = 5_000

# Below this there is not enough word to judge. Two-letter tokens are overwhelmingly
# abbreviations, particles and state codes -- the person list offers "wa", "ga" and "mi", each
# of them somebody's name to Wikidata -- and a spell checker's verdict on them says more about
# its own abbreviation list than about the language. Same floor and same reason as
# flag_names.py's MIN_LENGTH and make_names.py's MIN_NAME_LENGTH.
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

    One accepting is enough to veto, and that is the whole point: these are every language the
    project ships, not just the one being merged. A word that is ordinary in British but not
    American English is still an ordinary word; so is one that is ordinary in English and merely
    borrowed into Romanian, which is where `live`, `punk` and `rap` come from.

    A spell checker that will not run vetoes nothing, and says so. That is the safe direction
    here -- the opposite default would silently let the whole list through.
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
        # Existing rows keep their order and their real corpus frequency; only the third column
        # moves. Added rows go on the end at the flat frequency, which is what make_pack.py does
        # with a name the corpus never wrote down.
        written = [f"{line}\tname" if word in flag else line for word, line, _ in rows]
        written.extend(f"{word}\t{flat[word]}\tname" for word in sorted(add))
        path.write_text("\n".join(written) + "\n", encoding="utf-8")
        print(f"    written: {path}")
    else:
        print("nothing written -- pass --apply")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
