#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Measures the trained TCN swipe decoder's accuracy over the recorded corpus gesture_replay.py
measures Shark2 against. A TCN decode needs a weights file as well as a language pack, and is
compared against its own baseline, docs/gesture-accuracy-tcn.json. The corpus, the binary's
lines, the figures and the rows file are gesture_replay.py's.

Usage
-----
    ./tcn_replay.py --binary native-tests/build/tcn_replay \\
        --pack native-tests/build/test_pack.bkd --weights keyboard/src/plus/assets/model.bkw \\
        [--rows rows.tsv]
    ./tcn_replay.py --check-regression --weights keyboard/src/plus/assets/model.bkw \\
        --pack native-tests/build/test_pack.bkd
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from gesture_replay import (DEFAULT_CORPUS, DEFAULT_LAYOUT, REPOSITORY_ROOT, Replayed, figures,
                            load_corpus, nothing_measured, run_gate_selftest, run_replay, write_rows)

DEFAULT_BINARY = REPOSITORY_ROOT / "native-tests" / "build" / "tcn_replay"
BASELINE = REPOSITORY_ROOT / "docs" / "gesture-accuracy-tcn.json"


def replay(binary: Path, pack: Path, weights: Path, layout: Path, corpus: Path) -> list[Replayed]:
    """gesture_replay.replay()'s line per gesture, from a binary that also takes a weights file."""
    return run_replay([str(binary), str(pack), str(weights), str(layout), str(corpus)])


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
    parser.add_argument("--rows", type=Path, help="also write each gesture's verdicts as a rows file here")
    parser.add_argument("--selftest", action="store_true",
                        help="prove the regression check fails whenever it has nothing to compare")
    arguments = parser.parse_args(argv)

    if arguments.selftest:
        selftest()
        return 0
    if not arguments.corpus.is_file():
        return nothing_measured(f"{arguments.corpus} does not exist yet — nothing to replay",
                                arguments.check_regression)
    gestures = load_corpus(arguments.corpus)
    print(f"corpus: {len(gestures)} gestures, "
          f"{sum(len(g.samples) for g in gestures)} samples, from {arguments.corpus}")

    if not arguments.binary.is_file():
        return nothing_measured(f"{arguments.binary} not built — accuracy not measured, nothing compared",
                                arguments.check_regression)
    if arguments.weights is None or not arguments.weights.is_file():
        return nothing_measured(f"no trained weights at {arguments.weights} — accuracy not measured, "
                                f"nothing compared", arguments.check_regression)
    if arguments.pack is None:
        raise SystemExit("--pack is required to replay")

    replayed = replay(arguments.binary, arguments.pack, arguments.weights, arguments.layout,
                      arguments.corpus)
    if arguments.rows is not None:
        write_rows(arguments.rows, gestures, replayed)
    measured = figures(replayed)
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
            return nothing_measured(f"{BASELINE} does not exist — nothing to compare against", True)
        baseline = json.loads(BASELINE.read_text(encoding="utf-8"))
        # The same tolerance as gesture_replay.py's regression gate.
        tolerance = 0.5
        if measured["top1"] + tolerance < baseline["top1"]:
            print(f"::error::top-1 fell from {baseline['top1']}% to {measured['top1']}%")
            return 1
        if measured["top3"] + tolerance < baseline["top3"]:
            print(f"::error::top-3 fell from {baseline['top3']}% to {measured['top3']}%")
            return 1
        print(f"no regression against {baseline['top1']}% / {baseline['top3']}%")
    return 0


def selftest() -> None:
    replayable = ["--check-regression", "--corpus", "{corpus}", "--binary", "{present}", "--pack", "{present}",
                  "--weights", "{present}"]
    run_gate_selftest(sys.modules[__name__], [
        ("a missing corpus fails the check", ["--check-regression", "--corpus", "{absent}"], None, 1),
        ("a missing corpus is a notice without the check", ["--corpus", "{absent}"], None, 0),
        ("a missing binary fails the check",
         ["--check-regression", "--corpus", "{corpus}", "--binary", "{absent}"], None, 1),
        ("missing weights fail the check",
         ["--check-regression", "--corpus", "{corpus}", "--binary", "{present}", "--weights", "{absent}"], None, 1),
        ("missing weights are a notice without the check",
         ["--corpus", "{corpus}", "--binary", "{present}", "--weights", "{absent}"], None, 0),
        ("a missing baseline fails the check", replayable, None, 1),
        ("the baseline's own figures pass", replayable, {"top1": 40.0, "top3": 60.0}, 0),
        ("a top-3 below the baseline fails", replayable, {"top1": 40.0, "top3": 61.0}, 1),
        ("each gesture is a row, its verdicts from its rank",
         ["--corpus", "{corpus}", "--binary", "{present}", "--pack", "{present}", "--weights", "{present}",
          "--rows", "{rows}"], None, 0),
    ])
    print("selftest ok: the regression check fails on a fall and whenever it has nothing to compare, "
          "and each gesture is a row")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
