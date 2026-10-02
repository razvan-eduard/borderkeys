#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Derives a part-of-speech tag per word and a tag transition matrix from a UD treebank.

The output is what the pack compiler folds into the .bkd: a tag per word in the word list, and
the matrix of P(tag | previous tag). Both are quantised to a byte, on the same log scale the
n-gram probabilities use.
"""

import argparse
import collections
import json
import math
import pathlib

from build_dict import plain_apostrophes

# The n-gram model's scale, mirrored so the engine can add the two without converting either.
LOG_PROB_SCALE = 10.0
LOG_PROB_FLOOR = -25.5

# One byte per tag; every tag rarer than the commonest 255 shares the last slot.
MAX_TAGS = 255
OTHER_TAG = 255


# The features agreement is expressed through, in the order they are composed into a tag.
FEATURES = ("Gender", "Number", "Case", "Person", "VerbForm", "Mood", "Definite", "PronType")


def compose(upos, feats):
    """A tag from the universal columns, UPOS plus the agreement features, whether or not a
    treebank fills XPOS."""
    if feats == "_":
        return upos
    values = dict(p.split("=", 1) for p in feats.split("|") if "=" in p)
    parts = [upos]
    for feature in FEATURES:
        value = values.get(feature)
        if value is not None:
            parts.append(value.split(",")[0])
    return ".".join(parts)


# Below this many distinct XPOS values the composed tag is used instead.
MIN_USEFUL_XPOS = 20

# At or above this share of tokens whose XPOS repeats their UPOS, the composed tag is used instead.
XPOS_COPY_SHARE = 0.9


def rows(path):
    """(form, upos, xpos, feats) for every real token, with None between sentences."""
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line:
            yield None
            continue
        if line.startswith("#"):
            continue
        c = line.split("\t")
        if "-" in c[0] or "." in c[0]:
            continue
        yield plain_apostrophes(c[1].lower()), c[3], c[4], c[5]


def uses_xpos(paths):
    """Whether the treebank fills its own fine tagset (XPOS), which is then preferred to a
    composed one: more than MIN_USEFUL_XPOS values, and not a copy of UPOS."""
    seen = set()
    tokens = 0
    copies = 0
    for path in paths:
        for row in rows(path):
            if row is None:
                continue
            seen.add(row[2])
            tokens += 1
            copies += row[1] == row[2]
    return len(seen) > MIN_USEFUL_XPOS and copies < XPOS_COPY_SHARE * tokens


def tokens(path, xpos):
    """(form, upos, tag) for every real token, skipping ranges and empty nodes."""
    for row in rows(path):
        if row is None:
            yield None
            continue
        form, upos, fine, feats = row
        yield form, upos, (fine if xpos else compose(upos, feats))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--treebank", type=pathlib.Path, nargs="+", required=True,
                        help="one or more .conllu files")
    parser.add_argument("--out", type=pathlib.Path, required=True,
                        help="JSON, read by tools/build_dict.py --grammar")
    parser.add_argument("--coarse", action="store_true",
                        help="use bare UPOS rather than UPOS plus agreement features")
    parser.add_argument("--composed", action="store_true",
                        help="use UPOS plus agreement features even where the treebanks fill XPOS")
    arguments = parser.parse_args()

    column = 1 if arguments.coarse else 2
    xpos = not arguments.coarse and not arguments.composed and uses_xpos(arguments.treebank)
    word_tags = collections.defaultdict(collections.Counter)
    tag_counts = collections.Counter()
    transitions = collections.defaultdict(collections.Counter)

    for path in arguments.treebank:
        previous = None
        for item in tokens(path, xpos):
            if item is None:
                previous = None
                continue
            form, tag = item[0], item[column]
            word_tags[form][tag] += 1
            tag_counts[tag] += 1
            if previous is not None:
                transitions[previous][tag] += 1
            previous = tag

    # Tags by how much text they account for, so the byte is spent on what people write.
    ordered = [t for t, _ in tag_counts.most_common(MAX_TAGS)]
    index = {t: i for i, t in enumerate(ordered)}
    kept = sum(tag_counts[t] for t in ordered)
    total = sum(tag_counts.values())

    # P(tag | previous tag), add-one smoothed.
    size = len(ordered)
    matrix = bytearray(size * size)
    for previous, counter in transitions.items():
        row = index.get(previous)
        if row is None:
            continue
        denominator = sum(counter.values()) + size
        for tag, i in index.items():
            p = (counter.get(tag, 0) + 1) / denominator
            q = max(LOG_PROB_FLOOR, math.log(p))
            matrix[row * size + i] = min(255, int(round(-q * LOG_PROB_SCALE)))

    # One tag per word, the one it carries most often, keyed by word. The ambiguity this
    # discards is reported.
    tags = {}
    ambiguous = 0
    for word, counter in word_tags.items():
        if len(counter) > 1:
            ambiguous += 1
        tag = index.get(counter.most_common(1)[0][0])
        if tag is not None:
            tags[word] = tag

    arguments.out.write_text(json.dumps({
        "tag_count": size,
        "tagset": ordered,
        "tags": tags,
        "transitions": bytes(matrix).hex(),
    }, ensure_ascii=False), encoding="utf-8")

    source = "the treebank's own tagset" if xpos else "UPOS plus agreement features"
    print(f"{size} tags from {source}, covering {100 * kept / total:.2f}% of tokens")
    print(f"{len(tags)} words tagged, {ambiguous} of them ambiguous in the treebank")
    print(f"matrix {len(matrix) / 1024:.1f} KB, one byte per word on top of that")


if __name__ == "__main__":
    main()
