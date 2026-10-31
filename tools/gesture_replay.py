#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Measures swipe decoding accuracy over a recorded corpus. Standard library only.

Formats
-------
Layout (`native-tests/data/*.layout`), one key per line after a header:

    # keyWidth keyHeight
    108 160
    q 54 80
    ...

Corpus (`native-tests/data/*.csv`), one touch sample per line:

    id,word,x,y,t

Gestures are grouped by `id` and must appear contiguously.

A replay binary prints one `word<TAB>rank<TAB>top` line per gesture, in corpus order: the rank of
the word meant among the candidates, -1 for a miss, and the candidate ranked first. `--rows`
writes them as a rows file for scripts/compare_readings.py.

Usage
-----
    ./gesture_replay.py --synthesise --layout native-tests/data/qwerty_1080.layout \\
        --words the,there,keyboard --out native-tests/data/gestures.csv [--fold]
    ./gesture_replay.py --binary native-tests/build/gesture_replay \\
        --pack keyboard/src/main/assets/dict/en_US.bkd [--rows rows.tsv]
    ./gesture_replay.py --check-regression
    ./gesture_replay.py --selftest
"""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import subprocess
import sys
import tempfile
import unicodedata
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_LAYOUT = REPOSITORY_ROOT / "native-tests" / "data" / "qwerty_1080.layout"
DEFAULT_CORPUS = REPOSITORY_ROOT / "native-tests" / "data" / "gestures.csv"
DEFAULT_BINARY = REPOSITORY_ROOT / "native-tests" / "build" / "gesture_replay"
BASELINE = REPOSITORY_ROOT / "docs" / "gesture-accuracy.json"

# A layout file's rows: the header, the key width and height; then each key's code and centre.
LAYOUT_HEADER_COLUMNS = 2
LAYOUT_KEY_COLUMNS = 3
# A gesture corpus row: 'id,word,x,y,t'.
GESTURE_COLUMNS = 5
# A replay binary's line up to the rank, 'word<TAB>rank', before the top word.
REPLAY_RANK_COLUMNS = 2


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
            if key_width == 0.0 and len(parts) == LAYOUT_HEADER_COLUMNS:
                key_width, key_height = float(parts[0]), float(parts[1])
                continue
            if len(parts) != LAYOUT_KEY_COLUMNS:
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
        if len(parts) != GESTURE_COLUMNS:
            raise SystemExit(f"{path}:{number}: expected 'id,word,x,y,t'")
        identifier, word = parts[0], parts[1]
        if current is None or current.identifier != identifier:
            current = Gesture(identifier, word)
            gestures.append(current)
        current.samples.append((float(parts[2]), float(parts[3]), int(parts[4])))
    return gestures


# --------------------------------------------------------------------------------------
# Synthesis, in the same corpus format as a recording.
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


# The marks a swipe passes over without a key of its own.
UNKEYED_MARKS = "'-"


def swiped_letters(word: str, layout: Layout) -> str:
    """The letters a swipe of [word] passes over on [layout]: each as written when the layout has
    its key, else in lower case without its accents; apostrophes and hyphens left out."""
    letters = []
    for character in word:
        if character in layout.keys:
            letters.append(character)
        elif character not in UNKEYED_MARKS:
            letters.append(unicodedata.normalize("NFD", character.lower())[0])
    return "".join(letters)


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


class Replayed:
    """One gesture's line from a replay binary: the word meant, its zero-based rank among the
    candidates or -1 for a miss, and the candidate ranked first, `-` for none."""

    def __init__(self, word: str, rank: int, top: str):
        self.word = word
        self.rank = rank
        self.top = top


# The measures of a swipe rows file, and how many candidates the second one reads.
ROWS_MEASURES = ("first", "top-three")
TOP_THREE = 3
# How many misses a replay's figures list.
MISSES_LISTED = 20
# A rows file's place for a word the candidates do not hold.
ABSENT = "absent"


def verdicts(rank: int) -> tuple[bool, bool]:
    """Whether the word at [rank] (-1 for a miss) came first, and whether among the first
    TOP_THREE."""
    return rank == 0, 0 <= rank < TOP_THREE


def run_replay(command: list[str]) -> list[Replayed]:
    """Runs a replay binary and reads its `word<TAB>rank[<TAB>top]` line per gesture, in corpus
    order."""
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        sys.stderr.write(result.stdout)
        sys.stderr.write(result.stderr)
        raise SystemExit(f"{command[0]} exited {result.returncode}")
    sys.stderr.write(result.stderr)
    replayed: list[Replayed] = []
    for line in result.stdout.splitlines():
        fields = line.split("\t")
        if len(fields) < REPLAY_RANK_COLUMNS:
            continue
        try:
            rank = int(fields[1].strip())
        except ValueError:
            continue
        replayed.append(Replayed(fields[0], rank, fields[2] if len(fields) > REPLAY_RANK_COLUMNS else "-"))
    if not replayed:
        raise SystemExit(f"{command[0]} reported no gestures")
    return replayed


def replay(binary: Path, pack: Path, layout: Path, corpus: Path) -> list[Replayed]:
    """The native harness's line per gesture."""
    return run_replay([str(binary), str(pack), str(layout), str(corpus)])


def figures(replayed: list[Replayed]) -> dict:
    """Top-1 and top-3 in percent over [replayed], and the first misses as `word->top`."""
    firsts = [verdicts(line.rank) for line in replayed]
    return {
        "gestures": len(replayed),
        "top1": round(100.0 * sum(first for first, _ in firsts) / len(replayed), 2),
        "top3": round(100.0 * sum(three for _, three in firsts) / len(replayed), 2),
        "misses": [f"{line.word}->{line.top}" for line in replayed if not verdicts(line.rank)[1]][:MISSES_LISTED],
    }


def write_rows(path: Path, gestures: list[Gesture], replayed: list[Replayed]) -> None:
    """[replayed] as a rows file, in the format native-tests/rows_file.hpp describes: per gesture
    its verdicts, its id and word as the key, then the word meant's place among the candidates,
    from 1 (ABSENT for a miss), and the top candidate. The lines are the corpus's gestures in
    order, so their count and words must agree."""
    if [gesture.word for gesture in gestures] != [line.word for line in replayed]:
        raise SystemExit(f"the replay answered {len(replayed)} gestures, not the corpus's {len(gestures)} in order")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        handle.write(f"# measures: {' '.join(ROWS_MEASURES)}\n")
        for gesture, line in zip(gestures, replayed):
            cells = ["1" if verdict else "0" for verdict in verdicts(line.rank)]
            place = str(line.rank + 1) if line.rank >= 0 else ABSENT
            handle.write("\t".join(cells + [f"{gesture.identifier} {gesture.word}", place, line.top]) + "\n")


def nothing_measured(message: str, check_regression: bool) -> int:
    """Reports [message]: under --check-regression an error and a failure, since nothing was
    compared; otherwise a notice."""
    if check_regression:
        print(f"::error::{message}")
        return 1
    print(f"::notice::{message}")
    return 0


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
    parser.add_argument("--fold", action="store_true",
                        help="for --synthesise, swipe a letter the layout lacks over the key of its lower-case, "
                             "unaccented form, and pass over apostrophes and hyphens")
    parser.add_argument("--out", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--record-baseline", action="store_true",
                        help="write the measured accuracy to docs/gesture-accuracy.json")
    parser.add_argument("--check-regression", action="store_true",
                        help="fail if accuracy fell below the recorded baseline")
    parser.add_argument("--rows", type=Path, help="also write each gesture's verdicts as a rows file here")
    parser.add_argument("--selftest", action="store_true",
                        help="prove the regression check fails whenever it has nothing to compare")
    arguments = parser.parse_args(argv)

    if arguments.selftest:
        selftest()
        return 0
    if arguments.synthesise:
        layout = Layout.load(arguments.layout)
        words = [w.strip() for w in (arguments.words or "").split(",") if w.strip()]
        if not words:
            raise SystemExit("--synthesise needs --words")
        gestures: list[Gesture] = []
        for index, word in enumerate(words):
            gesture = Gesture(f"g{index:04d}", word)
            letters = swiped_letters(word, layout) if arguments.fold else word
            gesture.samples = synthesise(layout, letters, arguments.jitter, 0x5EED + index * 7919)
            if not gesture.samples:
                sys.stderr.write(f"skipped {word!r}: a letter is not on this layout\n")
                continue
            gestures.append(gesture)
        write_corpus(arguments.out, gestures)
        total = sum(len(g.samples) for g in gestures)
        print(f"wrote {arguments.out}: {len(gestures)} gestures, {total} samples")
        return 0

    if not arguments.corpus.is_file():
        return nothing_measured(f"{arguments.corpus} does not exist yet — nothing to replay",
                                arguments.check_regression)
    gestures = load_corpus(arguments.corpus)
    print(f"corpus: {len(gestures)} gestures, "
          f"{sum(len(g.samples) for g in gestures)} samples, from {arguments.corpus}")

    if not arguments.binary.is_file():
        # The native harness is built by native-tests/.
        return nothing_measured(f"{arguments.binary} not built — accuracy not measured, nothing compared",
                                arguments.check_regression)
    if arguments.pack is None:
        raise SystemExit("--pack is required to replay")

    replayed = replay(arguments.binary, arguments.pack, arguments.layout, arguments.corpus)
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
        # A tolerance, not equality, for rounding differences between platforms.
        tolerance = 0.5
        if measured["top1"] + tolerance < baseline["top1"]:
            print(f"::error::top-1 fell from {baseline['top1']}% to {measured['top1']}%")
            return 1
        if measured["top3"] + tolerance < baseline["top3"]:
            print(f"::error::top-3 fell from {baseline['top3']}% to {measured['top3']}%")
            return 1
        print(f"no regression against {baseline['top1']}% / {baseline['top3']}%")
    return 0


# --------------------------------------------------------------------------------------
# Selftest
# --------------------------------------------------------------------------------------

# The selftest's corpus, one sample per gesture, and the lines its replay answers for it: ranks
# 0, 0, 2, 3 and a miss, so top-1 40% and top-3 60%.
SELFTEST_CORPUS = "id,word,x,y,t\ng0,a,0,0,0\ng1,b,0,0,0\ng2,c,0,0,0\ng3,d,0,0,0\ng4,e,0,0,0\n"
SELFTEST_REPLAYED = [Replayed("a", 0, "a"), Replayed("b", 0, "b"), Replayed("c", 2, "x"),
                     Replayed("d", 3, "w"), Replayed("e", -1, "y")]

# The rows file a replay of the selftest's corpus writes.
SELFTEST_ROWS = ("# measures: first top-three\n"
                 "1\t1\tg0 a\t1\ta\n"
                 "1\t1\tg1 b\t1\tb\n"
                 "0\t1\tg2 c\t3\tx\n"
                 "0\t0\tg3 d\t4\tw\n"
                 "0\t0\tg4 e\tabsent\ty\n")


def run_gate_selftest(module, cases: list[tuple[str, list[str], dict | None, int]]) -> None:
    """Runs [module]'s main on each of [cases], `(name, arguments, baseline, status)`, with its
    replay answering SELFTEST_REPLAYED. `{corpus}`, `{absent}`, `{present}` and `{rows}` in the
    arguments are SELFTEST_CORPUS, a missing file, an existing one and a file not yet written;
    [baseline], when given, is written as the module's BASELINE, which is otherwise missing. A
    case that names `{rows}` must write SELFTEST_ROWS there."""
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        paths = {"corpus": root / "corpus.csv", "absent": root / "absent", "present": Path(sys.executable),
                 "rows": root / "rows.tsv"}
        paths["corpus"].write_text(SELFTEST_CORPUS, encoding="utf-8")
        module.replay = lambda *_: list(SELFTEST_REPLAYED)
        module.BASELINE = root / "baseline.json"
        for name, arguments, baseline, status in cases:
            module.BASELINE.unlink(missing_ok=True)
            paths["rows"].unlink(missing_ok=True)
            if baseline is not None:
                module.BASELINE.write_text(json.dumps(baseline), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()):
                got = module.main([argument.format(**paths) for argument in arguments])
            assert got == status, f"{name}: exit {got}, expected {status}"
            if "{rows}" in arguments:
                written = paths["rows"].read_text(encoding="utf-8") if paths["rows"].is_file() else None
                assert written == SELFTEST_ROWS, f"{name}: wrote {written!r}, expected {SELFTEST_ROWS!r}"


def selftest() -> None:
    replayable = ["--check-regression", "--corpus", "{corpus}", "--binary", "{present}", "--pack", "{present}"]
    run_gate_selftest(sys.modules[__name__], [
        ("a missing corpus fails the check", ["--check-regression", "--corpus", "{absent}"], None, 1),
        ("a missing corpus is a notice without the check", ["--corpus", "{absent}"], None, 0),
        ("a missing binary fails the check",
         ["--check-regression", "--corpus", "{corpus}", "--binary", "{absent}"], None, 1),
        ("a missing baseline fails the check", replayable, None, 1),
        ("the baseline's own figures pass", replayable, {"top1": 40.0, "top3": 60.0}, 0),
        ("a top-1 below the baseline fails", replayable, {"top1": 41.0, "top3": 60.0}, 1),
        ("each gesture is a row, its verdicts from its rank",
         ["--corpus", "{corpus}", "--binary", "{present}", "--pack", "{present}", "--rows", "{rows}"], None, 0),
    ])
    with tempfile.TemporaryDirectory() as directory:
        written = {}
        for name, options in (("folded", ["--fold"]), ("plain", [])):
            out = Path(directory) / f"{name}.csv"
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                main(["--synthesise", "--words", "Și-a,sia", "--out", str(out), *options])
            written[name] = load_corpus(out)
        folded, plain = written["folded"], written["plain"]
        assert [gesture.word for gesture in folded] == ["Și-a", "sia"], "--fold keeps each word as written"
        through_sia = [(float(f"{x:.1f}"), float(f"{y:.1f}"), t)
                       for x, y, t in synthesise(Layout.load(DEFAULT_LAYOUT), "sia", 14.0, 0x5EED)]
        assert folded[0].samples == through_sia, \
            "--fold swipes a capital, an accented letter and a hyphen over the keys of s, i and a"
        assert [gesture.word for gesture in plain] == ["sia"], "without --fold a letter off the layout is skipped"
    print("selftest ok: the regression check fails on a fall and whenever it has nothing to compare, "
          "each gesture is a row, and --fold swipes a word over its letters' plain keys")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
