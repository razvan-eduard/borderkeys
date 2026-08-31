#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Measures swipe decoding accuracy over a recorded corpus.

Without this, a change to the decoder can only be judged by swiping a few words and forming an
impression. An impression is not a measurement: the constants in the scorer trade one class of
word against another, and the only way to know whether a change helped is to replay the same
gestures through the old and the new build and compare two numbers.

Standard library only, like every other tool here.

Formats
-------
Layout (`native-tests/data/*.layout`), one key per line after a header:

    # keyWidth keyHeight
    108 160
    q 54 80
    ...

Corpus (`native-tests/data/*.csv`), one touch sample per line:

    id,word,x,y,t

Gestures are grouped by `id` and must appear contiguously. One sample per line rather than one
gesture per line so the file diffs usefully when a recording is added or corrected.

Usage
-----
    ./gesture_replay.py --synthesise --layout native-tests/data/qwerty_1080.layout \\
        --words the,there,keyboard --out native-tests/data/gestures.csv
    ./gesture_replay.py --binary native-tests/build/gesture_replay \\
        --pack keyboard/src/main/assets/dict/en_US.bkd
    ./gesture_replay.py --check-regression
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_LAYOUT = REPOSITORY_ROOT / "native-tests" / "data" / "qwerty_1080.layout"
DEFAULT_CORPUS = REPOSITORY_ROOT / "native-tests" / "data" / "gestures.csv"
DEFAULT_BINARY = REPOSITORY_ROOT / "native-tests" / "build" / "gesture_replay"
BASELINE = REPOSITORY_ROOT / "docs" / "gesture-accuracy.json"


# --------------------------------------------------------------------------------------
# Layout and corpus
# --------------------------------------------------------------------------------------


class Layout:
    def __init__(self, key_width: float, key_height: float, keys: dict[str, tuple[float, float]]):
        self.key_width = key_width
        self.key_height = key_height
        self.keys = keys

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


class Gesture:
    def __init__(self, identifier: str, word: str):
        self.identifier = identifier
        self.word = word
        self.samples: list[tuple[float, float, int]] = []


def load_corpus(path: Path) -> list[Gesture]:
    gestures: list[Gesture] = []
    current: Gesture | None = None
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("id,"):
            continue
        parts = line.split(",")
        if len(parts) != 5:
            raise SystemExit(f"{path}:{number}: expected 'id,word,x,y,t'")
        identifier, word = parts[0], parts[1]
        if current is None or current.identifier != identifier:
            current = Gesture(identifier, word)
            gestures.append(current)
        current.samples.append((float(parts[2]), float(parts[3]), int(parts[4])))
    return gestures


# --------------------------------------------------------------------------------------
# Synthesis
#
# A synthesised gesture is not a substitute for a recorded one -- a real finger overshoots
# corners, slows before a turn and wobbles in ways no generator reproduces. It is what makes the
# harness runnable and the regression gate meaningful before any recordings exist, and the
# corpus format is the same either way, so recordings drop straight in.
# --------------------------------------------------------------------------------------


def synthesise(layout: Layout, word: str, jitter: float, seed: int) -> list[tuple[float, float, int]]:
    state = seed & 0xFFFFFFFF

    def random_unit() -> float:
        nonlocal state
        state = (state * 1664525 + 1013904223) & 0xFFFFFFFF
        return ((state >> 8) & 0xFFFF) / 65535.0

    anchors: list[tuple[float, float]] = []
    for character in word:
        position = layout.keys.get(character)
        if position is None:
            return []
        if anchors and anchors[-1] == position:
            continue  # a doubled letter is one pass over the key
        anchors.append(position)
    if len(anchors) < 2:
        return []

    samples: list[tuple[float, float, int]] = []
    time = 0
    for index in range(len(anchors) - 1):
        (x0, y0), (x1, y1) = anchors[index], anchors[index + 1]
        distance = ((x1 - x0) ** 2 + (y1 - y0) ** 2) ** 0.5
        steps = max(6, int(distance / 18.0) + 6)
        for step in range(steps):
            fraction = step / steps
            samples.append((
                x0 + (x1 - x0) * fraction + (random_unit() - 0.5) * 2 * jitter,
                y0 + (y1 - y0) * fraction + (random_unit() - 0.5) * 2 * jitter,
                time,
            ))
            time += 8 + int(random_unit() * 6)
    samples.append((anchors[-1][0], anchors[-1][1], time + 10))
    return samples


def write_corpus(path: Path, gestures: list[Gesture]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        handle.write("id,word,x,y,t\n")
        for gesture in gestures:
            for x, y, t in gesture.samples:
                handle.write(f"{gesture.identifier},{gesture.word},{x:.1f},{y:.1f},{t}\n")


# --------------------------------------------------------------------------------------
# Replay
# --------------------------------------------------------------------------------------


def replay(binary: Path, pack: Path, layout: Path, corpus: Path) -> dict:
    """Runs the native harness and parses its verdict per gesture.

    The binary is expected to print one `word<TAB>rank` line per gesture, where rank is the
    zero-based position of the expected word among the candidates, or -1 for a miss.
    """
    result = subprocess.run(
        [str(binary), str(pack), str(layout), str(corpus)],
        capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        sys.stderr.write(result.stdout)
        sys.stderr.write(result.stderr)
        raise SystemExit(f"{binary} exited {result.returncode}")

    total = top1 = top3 = 0
    misses: list[str] = []
    for line in result.stdout.splitlines():
        if "\t" not in line:
            continue
        word, _, rank_text = line.partition("\t")
        try:
            rank = int(rank_text.strip())
        except ValueError:
            continue
        total += 1
        if rank == 0:
            top1 += 1
        if 0 <= rank < 3:
            top3 += 1
        else:
            misses.append(word)
    if total == 0:
        raise SystemExit(f"{binary} reported no gestures")
    return {
        "gestures": total,
        "top1": round(100.0 * top1 / total, 2),
        "top3": round(100.0 * top3 / total, 2),
        "misses": misses[:20],
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--layout", type=Path, default=DEFAULT_LAYOUT)
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--binary", type=Path, default=DEFAULT_BINARY)
    parser.add_argument("--pack", type=Path)
    parser.add_argument("--synthesise", action="store_true",
                        help="generate a corpus instead of replaying one")
    parser.add_argument("--words", help="comma separated, for --synthesise")
    parser.add_argument("--jitter", type=float, default=14.0)
    parser.add_argument("--out", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--record-baseline", action="store_true",
                        help="write the measured accuracy to docs/gesture-accuracy.json")
    parser.add_argument("--check-regression", action="store_true",
                        help="fail if accuracy fell below the recorded baseline")
    arguments = parser.parse_args(argv)

    if arguments.synthesise:
        layout = Layout.load(arguments.layout)
        words = [w.strip() for w in (arguments.words or "").split(",") if w.strip()]
        if not words:
            raise SystemExit("--synthesise needs --words")
        gestures: list[Gesture] = []
        for index, word in enumerate(words):
            gesture = Gesture(f"g{index:04d}", word)
            gesture.samples = synthesise(layout, word, arguments.jitter, 0x5EED + index * 7919)
            if not gesture.samples:
                sys.stderr.write(f"skipped {word!r}: a letter is not on this layout\n")
                continue
            gestures.append(gesture)
        write_corpus(arguments.out, gestures)
        total = sum(len(g.samples) for g in gestures)
        print(f"wrote {arguments.out}: {len(gestures)} gestures, {total} samples")
        return 0

    if not arguments.corpus.is_file():
        print(f"::notice::{arguments.corpus} does not exist yet — nothing to replay")
        return 0
    gestures = load_corpus(arguments.corpus)
    print(f"corpus: {len(gestures)} gestures, "
          f"{sum(len(g.samples) for g in gestures)} samples, from {arguments.corpus}")

    if not arguments.binary.is_file():
        # The native harness is built by native-tests/, which arrives with step 8. Saying so is
        # the point: a check that silently does nothing reads exactly like a check that passed.
        print(f"::notice::{arguments.binary} not built — accuracy not measured, nothing compared")
        return 0
    if arguments.pack is None:
        raise SystemExit("--pack is required to replay")

    measured = replay(arguments.binary, arguments.pack, arguments.layout, arguments.corpus)
    print(f"top-1 {measured['top1']}%  top-3 {measured['top3']}%  "
          f"over {measured['gestures']} gestures")
    if measured["misses"]:
        print("misses:", ", ".join(measured["misses"]))

    if arguments.record_baseline:
        BASELINE.parent.mkdir(parents=True, exist_ok=True)
        BASELINE.write_text(json.dumps({
            "corpus": str(arguments.corpus.relative_to(REPOSITORY_ROOT)),
            "gestures": measured["gestures"],
            "top1": measured["top1"],
            "top3": measured["top3"],
        }, indent=2) + "\n", encoding="utf-8")
        print(f"baseline written to {BASELINE}")
        return 0

    if arguments.check_regression:
        if not BASELINE.is_file():
            print(f"::notice::{BASELINE} does not exist — nothing to compare against")
            return 0
        baseline = json.loads(BASELINE.read_text(encoding="utf-8"))
        # A tolerance, not equality: the corpus is fixed and the decoder is deterministic, so
        # any drop is real, but a rounding difference between platforms should not fail a build.
        tolerance = 0.5
        if measured["top1"] + tolerance < baseline["top1"]:
            print(f"::error::top-1 fell from {baseline['top1']}% to {measured['top1']}%")
            return 1
        if measured["top3"] + tolerance < baseline["top3"]:
            print(f"::error::top-3 fell from {baseline['top3']}% to {measured['top3']}%")
            return 1
        print(f"no regression against {baseline['top1']}% / {baseline['top3']}%")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
