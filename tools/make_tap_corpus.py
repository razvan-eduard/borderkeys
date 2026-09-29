#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Synthetic taps for measuring the touch model. Standard library only.

Words are drawn from a word list by frequency and typed one tap per letter. A tap lands at its
letter key's centre, moved by the profile's offset for that key plus Gaussian noise; the letter
typed is the key the tap falls on: the nearest letter row, then the nearest key in it. Offsets
and spreads are in key units, x in key widths and y in key heights, y growing downwards.

Output, one word per line in typing order, after `#` comment lines:

    intended<TAB>typed<TAB>x,y x,y ...

Usage
-----
    ./make_tap_corpus.py --profile low --taps 60000 --seed 1 > low.tsv
    ./make_tap_corpus.py --profile thumbs --taps 60000 --then right-thumb --then-taps 40000 \\
        > grip_change.tsv
"""

from __future__ import annotations

import argparse
import math
import random
import re
import sys
from pathlib import Path
from typing import Callable

REPOSITORY_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_LAYOUT = REPOSITORY_ROOT / "native-tests" / "data" / "qwerty_1080.layout"
DEFAULT_WORDS = REPOSITORY_ROOT / "dictionaries" / "en_US.tsv"

# The most frequent words the draw takes from, and the shortest.
VOCABULARY_SIZE = 20000
MIN_WORD_LENGTH = 2

LETTERS = re.compile(r"^[a-z]+$")


class Layout:
    def __init__(self, key_width: float, key_height: float, keys: dict[str, tuple[float, float]]):
        self.key_width = key_width
        self.key_height = key_height
        self.keys = keys
        self.rows: list[tuple[float, list[tuple[float, str]]]] = []
        for letter, (x, y) in sorted(keys.items(), key=lambda item: (item[1][1], item[1][0])):
            if not self.rows or self.rows[-1][0] != y:
                self.rows.append((y, []))
            self.rows[-1][1].append((x, letter))
        self.width = max(x for x, _ in keys.values()) + key_width / 2

    @staticmethod
    def load(path: Path) -> "Layout":
        key_width = key_height = 0.0
        keys: dict[str, tuple[float, float]] = {}
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if key_width == 0.0 and len(parts) == 2:
                key_width, key_height = float(parts[0]), float(parts[1])
                continue
            if len(parts) != 3:
                raise SystemExit(f"{path}: expected 'code x y', got {line!r}")
            keys[parts[0]] = (float(parts[1]), float(parts[2]))
        if not keys or key_width <= 0:
            raise SystemExit(f"{path}: no keys, or no key size header")
        return Layout(key_width, key_height, keys)

    def letter_at(self, x: float, y: float) -> str:
        """The letter key a tap at (x, y) falls on: the nearest row, then the nearest key."""
        _, row = min(self.rows, key=lambda each: abs(each[0] - y))
        return min(row, key=lambda each: abs(each[0] - x))[1]


# A profile maps a key's centre, in the layout's pixels, to its taps' mean offset (x, y), their
# spreads (x, y) and the correlation of the two, in key units.
Profile = Callable[[Layout, float, float], tuple[float, float, float, float, float]]


def centred(layout: Layout, x: float, y: float):
    return 0.0, 0.0, 0.22, 0.22, 0.0


def low(layout: Layout, x: float, y: float):
    return 0.0, 0.18, 0.20, 0.22, 0.0


def thumbs(layout: Layout, x: float, y: float):
    """Two thumbs: each half's taps land towards the middle and low."""
    left = x < layout.width / 2
    return (0.12 if left else -0.12), 0.12, 0.22, 0.22, (0.2 if left else -0.2)


def right_thumb(layout: Layout, x: float, y: float):
    """One right thumb resting below the bottom row's right end: the farther the key, the more
    its taps fall short towards the thumb and the wider they spread, along the reach."""
    bottom = max(row_y for row_y, _ in layout.rows) + layout.key_height
    anchor_x, anchor_y = layout.width, bottom
    far_x, far_y = min(layout.keys.values(), key=lambda centre: (centre[0], centre[1]))
    reach = math.hypot(anchor_x - far_x, anchor_y - far_y)
    dx, dy = anchor_x - x, anchor_y - y
    distance = math.hypot(dx, dy)
    share = distance / reach
    shift = 0.25 * share
    spread = 0.17 + 0.13 * share
    return shift * dx / distance, shift * dy / distance, spread, spread, 0.3 * share


def precise(layout: Layout, x: float, y: float):
    return 0.03, 0.06, 0.16, 0.17, 0.0


def sloppy(layout: Layout, x: float, y: float):
    return 0.0, 0.10, 0.28, 0.28, 0.0


PROFILES: dict[str, Profile] = {
    "centred": centred,
    "low": low,
    "thumbs": thumbs,
    "right-thumb": right_thumb,
    "precise": precise,
    "sloppy": sloppy,
}


def load_words(path: Path) -> tuple[list[str], list[float]]:
    """The most frequent lower-case words of the list, with their counts."""
    words: list[str] = []
    counts: list[float] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        parts = raw.split("\t")
        if len(parts) < 2 or not LETTERS.match(parts[0]) or len(parts[0]) < MIN_WORD_LENGTH:
            continue
        words.append(parts[0])
        counts.append(float(parts[1]))
        if len(words) == VOCABULARY_SIZE:
            break
    if not words:
        raise SystemExit(f"{path}: no words")
    return words, counts


def tap(layout: Layout, profile: Profile, letter: str, draw: random.Random) -> tuple[float, float]:
    x, y = layout.keys[letter]
    offset_x, offset_y, spread_x, spread_y, correlation = profile(layout, x, y)
    first = draw.gauss(0.0, 1.0)
    second = draw.gauss(0.0, 1.0)
    noise_x = spread_x * first
    noise_y = spread_y * (correlation * first + math.sqrt(1.0 - correlation * correlation) * second)
    return x + (offset_x + noise_x) * layout.key_width, y + (offset_y + noise_y) * layout.key_height


def write_segment(layout: Layout, profile: Profile, taps: int, words: list[str],
                  counts: list[float], draw: random.Random, out) -> None:
    typed_taps = 0
    while typed_taps < taps:
        word = draw.choices(words, counts)[0]
        points = [tap(layout, profile, letter, draw) for letter in word]
        typed = "".join(layout.letter_at(x, y) for x, y in points)
        out.write(f"{word}\t{typed}\t" + " ".join(f"{x:.1f},{y:.1f}" for x, y in points) + "\n")
        typed_taps += len(word)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--profile", choices=sorted(PROFILES), required=True)
    parser.add_argument("--taps", type=int, required=True, help="letter taps to type")
    parser.add_argument("--then", choices=sorted(PROFILES), help="a second profile, typed after")
    parser.add_argument("--then-taps", type=int, default=0,
                        help="letter taps of the second profile")
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--layout", type=Path, default=DEFAULT_LAYOUT)
    parser.add_argument("--words", type=Path, default=DEFAULT_WORDS)
    arguments = parser.parse_args()

    layout = Layout.load(arguments.layout)
    words, counts = load_words(arguments.words)
    draw = random.Random(arguments.seed)
    out = sys.stdout
    out.write(f"# profile {arguments.profile}, {arguments.taps} taps, seed {arguments.seed}\n")
    write_segment(layout, PROFILES[arguments.profile], arguments.taps, words, counts, draw, out)
    if arguments.then:
        out.write(f"# then profile {arguments.then}, {arguments.then_taps} taps\n")
        write_segment(layout, PROFILES[arguments.then], arguments.then_taps, words, counts, draw,
                      out)


if __name__ == "__main__":
    main()
