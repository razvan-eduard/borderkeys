#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Derives a part-of-speech tag per word and a tag transition matrix from a UD treebank.

Why a treebank and not a tagger: a treebank carries a tag for every *token in context*, which is
what makes it possible to see that "la" is a preposition almost always and a noun occasionally.
A tagger would give one answer per word type and hide exactly the ambiguity that matters. It is
also one fewer dependency -- no runtime, no model file, no version to pin.

The output is what the pack compiler folds into the .bkd: a tag per word in the word list, and
the matrix of P(tag | previous tag). Both are quantised to a byte, on the same log scale the
n-gram probabilities already use.
"""

import argparse
import collections
import json
import math
import pathlib

# The n-gram model's scale, mirrored so the engine can add the two without converting either.
LOG_PROB_SCALE = 10.0
LOG_PROB_FLOOR = -25.5

# One byte per tag, and 255 of them cover 99.4% of Romanian tokens; everything rarer shares the
# last slot. The alternative -- a wider tag -- would spend memory on distinctions that appear a
# few hundred times in a treebank and never in a phone.
MAX_TAGS = 255
OTHER_TAG = 255


def tokens(path):
    """(form, upos, xpos) for every real token, skipping ranges and empty nodes."""
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
        yield c[1].lower(), c[3], c[4]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--treebank", type=pathlib.Path, nargs="+", required=True,
                        help="one or more .conllu files")
    parser.add_argument("--words", type=pathlib.Path, required=True,
                        help="the word list the pack is built from, one word per line with count")
    parser.add_argument("--out", type=pathlib.Path, required=True)
    parser.add_argument("--coarse", action="store_true",
                        help="use UPOS (16 tags) rather than the fine tagset")
    arguments = parser.parse_args()

    column = 1 if arguments.coarse else 2
    word_tags = collections.defaultdict(collections.Counter)
    tag_counts = collections.Counter()
    transitions = collections.defaultdict(collections.Counter)

    for path in arguments.treebank:
        previous = None
        for item in tokens(path):
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

    # P(tag | previous tag), add-one smoothed so an unseen pair is unlikely rather than
    # impossible -- a treebank is 8,000 sentences and absence in it is weak evidence.
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

    # One tag per word: the one it carries most often. The ambiguity this discards is measured
    # and reported, because it is the honest cost of a single byte.
    words = []
    for line in open(arguments.words, encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if p and p[0]:
            words.append(p[0])

    tags = bytearray(len(words))
    tagged = ambiguous = 0
    for i, word in enumerate(words):
        counter = word_tags.get(word)
        if not counter:
            tags[i] = OTHER_TAG
            continue
        tagged += 1
        if len(counter) > 1:
            ambiguous += 1
        tags[i] = index.get(counter.most_common(1)[0][0], OTHER_TAG)

    arguments.out.write_bytes(bytes(tags) + bytes(matrix))
    meta = {
        "tags": size,
        "tagset": ordered,
        "words": len(words),
        "tagged": tagged,
        "ambiguous_types": ambiguous,
        "token_coverage_of_tagset": round(100 * kept / total, 2),
        "bytes_tags": len(tags),
        "bytes_matrix": len(matrix),
    }
    print(json.dumps(meta, ensure_ascii=False, indent=2)[:400])
    print(f"tags {len(tags)} B + matrix {len(matrix)} B = {(len(tags)+len(matrix))/1024:.1f} KB")
    print(f"{tagged}/{len(words)} words tagged, {ambiguous} of them ambiguous in the treebank")


if __name__ == "__main__":
    main()
