#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Synthesises swipes on any layout for a language's frequent words.

Generators:

    polyline  straight lines through the word's key centres, with gesture_replay.py's jitter
              (±14 px on the 1080-px layout) and timing (8–14 ms a sample)
    flow      the learned residual model (flow.py) sampled around the same ideal path

Words are spelled as the layout can type them: a letter it lacks is replaced by the one it is
projected to, or the word is left out. Every HOLD_OUT_EVERY-th word by frequency rank is kept for
evaluation and never trained on. Records are written as train.py reads them: the word, and x, y in
[0,1] of the key area the runtime normalises against (the extent of the letter keys), with the
timestamps.
"""

from __future__ import annotations

import argparse
import bisect
import json
import math
import random
import sys
import unicodedata
from pathlib import Path

import numpy as np

# Letters a layout has no key for, written as the letter it does have.
PROJECTIONS = {"ё": "е", "ъ": "ь", "ѝ": "и", "ς": "σ", "ך": "כ", "ם": "מ", "ן": "נ", "ף": "פ", "ץ": "צ"}

# Every HOLD_OUT_EVERY-th word by frequency rank is kept for evaluation, never for training.
HOLD_OUT_EVERY = 7

# gesture_replay.py's polyline: jitter in layout pixels, a sample every SAMPLE_SPACING pixels plus
# MIN_SAMPLES per segment, 8 + 0..5 ms a sample, the last sample 10 ms after.
POLYLINE_JITTER = 14.0
SAMPLE_SPACING = 18.0
MIN_SAMPLES = 6


class ReplayLayout:
    """A replay layout's letter centres in pixels, and the key area the runtime normalises against."""

    def __init__(self, path: Path):
        self.key_width = self.key_height = 0.0
        self.centres: dict[str, tuple[float, float]] = {}
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if self.key_width == 0.0 and len(parts) == 2:
                self.key_width, self.key_height = float(parts[0]), float(parts[1])
                continue
            self.centres[parts[0]] = (float(parts[1]), float(parts[2]))
        self.area_w = max(x for x, _ in self.centres.values()) + self.key_width / 2
        self.area_h = max(y for _, y in self.centres.values()) + self.key_height / 2

    def normalised(self) -> dict[str, tuple[float, float]]:
        return {c: (x / self.area_w, y / self.area_h) for c, (x, y) in self.centres.items()}


ASSETS = Path(__file__).resolve().parent.parent.parent / "keyboard/src/main/assets"


def long_press(layout: Path) -> dict[str, str]:
    """Each letter on a key's long press for [layout]'s language, to the key's letter, from
    `assets/accents/<tag>.json`; the decoder reaches the same letters through the same keys."""
    name = layout.name.removesuffix("_1080.layout")
    asset = ASSETS / "layouts" / f"{name}.json"
    if not asset.is_file():
        return {}
    accents = ASSETS / "accents" / f"{json.loads(asset.read_text(encoding='utf-8')).get('languageTag', 'und')}.json"
    if not accents.is_file():
        return {}
    out: dict[str, str] = {}
    for base, forms in json.loads(accents.read_text(encoding="utf-8")).get("keys", {}).items():
        for form in forms:
            out.setdefault(form.lower(), base.lower())
    return out


def project(word: str, keys: set[str], aliases: dict[str, str] | None = None) -> str | None:
    out = []
    for character in word.lower():
        if character in keys:
            out.append(character)
        elif aliases and aliases.get(character) in keys:
            out.append(aliases[character])
        elif PROJECTIONS.get(character) in keys:
            out.append(PROJECTIONS[character])
        else:
            bare = "".join(c for c in unicodedata.normalize("NFD", character) if not unicodedata.combining(c))
            if bare in keys:
                out.append(bare)
            elif PROJECTIONS.get(bare) in keys:
                out.append(PROJECTIONS[bare])
            else:
                return None
    return "".join(out)


def anchors(word: str, centres: dict[str, tuple[float, float]]) -> np.ndarray | None:
    """The word's key centres in order, a doubled letter once; None below two."""
    points: list[tuple[float, float]] = []
    for character in word:
        position = centres.get(character)
        if position is None:
            return None
        if points and points[-1] == position:
            continue
        points.append(position)
    return np.array(points, dtype=np.float64) if len(points) >= 2 else None


def polyline(path: np.ndarray, rng: random.Random) -> tuple[np.ndarray, list[int]]:
    """gesture_replay.py's synthetic swipe along [path], in the path's pixels."""
    points: list[tuple[float, float]] = []
    times: list[int] = []
    time = 0
    for index in range(len(path) - 1):
        (x0, y0), (x1, y1) = path[index], path[index + 1]
        distance = math.hypot(x1 - x0, y1 - y0)
        steps = max(MIN_SAMPLES, int(distance / SAMPLE_SPACING) + MIN_SAMPLES)
        for step in range(steps):
            fraction = step / steps
            points.append((
                x0 + (x1 - x0) * fraction + (rng.random() - 0.5) * 2 * POLYLINE_JITTER,
                y0 + (y1 - y0) * fraction + (rng.random() - 0.5) * 2 * POLYLINE_JITTER,
            ))
            times.append(time)
            time += 8 + int(rng.random() * 6)
    points.append((float(path[-1][0]), float(path[-1][1])))
    times.append(time + 10)
    return np.array(points, dtype=np.float64), times


def frequent_words(
    words: Path, keys: set[str], pool: int, hold_out: bool, aliases: dict[str, str] | None = None,
) -> tuple[list[tuple[str, str]], list[float]]:
    """(word, spelling on the layout) for the training or the held-out ranks, with draw weights."""
    rows = words.read_text(encoding="utf-8").splitlines()[:pool]
    chosen: list[tuple[str, str]] = []
    weights: list[float] = []
    for rank, row in enumerate(rows):
        if (rank % HOLD_OUT_EVERY == 0) != hold_out:
            continue
        parts = row.split("\t")
        spelled = project(parts[0], keys, aliases)
        if spelled is None or len(spelled) < 2:
            continue
        chosen.append((parts[0].lower(), spelled))
        frequency = float(parts[1]) if len(parts) > 1 and parts[1].replace(".", "", 1).isdigit() else 1.0
        # Square-root weights.
        weights.append(math.sqrt(max(frequency, 1.0)))
    return chosen, weights


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--generator", choices=["polyline", "flow"], required=True)
    parser.add_argument("--layout", type=Path, required=True)
    parser.add_argument("--words", type=Path, required=True, help="a word list, word<TAB>frequency, most frequent first")
    parser.add_argument("--flow", type=Path, help="the flow generator's checkpoint")
    parser.add_argument("--count", type=int, default=200_000)
    parser.add_argument("--pool", type=int, default=40_000, help="the most frequent words drawn from")
    parser.add_argument("--hold-out", action="store_true", help="draw only the held-out words")
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    layout = ReplayLayout(args.layout)
    words, weights = frequent_words(args.words, set(layout.centres), args.pool, args.hold_out, long_press(args.layout))
    if not words:
        raise SystemExit("synthesise: no word of the list can be typed on this layout")
    print(f"synthesise: {len(words)} words", file=sys.stderr)

    sampler = None
    if args.generator == "flow":
        if args.flow is None:
            raise SystemExit("synthesise: --generator flow needs --flow")
        from flow import FlowSampler
        sampler = FlowSampler(args.flow, layout, seed=args.seed)

    cumulative = []
    total = 0.0
    for weight in weights:
        total += weight
        cumulative.append(total)

    rng = random.Random(args.seed * 7919 + 17)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    written = 0
    with args.out.open("w", encoding="utf-8") as out:
        while written < args.count:
            word, spelled = words[bisect.bisect_left(cumulative, rng.random() * total)]
            path = anchors(spelled, layout.centres)
            if path is None:
                continue
            if sampler is None:
                points, times = polyline(path, rng)
            else:
                points, times = sampler.sample(path)
            xs = np.clip(points[:, 0] / layout.area_w, 0.0, 1.0)
            ys = np.clip(points[:, 1] / layout.area_h, 0.0, 1.0)
            record = {
                "word": word,
                "xs": [round(float(x), 5) for x in xs],
                "ys": [round(float(y), 5) for y in ys],
                "ts": [int(t) for t in times],
            }
            out.write(json.dumps(record, ensure_ascii=False) + "\n")
            written += 1
    print(f"synthesise: wrote {written} swipes to {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
