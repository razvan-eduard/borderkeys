#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Converts a held-out FUTO split into the replay corpus format.

`data/*.jsonl` carries normalised coordinates and real timestamps; `tools/gesture_replay.py` and
`tools/tcn_replay.py` read `id,word,x,y,t` in the pixel space of a replay layout. This maps one
to the other so both shipped decoders can be measured on the same recorded traces.

The scale comes from the layout file, not from a constant: FUTO normalises to [0,1] across the
keyboard, and a layout's own extent is `max(centre) + half a key` on each axis. For
`native-tests/data/qwerty_1080.layout` that is 1080 x 480, which reproduces FUTO's own key
centres to within 0.31 px -- a third of one percent of a key.

Timestamps keep their real spacing and are rebased to zero per gesture -- the recordings carry
absolute epoch milliseconds, which do not fit the replay format's integer. Both decoders
resample on time, so synthesising the spacing would measure the synthesiser rather than the
decoder.

    python3 tools/swipe_model/futo_to_corpus.py data/test.jsonl \\
        --layout ../../native-tests/data/qwerty_1080.layout --limit 2000 > out.csv
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import random
import sys
from pathlib import Path


def layout_extent(path: Path) -> tuple[float, float]:
    """The keyboard's width and height in pixels, from its key centres and key size."""
    key_width = key_height = None
    centres = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) == 2 and key_width is None:
            key_width, key_height = float(parts[0]), float(parts[1])
        elif len(parts) == 3:
            centres.append((float(parts[1]), float(parts[2])))
    if key_width is None or not centres:
        raise SystemExit(f"{path}: no key size and centres found")
    return (max(x for x, _ in centres) + key_width / 2,
            max(y for _, y in centres) + key_height / 2)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("split", type=Path, help="a data/*.jsonl split")
    parser.add_argument("--layout", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--seed", type=int, default=None,
                        help="sample --limit gestures at random rather than taking the first "
                             "ones. The split is in collection order, so a head slice is not "
                             "representative: the first 500 score ten points below the first "
                             "2000 on the same decoder.")
    parser.add_argument("--dictionary", type=Path, default=None,
                        help="a dictionaries/<tag>.tsv. Keeps only words the pack can produce, "
                             "so the measurement is of the decoder rather than of vocabulary "
                             "coverage. Membership is tested on the folded key, which is what "
                             "the pack is keyed by.")
    parser.add_argument("--lowercase-only", action="store_true",
                        help="keep only a-z words, which is what the English pack holds")
    arguments = parser.parse_args()

    width, height = layout_extent(arguments.layout)

    packed = None
    if arguments.dictionary is not None:
        root = Path(__file__).resolve().parents[2]
        spec = importlib.util.spec_from_file_location("build_dict", root / "tools/build_dict.py")
        build_dict = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(build_dict)
        packed = set()
        with arguments.dictionary.open(encoding="utf-8") as handle:
            for line in handle:
                parts = line.rstrip("\n").split("\t")
                if len(parts) >= 2:
                    packed.add(build_dict.fold_word(parts[0]))

    rows = []
    unreachable = 0
    with arguments.split.open(encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            word = row["word"]
            if not word.isascii() or (arguments.lowercase_only and not word.isalpha()):
                continue
            if packed is not None:
                candidate = word.lower() if arguments.lowercase_only else word
                if build_dict.fold_word(candidate) not in packed:
                    unreachable += 1
                    continue
            rows.append(row)
    skipped = unreachable
    if arguments.limit is not None and arguments.limit < len(rows):
        if arguments.seed is not None:
            rows = random.Random(arguments.seed).sample(rows, arguments.limit)
        else:
            rows = rows[:arguments.limit]

    print("id,word,x,y,t")
    kept = 0
    for row in rows:
        word = row["word"].lower() if arguments.lowercase_only else row["word"]
        identifier = f"f{kept:05d}"
        start = row["ts"][0] if row["ts"] else 0
        for x, y, t in zip(row["xs"], row["ys"], row["ts"]):
            print(f"{identifier},{word},{x * width:.1f},{y * height:.1f},{int(t - start)}")
        kept += 1
    print(f"{kept} gestures, {skipped} outside the dictionary", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
