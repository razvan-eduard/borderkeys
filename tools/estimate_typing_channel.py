#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Measures how often typing on a phone adds, drops or swaps a letter, and how often the word
meant is one the pack lacks, from the ITE Typing dataset (Leino, Laine, Kurimo and Oulasvirta,
Aalto University, 2024; doi:10.5281/zenodo.12528162, CC BY 4.0). The tap decoder's operation
log-probabilities and its unknown-word log-probability are these rates.

Each mobile test section's words are read as they stood when the word ended: the final input,
with every autocorrected word put back as typed (ac_words_en.csv). A section whose word count
differs from its sentence's is left out. Each typed word is aligned with the word meant by
optimal string alignment; an added letter is a tap of no letter, a dropped one a letter with no
tap. Pairs in the real-typo test corpus are left out.

    python3 tools/estimate_typing_channel.py <dir with the ITE csv files> dictionaries/en_US.tsv \\
        native-tests/data/autocorrect_real_en.tsv
"""

import argparse
import collections
import csv
import math
import pathlib
import sys

from make_real_corpus import load_fold, mobile_sections
from make_unknown_corpus import packed_keys


def words_of(text):
    """The words of `text`, lower-cased, each holding only its letters and apostrophes."""
    words = []
    for token in text.replace("’", "'").split():
        word = "".join(c for c in token.lower() if c.isalpha() or c == "'").strip("'")
        words.append(word)
    return words


QWERTY_ROWS = ("qwertyuiop", "asdfghjkl", "zxcvbnm")

# How far each row's first key sits in from the top row's, in key widths.
ROW_INSETS = (0.0, 0.5, 1.5)

# Keys whose centres are at most this far apart, in key units, are neighbours: one key across,
# or one row up or down and half a key across.
NEIGHBOUR_DISTANCE = 1.2


def key_centres():
    """Each letter's key centre on the QWERTY layout, in key units, each row set in from the
    one above as the layout draws it."""
    centres = {}
    for row, letters in enumerate(QWERTY_ROWS):
        for column, letter in enumerate(letters):
            centres[letter] = (column + ROW_INSETS[row], float(row))
    return centres


def key_distance(centres, typed, meant):
    """How far apart two letters' keys are, in key units; None when either is not a letter key."""
    if typed not in centres or meant not in centres:
        return None
    (tx, ty), (mx, my) = centres[typed], centres[meant]
    return ((tx - mx) ** 2 + (ty - my) ** 2) ** 0.5


def alignment(typed, meant):
    """An optimal string alignment of `typed` to `meant`, as (kind, typed letter, meant letter)
    steps, kind one of "match", "substitute", "add", "drop" and "swap"."""
    rows, cols = len(typed) + 1, len(meant) + 1
    cost = [[0] * cols for _ in range(rows)]
    for i in range(rows):
        cost[i][0] = i
    for j in range(cols):
        cost[0][j] = j
    for i in range(1, rows):
        for j in range(1, cols):
            best = min(cost[i - 1][j] + 1, cost[i][j - 1] + 1,
                       cost[i - 1][j - 1] + (typed[i - 1] != meant[j - 1]))
            if i > 1 and j > 1 and typed[i - 1] == meant[j - 2] and typed[i - 2] == meant[j - 1]:
                best = min(best, cost[i - 2][j - 2] + 1)
            cost[i][j] = best
    steps = []
    i, j = len(typed), len(meant)
    while i > 0 or j > 0:
        if (i > 1 and j > 1 and typed[i - 1] == meant[j - 2] and typed[i - 2] == meant[j - 1]
                and cost[i][j] == cost[i - 2][j - 2] + 1):
            steps.append(("swap", typed[i - 2:i], meant[j - 2:j]))
            i, j = i - 2, j - 2
        elif i > 0 and j > 0 and cost[i][j] == cost[i - 1][j - 1] + (typed[i - 1] != meant[j - 1]):
            kind = "match" if typed[i - 1] == meant[j - 1] else "substitute"
            steps.append((kind, typed[i - 1], meant[j - 1]))
            i, j = i - 1, j - 1
        elif i > 0 and cost[i][j] == cost[i - 1][j] + 1:
            steps.append(("add", typed[i - 1], ""))
            i -= 1
        else:
            steps.append(("drop", "", meant[j - 1]))
            j -= 1
    steps.reverse()
    return steps


def typed_before_correction(directory, sections):
    """Per test section, each word position autocorrected, mapped to the word as typed."""
    typed = {}
    with open(directory / "ac_words_en.csv", encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            section = int(float(row["TEST_SECTION_ID"]))
            if section not in sections:
                continue
            position = len(row["CURRENT_INPUT"].split()) - 1
            typed.setdefault(section, {})[position] = words_of(row["TYPED_WORD"])[:1]
    return typed


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("ite", type=pathlib.Path)
    parser.add_argument("dictionary")
    parser.add_argument("held_out")
    args = parser.parse_args()

    csv.field_size_limit(sys.maxsize)
    fold = load_fold()
    packed = packed_keys(args.dictionary, fold)
    held_out = set()
    with open(args.held_out, encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 2 and not line.startswith("#"):
                held_out.add((parts[0], parts[1]))

    sentences = {}
    with open(args.ite / "sentences.csv", encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            sentences[int(float(row["SENTENCE_ID"]))] = words_of(row["SENTENCE"])
    sections = mobile_sections(args.ite)
    corrected = typed_before_correction(args.ite, sections)

    centres = key_centres()
    neighbours = {
        letter: sum(1 for other in centres
                    if other != letter and key_distance(centres, other, letter) <= NEIGHBOUR_DISTANCE)
        for letter in centres
    }
    totals = collections.Counter()
    with open(args.ite / "test_sections_labeled.csv", encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            section = int(float(row["TEST_SECTION_ID"]))
            if section not in sections:
                continue
            totals["sections"] += 1
            meant_words = sentences.get(int(float(row["SENTENCE_ID"])))
            typed_words = words_of(row["USER_INPUT"])
            if meant_words is None or len(meant_words) != len(typed_words):
                continue
            totals["aligned"] += 1
            for position, word in corrected.get(section, {}).items():
                if 0 <= position < len(typed_words) and word:
                    typed_words[position] = word[0]
            for typed, meant in zip(typed_words, meant_words):
                if not typed or not meant:
                    continue
                if (typed, meant) in held_out:
                    totals["held out"] += 1
                    continue
                totals["words"] += 1
                totals["taps"] += len(typed)
                totals["pairs"] += max(len(typed) - 1, 0)
                totals["unknown"] += fold(meant) not in packed
                for letter in meant:
                    if letter == "'":
                        totals["marks"] += 1
                    else:
                        totals["letters"] += 1
                        totals["neighbour slots"] += neighbours.get(letter, 0)
                        if letter in centres:
                            totals["far slots"] += len(centres) - 1 - neighbours[letter]
                for kind, typed_letter, meant_letter in alignment(typed, meant):
                    if kind == "add":
                        totals["added"] += 1
                    elif kind == "drop":
                        totals["dropped marks" if meant_letter == "'" else "dropped"] += 1
                    elif kind == "swap":
                        totals["swapped"] += 1
                    elif kind == "substitute":
                        distance = key_distance(centres, typed_letter, meant_letter)
                        if distance is not None and distance <= NEIGHBOUR_DISTANCE:
                            totals["neighbour"] += 1
                        else:
                            totals["far"] += 1

    for name, value in totals.items():
        print(f"{name:15s} {value:,}")
    rates = {
        "a tap of no letter, per tap": (totals["added"], totals["taps"]),
        "a letter with no tap, per letter": (totals["dropped"], totals["letters"]),
        "a mark with no tap, per mark": (totals["dropped marks"], totals["marks"]),
        "two taps swapped, per adjacent pair": (totals["swapped"], totals["pairs"]),
        "a letter typed as a neighbouring key, per letter": (totals["neighbour"], totals["letters"]),
        "a letter typed as one given neighbouring key, per neighbour":
            (totals["neighbour"], totals["neighbour slots"]),
        "a letter typed as a key further away, per letter": (totals["far"], totals["letters"]),
        "a letter typed as one given key further away, per such key":
            (totals["far"], totals["far slots"]),
        "the word meant is one the pack lacks, per word": (totals["unknown"], totals["words"]),
    }
    for name, (count, base) in rates.items():
        rate = count / base if base else 0.0
        print(f"{name}: {rate:.5f}  log {math.log(rate) if rate > 0 else float('-inf'):.2f}")


if __name__ == "__main__":
    main()
