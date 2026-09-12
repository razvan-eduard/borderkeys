#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Measures the trained TCN swipe decoder's accuracy over the same recorded corpus
gesture_replay.py measures Shark2 against -- the two are comparable numbers only because they
replay the identical gestures.

Sibling to gesture_replay.py rather than a mode of it: unlike Shark2, a TCN decode needs a
*weights* file as well as a language pack (see native-tests/tcn_replay.cpp's own note on why),
so the CLI contract genuinely differs, and the two decoders are compared against separate
baselines (docs/gesture-accuracy.json vs. docs/gesture-accuracy-tcn.json) -- folding them into one
script's argparse would hide that difference rather than express it. Layout/corpus parsing is
imported from gesture_replay.py rather than copied, so the two never drift apart on what a
recording looks like.

Usage
-----
    ./tcn_replay.py --binary native-tests/build/tcn_replay \\
        --pack native-tests/build/test_pack.bkd --weights tools/swipe_model/model.bkw
    ./tcn_replay.py --check-regression --weights tools/swipe_model/model.bkw \\
        --pack native-tests/build/test_pack.bkd
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

from gesture_replay import DEFAULT_CORPUS, DEFAULT_LAYOUT, REPOSITORY_ROOT, load_corpus

DEFAULT_BINARY = REPOSITORY_ROOT / "native-tests" / "build" / "tcn_replay"
BASELINE = REPOSITORY_ROOT / "docs" / "gesture-accuracy-tcn.json"


def replay(binary: Path, pack: Path, weights: Path, layout: Path, corpus: Path) -> dict:
    """Same `word<TAB>rank` contract gesture_replay.replay() parses, from a binary that also
    takes a weights file -- see the module doc for why that is one more required argument here
    than gesture_replay.py's own `replay()` takes."""
    result = subprocess.run(
        [str(binary), str(pack), str(weights), str(layout), str(corpus)],
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
    parser.add_argument("--pack", type=Path, help="a .bkd language pack, for the lexicon")
    parser.add_argument("--weights", type=Path, help="a trained .bkw file (export_weights.py)")
    parser.add_argument("--record-baseline", action="store_true",
                        help="write the measured accuracy to docs/gesture-accuracy-tcn.json")
    parser.add_argument("--check-regression", action="store_true",
                        help="fail if accuracy fell below the recorded baseline")
    arguments = parser.parse_args(argv)

    if not arguments.corpus.is_file():
        print(f"::notice::{arguments.corpus} does not exist yet — nothing to replay")
        return 0
    gestures = load_corpus(arguments.corpus)
    print(f"corpus: {len(gestures)} gestures, "
          f"{sum(len(g.samples) for g in gestures)} samples, from {arguments.corpus}")

    if not arguments.binary.is_file():
        # Mirrors gesture_replay.py's own notice: a check that silently does nothing reads
        # exactly like a check that passed, so it says why instead of staying quiet.
        print(f"::notice::{arguments.binary} not built — accuracy not measured, nothing compared")
        return 0
    if arguments.weights is None or not arguments.weights.is_file():
        # Training runs for hours and this script must not fail a build in the meantime -- the
        # weights file existing at all is the signal that a real evaluation is possible yet.
        print(f"::notice::no trained weights at {arguments.weights} — accuracy not measured, "
              f"nothing compared")
        return 0
    if arguments.pack is None:
        raise SystemExit("--pack is required to replay")

    measured = replay(arguments.binary, arguments.pack, arguments.weights, arguments.layout,
                      arguments.corpus)
    print(f"top-1 {measured['top1']}%  top-3 {measured['top3']}%  "
          f"over {measured['gestures']} gestures")
    if measured["misses"]:
        print("misses:", ", ".join(measured["misses"]))

    if arguments.record_baseline:
        BASELINE.parent.mkdir(parents=True, exist_ok=True)
        BASELINE.write_text(json.dumps({
            "corpus": str(arguments.corpus.relative_to(REPOSITORY_ROOT)),
            "weights": str(arguments.weights.relative_to(REPOSITORY_ROOT))
                      if arguments.weights.is_relative_to(REPOSITORY_ROOT) else str(arguments.weights),
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
        # Same tolerance as gesture_replay.py's own regression gate, for the same reason: the
        # corpus is fixed and decoding is deterministic, so any real drop should fail the build,
        # but a cross-platform rounding difference should not.
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
