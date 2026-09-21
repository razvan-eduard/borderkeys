#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Builds a replay layout and a replay corpus for one of FUTO's non-QWERTY keyboards.

`futo_to_corpus.py` converts the held-out split against a layout file that already exists.
This covers the case where it does not: `swipe-5` is FUTO's multi-layout collection, and nothing
in `native-tests/data` describes azerty, qwertz, dvorak, german or spanish. Both outputs are
written from the same canvas, so the gesture coordinates and the key centres land in one pixel
space and `tools/gesture_replay.py` measures the decoder rather than a coordinate mismatch.

Key size comes from the column pitch measured inside a row and the row pitch across rows, not
from the smallest gap between any two keys: keyboard rows are staggered, so that gap is half a
key. On `qwerty` this reproduces the hand-written `native-tests/data/qwerty_1080.layout` at
108x160 px, which is what says the two agree.

    python3 tools/swipe_model/futo_layout_corpus.py german \\
        --dataset german.jsonl --out-layout german.layout --out-corpus german.csv \\
        --dictionary dictionaries/de_DE.tsv
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import math
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from futo_layout import load_futo_layout  # noqa: E402

CANVAS_WIDTH, CANVAS_HEIGHT = 1080.0, 480.0

# Written at the top of every generated layout. Kept as a block with each tag ending its own
# line, the same shape tools/gen_keys.py uses: `reuse lint` reads a tag wherever it appears,
# so one built inline with the newline escape trailing it is read as a malformed expression.
LAYOUT_HEADER = """# BorderKeys replay layout: FUTO {name}, {width} px canvas.
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
# keyWidth keyHeight
"""


def gaps(values: list[float], tolerance: float = 1e-6) -> list[float]:
    """Positive differences between consecutive distinct values."""
    unique = sorted({round(value, 6) for value in values})
    return [b - a for a, b in zip(unique, unique[1:]) if b - a > tolerance]


def pitches(centres: dict[str, tuple[float, float]]) -> tuple[float, float]:
    """Column pitch, measured inside a row, and row pitch, measured across rows."""
    rows: dict[float, list[float]] = {}
    for cx, cy in centres.values():
        rows.setdefault(round(cy, 4), []).append(cx)
    columns = [min(gaps(xs)) for xs in rows.values() if gaps(xs)]
    down = gaps(list(rows.keys()))
    return (min(columns) if columns else 0.1, min(down) if down else 0.25)


def load_folder(dictionary: Path):
    """`build_dict.fold_word` plus the set of folded keys a pack holds."""
    root = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location("build_dict", root / "tools/build_dict.py")
    build_dict = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(build_dict)
    keys = set()
    with dictionary.open(encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 2:
                keys.add(build_dict.fold_word(parts[0]))
    return build_dict.fold_word, keys


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("layout", help="a FUTO layout name, as futo_layout.LAYOUT_NAMES lists")
    parser.add_argument("--dataset", type=Path, required=True,
                        help="JSON lines of swipe-5 rows for this layout: word, data")
    parser.add_argument("--out-layout", type=Path, required=True)
    parser.add_argument("--out-corpus", type=Path, required=True)
    parser.add_argument("--dictionary", type=Path, default=None,
                        help="a dictionaries/<tag>.tsv. Keeps only words the pack can produce, "
                             "so a miss is a decoding failure rather than a vocabulary gap.")
    parser.add_argument("--limit", type=int, default=500)
    parser.add_argument("--seed", type=int, default=20260921)
    parser.add_argument("--min-travel", type=float, default=0.25,
                        help="drop traces shorter than this many key widths. A touch that never "
                             "leaves the touch slop is a tap on this keyboard and is typed as "
                             "one, so it never reaches the gesture decoder.")
    arguments = parser.parse_args()

    centres = load_futo_layout(arguments.layout)
    column_pitch, row_pitch = pitches(centres)
    key_width = column_pitch * CANVAS_WIDTH
    key_height = row_pitch * CANVAS_HEIGHT

    with arguments.out_layout.open("w", encoding="utf-8") as handle:
        handle.write(LAYOUT_HEADER.format(name=arguments.layout, width=int(CANVAS_WIDTH)))
        handle.write(f"{key_width:.1f} {key_height:.1f}\n")
        for letter, (cx, cy) in sorted(centres.items()):
            handle.write(f"{letter} {cx * CANVAS_WIDTH:.1f} {cy * CANVAS_HEIGHT:.1f}\n")

    fold = keys = None
    if arguments.dictionary is not None:
        fold, keys = load_folder(arguments.dictionary)

    letters = set(centres)
    rows = []
    unreachable = stationary = 0
    with arguments.dataset.open(encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            word = row["word"].lower()
            points = row.get("data")
            if not isinstance(points, list) or len(points) < 2:
                continue  # dual-finger records carry a dict here, not a list
            if any(character not in letters for character in word):
                continue
            if keys is not None and fold(word) not in keys:
                unreachable += 1
                continue
            travel = sum(math.hypot((b["x"] - a["x"]) * CANVAS_WIDTH,
                                    (b["y"] - a["y"]) * CANVAS_HEIGHT)
                         for a, b in zip(points, points[1:]))
            if travel < arguments.min_travel * key_width:
                stationary += 1
                continue
            rows.append((word, points))

    if len(rows) > arguments.limit:
        rows = random.Random(arguments.seed).sample(rows, arguments.limit)

    with arguments.out_corpus.open("w", encoding="utf-8") as handle:
        handle.write("id,word,x,y,t\n")
        for index, (word, points) in enumerate(rows):
            start = points[0]["t"]
            for point in points:
                handle.write(f"g{index:05d},{word},{point['x'] * CANVAS_WIDTH:.1f},"
                             f"{point['y'] * CANVAS_HEIGHT:.1f},{int(point['t'] - start)}\n")

    print(f"{arguments.layout}: {len(rows)} gestures, key {key_width:.1f}x{key_height:.1f} px, "
          f"{unreachable} outside the dictionary, {stationary} too short to be a gesture",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
