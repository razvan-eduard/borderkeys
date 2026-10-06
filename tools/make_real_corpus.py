#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Builds a corpus of real English typos from the ITE Typing dataset (Leino, Laine, Kurimo and
Oulasvirta, Aalto University, 2024; doi:10.5281/zenodo.12528162, CC BY 4.0).

Its ac_words_en.csv holds every word a participant's phone autocorrected: the word as typed, the
phone's correction and the word the sentence held. Kept: participants on a mobile device, a typed
word of three letters or more that no pack word spells and that differs from the word meant, a
word meant that the bundled pack holds, both all letters. Each distinct (typed, meant) pair counts
once; the pairs are sampled at random with a fixed seed.

Every line is `typed<TAB>meant<TAB>phone<TAB>before`: the third column what the participant's
phone wrote, read by neither corpus test; the fourth the sentence as it stood before the word was
typed, from the pair's first occurrence. Membership is tested on the folded key, which is what the
pack is keyed by.

    python3 tools/make_real_corpus.py <dir with ac_words_en.csv, participants_labeled.csv and
        test_sections_labeled.csv> dictionaries/en_US.tsv > native-tests/data/autocorrect_real_en.tsv
"""

import argparse
import csv
import importlib.util
import pathlib
import random
import sys

SEED = 20261004
COUNT = 1000
MIN_LENGTH = 3


def load_fold():
    """build_dict.py's own fold, so membership here means membership in the compiled pack."""
    path = pathlib.Path(__file__).with_name("build_dict.py")
    spec = importlib.util.spec_from_file_location("build_dict", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.fold_word


def sentence_before(current_input):
    """The sentence as it stood before its last word: `current_input` without that word, on one
    line."""
    return " ".join(current_input.split()[:-1])


def mobile_sections(directory):
    """The test sections typed on a mobile device."""
    mobile = set()
    with open(directory / "participants_labeled.csv", encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            if row["DEVICE"] == "mobile":
                mobile.add(int(float(row["PARTICIPANT_ID"])))
    sections = set()
    with open(directory / "test_sections_labeled.csv", encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            if int(float(row["PARTICIPANT_ID"])) in mobile:
                sections.add(int(float(row["TEST_SECTION_ID"])))
    return sections


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("ite", type=pathlib.Path)
    parser.add_argument("dictionary")
    parser.add_argument("--count", type=int, default=COUNT)
    args = parser.parse_args()

    csv.field_size_limit(sys.maxsize)
    fold = load_fold()
    packed = set()
    with open(args.dictionary, encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 2:
                packed.add(fold(parts[0]))

    sections = mobile_sections(args.ite)
    counts = {"rows": 0, "mobile": 0, "short": 0, "same": 0, "letters": 0, "typed known": 0,
              "meant missing": 0}
    pairs = {}
    with open(args.ite / "ac_words_en.csv", encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            counts["rows"] += 1
            if int(float(row["TEST_SECTION_ID"])) not in sections:
                continue
            counts["mobile"] += 1
            typed = row["TYPED_WORD"].strip().lower()
            meant = row["ORIGINAL_WORD"].strip().lower()
            phone = row["AC_WORD"].strip().lower()
            if not (typed.isascii() and typed.isalpha() and meant.isascii() and meant.isalpha()):
                counts["letters"] += 1
                continue
            if typed == meant:
                counts["same"] += 1
                continue
            if len(typed) < MIN_LENGTH:
                counts["short"] += 1
                continue
            if fold(typed) in packed:
                counts["typed known"] += 1
                continue
            if fold(meant) not in packed:
                counts["meant missing"] += 1
                continue
            pairs.setdefault((typed, meant), (phone, sentence_before(row["CURRENT_INPUT"])))

    eligible = sorted(pairs)
    rows = random.Random(SEED).sample(eligible, min(args.count, len(eligible)))
    rows.sort()
    phone_right = sum(1 for pair in rows if pairs[pair][0] == pair[1])

    print("# Real English typos from the ITE Typing dataset: Katri Leino, Markku Laine, Mikko Kurimo")
    print("# and Antti Oulasvirta, Aalto University, 2024, doi:10.5281/zenodo.12528162, CC BY 4.0.")
    print("# Each word a participant's phone autocorrected, typed on a mobile device; the word as")
    print("# typed, the word the sentence held, what the phone wrote, and the sentence before it.")
    print("#")
    print(f"# {counts['rows']:,} autocorrected words, {counts['mobile']:,} on mobile devices. Left out: "
          f"{counts['letters']:,} not all letters, {counts['same']:,} typed as meant,")
    print(f"# {counts['short']:,} under {MIN_LENGTH} letters, {counts['typed known']:,} typed as another "
          f"word the pack holds, {counts['meant missing']:,} meant as a word the pack lacks.")
    print(f"# {len(eligible):,} distinct pairs are eligible; these are {len(rows)} sampled at random. "
          f"The phones wrote the word meant for {phone_right} of them.")
    print("#")
    print("# Generated by tools/make_real_corpus.py, seed %d." % SEED)
    for typed, meant in rows:
        phone, before = pairs[(typed, meant)]
        print(f"{typed}\t{meant}\t{phone}\t{before}")
    for name, value in counts.items():
        print(f"{name}: {value}", file=sys.stderr)
    print(f"eligible: {len(eligible)}, sampled: {len(rows)}, phone right: {phone_right}", file=sys.stderr)


if __name__ == "__main__":
    main()
