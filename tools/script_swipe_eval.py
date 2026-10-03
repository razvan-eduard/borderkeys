#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Synthesises swipes on an own-script layout and measures both decoders on them.

The words are a deterministic sample of a language's frequent words, spelled as the layout can
type them: a letter the layout lacks is replaced by the one it is projected to, or the word is
left out. The traces are gesture_replay.py's polylines through the key centres, with its jitter
and timing; every figure is relative to that generator, as no real swipes exist for these
scripts.

A script's model is the shipped keyboard/src/plus/assets/swipe/<layout>.bkw when there is one;
a layout without one decodes geometrically. --record-baseline writes
docs/gesture-accuracy-tcn-<layout>.json; --check-regression is the CI gate, which replays the
polyline hold-out and needs no training data.
"""

import argparse
import json
import pathlib
import subprocess
import sys
import unicodedata

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import gesture_replay  # noqa: E402

DATA = ROOT / "native-tests/data"
OUT = ROOT / "build/script-swipe"
PACKS = ROOT / "build/packs"
LISTS = ROOT / "dictionaries/extra"
GEOMETRIC = ROOT / "native-tests/build/gesture_replay"
NEURAL = ROOT / "native-tests/build/tcn_replay"
LATIN_MODEL = ROOT / "keyboard/src/plus/assets/model.bkw"
SHIPPED = ROOT / "keyboard/src/plus/assets/swipe"
DOCS = ROOT / "docs"
BUILD_DICT = ROOT / "tools/build_dict.py"
TOLERANCE = 0.5

# layout -> word list
SCRIPTS = {
    "russian": "ru_RU", "ukrainian": "uk_UA", "bulgarian": "bg_BG", "serbian": "sr_RS",
    "macedonian": "mk_MK", "greek": "el_GR", "armenian": "hy_AM", "georgian": "ka_GE",
    "hebrew": "he_IL", "arabic": "ar",
}

# Letters a layout has no key for, written as the letter it does have.
PROJECTIONS = {"ё": "е", "Ё": "Е", "ѝ": "и"}

# The words drawn from, by frequency rank, and how many are kept.
POOL = 20_000
HOLD_OUT_EVERY = 7


def project(word: str, keys: set[str]) -> str | None:
    out = []
    for character in word.lower():
        if character in keys:
            out.append(character)
            continue
        if character in PROJECTIONS and PROJECTIONS[character] in keys:
            out.append(PROJECTIONS[character])
            continue
        bare = "".join(c for c in unicodedata.normalize("NFD", character) if not unicodedata.combining(c))
        if bare in keys:
            out.append(bare)
            continue
        # Hebrew final forms and Greek final sigma, when the layout has only the medial one.
        final = {"ך": "כ", "ם": "מ", "ן": "נ", "ף": "פ", "ץ": "צ", "ς": "σ"}.get(character)
        if final in keys:
            out.append(final)
            continue
        return None
    return "".join(out)


def words_for(name: str, keys: set[str], count: int, hold_out: bool) -> list[tuple[str, str]]:
    """(the word as the dictionary spells it, the letters a swipe passes through), deterministic."""
    seen: set[str] = set()
    chosen: list[tuple[str, str]] = []
    rows = (LISTS / f"{SCRIPTS[name]}.tsv").read_text(encoding="utf-8").splitlines()
    for rank, row in enumerate(rows[:POOL]):
        word = row.split("\t", 1)[0]
        if (rank % HOLD_OUT_EVERY == 0) != hold_out:
            continue
        spelled = project(word, keys)
        if spelled is None or len(spelled) < 2 or spelled in seen:
            continue
        seen.add(spelled)
        chosen.append((word.lower(), spelled))
        if len(chosen) >= count:
            break
    return chosen


def corpus(name: str, count: int, seed: int) -> pathlib.Path:
    layout = gesture_replay.Layout.load(DATA / f"{name}_1080.layout")
    words = words_for(name, set(layout.keys), count, hold_out=True)
    gestures = []
    for index, (word, spelled) in enumerate(words):
        samples = gesture_replay.synthesise(layout, spelled, 14.0, seed * 1_000_003 + index)
        if samples:
            gesture = gesture_replay.Gesture(f"{name}-{index}", word)
            gesture.samples = samples
            gestures.append(gesture)
    out = OUT / f"{name}_synthetic.csv"
    gesture_replay.write_corpus(out, gestures)
    return out


SWIPE_MODEL = ROOT / "tools/swipe_model"


def realistic_corpus(name: str, count: int, seed: int) -> pathlib.Path:
    """Held-out words swiped as real QWERTY swipes from the validation split are laid onto them
    (tools/swipe_model/synthesise.py), in the layout's pixels."""
    layout_path = DATA / f"{name}_1080.layout"
    records = SWIPE_MODEL / "data" / "eval" / f"{name}.jsonl"
    subprocess.run(
        [str(SWIPE_MODEL / ".venv/bin/python"), str(SWIPE_MODEL / "synthesise.py"),
         "--layout", str(layout_path), "--words", str(LISTS / f"{SCRIPTS[name]}.tsv"),
         "--source", str(SWIPE_MODEL / "data/validation.jsonl"), "--count", str(count),
         "--hold-out", "--bank", "60000", "--seed", str(seed), "--out", str(records)],
        check=True, cwd=SWIPE_MODEL, env={"HF_HUB_OFFLINE": "1", "PATH": "/usr/bin:/bin"},
        capture_output=True,
    )
    layout = gesture_replay.Layout.load(layout_path)
    area_w = max(x for x, _ in layout.keys.values()) + layout.key_width / 2
    area_h = max(y for _, y in layout.keys.values()) + layout.key_height / 2
    gestures = []
    for index, line in enumerate(records.read_text(encoding="utf-8").splitlines()):
        record = json.loads(line)
        gesture = gesture_replay.Gesture(f"{name}-r{index}", record["word"])
        gesture.samples = [(u * area_w, v * area_h, int(ts)) for u, v, ts in zip(record["xs"], record["ys"], record["ts"])]
        gestures.append(gesture)
    out = OUT / f"{name}_realistic.csv"
    gesture_replay.write_corpus(out, gestures)
    return out


def run(binary: pathlib.Path, arguments: list[str]) -> str:
    result = subprocess.run([str(binary), *arguments], capture_output=True, text=True, check=False)
    return (result.stdout + result.stderr).strip()


def summary(output: str) -> dict:
    """Top-1 and top-3 from the binary's `word<TAB>rank` lines, rank -1 for a miss."""
    ranks = []
    for line in output.splitlines():
        parts = line.split("\t")
        if len(parts) >= 2 and parts[1].lstrip("-").isdigit():
            ranks.append(int(parts[1]))
    if not ranks:
        return {"gestures": 0, "top1": 0.0, "top3": 0.0, "error": output[-300:]}
    return {
        "gestures": len(ranks),
        "top1": round(100.0 * sum(1 for r in ranks if r == 0) / len(ranks), 1),
        "top3": round(100.0 * sum(1 for r in ranks if 0 <= r < 3) / len(ranks), 1),
    }


def model_for(name: str) -> pathlib.Path:
    shipped = SHIPPED / f"{name}.bkw"
    return shipped if shipped.exists() else LATIN_MODEL


def build_pack(name: str) -> pathlib.Path:
    """The script's pack, compiled from its word list as the packs workflow does, when missing."""
    pack = PACKS / f"{SCRIPTS[name]}.bkd"
    if pack.exists():
        return pack
    PACKS.mkdir(parents=True, exist_ok=True)
    stem = LISTS / SCRIPTS[name]
    arguments = [sys.executable, str(BUILD_DICT), "--words", f"{stem}.tsv",
                 "--tag", SCRIPTS[name].replace("_", "-"), "--out", str(pack)]
    if pathlib.Path(f"{stem}.ngrams").exists():
        arguments += ["--ngrams", f"{stem}.ngrams"]
    if pathlib.Path(f"{stem}.pos").exists():
        arguments += ["--grammar", f"{stem}.pos"]
    subprocess.run(arguments, check=True, cwd=ROOT)
    return pack


def measure(name: str, csv: pathlib.Path, weights: pathlib.Path) -> dict:
    pack = PACKS / f"{SCRIPTS[name]}.bkd"
    layout = DATA / f"{name}_1080.layout"
    return {
        "geometric": summary(run(GEOMETRIC, [str(pack), str(layout), str(csv)])),
        "neural": summary(run(NEURAL, [str(pack), str(weights), str(layout), str(csv)])),
    }


def figures(result: dict) -> dict:
    return {decoder: {"top1": result[decoder]["top1"], "top3": result[decoder]["top3"]} for decoder in result}


def baseline_path(name: str) -> pathlib.Path:
    return DOCS / f"gesture-accuracy-tcn-{name}.json"


def record_baseline(name: str, count: int, seed: int, weights: pathlib.Path) -> None:
    polyline = measure(name, corpus(name, count, seed), weights)
    realistic = measure(name, realistic_corpus(name, count, seed), weights)
    zero_shot = measure(name, OUT / f"{name}_realistic.csv", LATIN_MODEL)["neural"]
    record = {
        "layout": str((DATA / f"{name}_1080.layout").relative_to(ROOT)),
        "words": str((LISTS / f"{SCRIPTS[name]}.tsv").relative_to(ROOT)),
        "weights": str(weights.relative_to(ROOT)),
        "seed": seed,
        "polyline": {"gestures": polyline["neural"]["gestures"], **figures(polyline)},
        "realistic": {
            "gestures": realistic["neural"]["gestures"],
            **figures(realistic),
            "latin model": {"top1": zero_shot["top1"], "top3": zero_shot["top3"]},
        },
    }
    baseline_path(name).write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"baseline written to {baseline_path(name).relative_to(ROOT)}")


def check_regression(name: str, count: int) -> bool:
    path = baseline_path(name)
    if not path.exists():
        print(f"::error::{name} has a shipped model and no baseline at {path.relative_to(ROOT)}")
        return False
    baseline = json.loads(path.read_text(encoding="utf-8"))
    weights = ROOT / baseline["weights"]
    measured = measure(name, corpus(name, count, baseline["seed"]), weights)
    passed = True
    for decoder in ("neural", "geometric"):
        for rank in ("top1", "top3"):
            was = baseline["polyline"][decoder][rank]
            now = measured[decoder][rank]
            if now + TOLERANCE < was:
                print(f"::error::{name} {decoder} {rank} fell from {was}% to {now}%")
                passed = False
    if passed:
        print(f"{name}: no regression against neural {baseline['polyline']['neural']['top1']}% / "
              f"geometric {baseline['polyline']['geometric']['top1']}%")
    return passed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("names", nargs="*")
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--shipped", action="store_true", help="every layout with a shipped model")
    parser.add_argument("--count", type=int, default=500)
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--weights", type=pathlib.Path, help="a model for the script; its shipped or the Latin model otherwise")
    parser.add_argument("--json", type=pathlib.Path, help="write the figures here")
    parser.add_argument("--realistic", action="store_true",
                        help="real QWERTY swipes laid onto the words, not polylines through the keys")
    parser.add_argument("--build-packs", action="store_true", help="compile a missing pack from its word list")
    parser.add_argument("--record-baseline", action="store_true", help="write docs/gesture-accuracy-tcn-<layout>.json")
    parser.add_argument("--check-regression", action="store_true", help="fail if the polyline figures fell")
    args = parser.parse_args()
    if args.shipped:
        names = sorted(path.stem for path in SHIPPED.glob("*.bkw") if path.stem in SCRIPTS)
    else:
        names = list(SCRIPTS) if args.all else args.names
    if not names and not args.shipped:
        parser.error("name a layout, --all or --shipped")
    if args.build_packs:
        for name in names:
            build_pack(name)
    if args.check_regression:
        return 0 if all([check_regression(name, args.count) for name in names]) else 1
    if args.record_baseline:
        for name in names:
            record_baseline(name, args.count, args.seed, args.weights or model_for(name))
        return 0
    results = {}
    for name in names:
        csv = realistic_corpus(name, args.count, args.seed) if args.realistic else corpus(name, args.count, args.seed)
        weights = args.weights or model_for(name)
        result = measure(name, csv, weights)
        geometric, neural = result["geometric"], result["neural"]
        results[name] = {"geometric": geometric, "neural": neural, "weights": weights.name}
        print(f"{name:11} geometric {geometric['top1']:5.1f} / {geometric['top3']:5.1f}   "
              f"neural ({weights.name}) {neural['top1']:5.1f} / {neural['top3']:5.1f}   "
              f"over {geometric['gestures']} gestures")
    if args.json:
        args.json.write_text(json.dumps(results, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
