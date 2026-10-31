#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Judges a design sheet's arms by scripts/compare_readings.py's verdict, keeps the ledger of
TEST reads, and sets DEV's genres beside TEST's.

`judge` reads a sheet file: its name, its arm budget (`arms`), its churn budget (`budget`, as
compare_readings.py names them) and margin, the readings every run is judged against (`base`),
each arm's declared grid (`grid`: arm to constant to its values), and its runs (`runs`: the arm,
the point on its grid, the readings directory), paths relative to the sheet. Each run is judged
against the base; a run is eligible when nothing falls and its pooled net is better beyond
chance. Each arm's best is its eligible run with the highest pooled net, and the best arm the
arm whose best is highest. It prints:

- EDGE when an arm's best sits on a bound of its grid;
- WITHIN NOISE when an arm's best is not better beyond chance than its next run, or the best arm
  than the next arm;
- BUDGET SPENT when the sheet has run as many arms as its budget, or more;
- the best arm's genres.

`freeze` records in the sheet, once, before its first run, the SHA-256 of the tools its runs are
read and judged with: compare_readings.py, this script, suggest_eval.cpp and the native tests'
headers it includes, context_runs.hpp, rows_file.hpp and test_support.hpp (`tools`); the engine's
own headers are what the arms change, and are not frozen. `judge` and `compose` print TOOLS
CHANGED and judge nothing when the sheet records none, or when one differs.

`ledger` records a TEST read of a sheet: the time, the tree (HEAD, and a digest of every change
and untracked file when there are any) and the sheet. When the ledger already holds a read of
that sheet it prints SECOND TEST READ and exits 1, unless --second-test-read gives the words that
allow it, which are recorded beside the read.

`compose` writes the composite of the runs at one point of a grid (`--where NAME=VALUE`): each
language's files from the run that gives its top three the highest net with no fall in that
language's files, the choices recorded in COMPOSITE_RECORD.

`genres` counts each language's sentences by genre in the DEV corpora (`dev_<language>.txt`) and
the TEST corpora (`context_<language>.txt`), and prints TEST GENRE NOT IN DEV for a TEST genre
with no DEV sentence.

    python3 scripts/judge_arms.py freeze <sheet.json>
    python3 scripts/judge_arms.py judge <sheet.json>
    python3 scripts/judge_arms.py compose <sheet.json> <out dir> [--where NAME=VALUE ...]
    python3 scripts/judge_arms.py ledger <ledger.tsv> <sheet> [--second-test-read WORDS]
    python3 scripts/judge_arms.py genres <dev corpora dir> <test corpora dir>
    python3 scripts/judge_arms.py --selftest
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import shutil
import subprocess
import sys
import tempfile
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compare_readings  # noqa: E402

EDGE = "EDGE"
WITHIN_NOISE = "WITHIN NOISE"
BUDGET_SPENT = "BUDGET SPENT"
SECOND_TEST_READ = "SECOND TEST READ"
TEST_GENRE_MISSING = "TEST GENRE NOT IN DEV"
TOOLS_CHANGED = "TOOLS CHANGED"

ROOT = Path(__file__).resolve().parent.parent
# The tools a sheet's runs are read and judged with, by path under the repository root: a sheet
# records their SHA-256 when it is frozen and is judged only while each still matches. They are
# the judges; the evaluators that write the rows, with the native tests' headers they include; the
# replays' wrappers; and the Kotlin reading of the correction DEV rows, with the pipeline and the
# corpus tests it types them through. The engine's own sources are what the arms change.
FROZEN_TOOLS = ("scripts/compare_readings.py", "scripts/judge_arms.py",
                "native-tests/suggest_eval.cpp", "native-tests/gesture_replay.cpp",
                "native-tests/tcn_replay.cpp", "native-tests/touch_eval.cpp",
                "native-tests/rows_file.hpp", "native-tests/context_runs.hpp",
                "native-tests/replay_corpora.hpp", "native-tests/test_support.hpp",
                "tools/gesture_replay.py", "tools/tcn_replay.py",
                "keyboard/src/test/java/com/borderkeys/predict/CorrectionDevReadingsTest.kt",
                "keyboard/src/test/java/com/borderkeys/predict/Pipeline.kt",
                "keyboard/src/test/java/com/borderkeys/predict/PersonalCorpusTest.kt",
                "keyboard/src/test/java/com/borderkeys/predict/TwoLanguageCorpusTest.kt")
# The file a composite records its choices in.
COMPOSITE_RECORD = "composite.json"
SENTENCE_LINE = "# sentence "
NOT_RECORDED = "-"


def label(run: dict) -> str:
    """A run's arm and its point on the arm's grid."""
    point = " ".join(f"{name}={value}" for name, value in run["point"].items())
    return f"{run['arm']} {point}".rstrip()


def summary(found: compare_readings.Verdict) -> str:
    if found.status == compare_readings.NOT_COMPARABLE:
        return f"not comparable: {found.reason}"
    falls = [measure.text.split(":", 1)[0].strip() for measure in found.measures if measure.falls]
    pooled = (f"; pooled {found.pooled.share * compare_readings.POINTS:+.2f} pt (95% from "
              f"{found.pooled.lower * compare_readings.POINTS:+.2f}): "
              f"{compare_readings.BETTER if found.pooled.better else compare_readings.NOT_BETTER}"
              if found.pooled else "")
    return f"{'FALL (' + '; '.join(falls) + ')' if falls else 'no fall'}{pooled}"


def eligible(found: compare_readings.Verdict) -> bool:
    return (found.status == compare_readings.NO_FALL and found.pooled is not None
            and found.pooled.better)


def noise(best: dict, other: dict, root: Path, sheet: dict, out) -> None:
    """Prints WITHIN NOISE when [best]'s readings are not better beyond chance than [other]'s."""
    found = compare_readings.verdict(root / other["readings"], root / best["readings"],
                                     sheet["budget"], sheet["margin"])
    if found.pooled is not None and not found.pooled.better:
        out.write(f"{WITHIN_NOISE}: {label(best)} and {label(other)} differ by "
                  f"{found.pooled.share * compare_readings.POINTS:+.2f} pt (95% "
                  f"{found.pooled.lower * compare_readings.POINTS:+.2f} to "
                  f"{found.pooled.upper * compare_readings.POINTS:+.2f})\n")


def digests(tools: Path = ROOT) -> dict[str, str]:
    """Each frozen tool's SHA-256, the tools read under [tools]."""
    return {name: hashlib.sha256((tools / name).read_bytes()).hexdigest() for name in FROZEN_TOOLS}


def tools_refusal(sheet: dict, tools: Path = ROOT) -> str | None:
    """Why [sheet] cannot be judged with the tools under [tools]: the frozen tools whose SHA-256
    is not the one the sheet records, one it does not record included; None when each matches."""
    recorded = sheet.get("tools", {})
    changed = [name for name, digest in digests(tools).items() if recorded.get(name) != digest]
    if not changed:
        return None
    return (f"{TOOLS_CHANGED}: {', '.join(changed)}; the sheet is judged only by the tools it was "
            "frozen with (judge_arms.py freeze), so nothing is judged")


def freeze(path: Path, tools: Path = ROOT) -> None:
    """Records the frozen tools' SHA-256 in the sheet at [path], once, before its first run."""
    sheet = json.loads(path.read_text(encoding="utf-8"))
    if "tools" in sheet:
        raise SystemExit(f"{path}: its tools are frozen already; a changed tool starts another "
                         "sheet")
    sheet["tools"] = digests(tools)
    path.write_text(json.dumps(sheet, indent=2) + "\n", encoding="utf-8")


def judge(path: Path, out, tools: Path = ROOT) -> int:
    """Writes the judgement of the sheet at [path] to [out]; 2 when a run cannot be compared, or
    when a frozen tool under [tools] changed."""
    sheet = json.loads(path.read_text(encoding="utf-8"))
    refusal = tools_refusal(sheet, tools)
    if refusal is not None:
        out.write(refusal + "\n")
        return 2
    root = path.parent
    status = 0
    ranked: dict[str, list[tuple[float, dict, compare_readings.Verdict]]] = {}
    for run in sheet["runs"]:
        found = compare_readings.verdict(root / sheet["base"], root / run["readings"],
                                         sheet["budget"], sheet["margin"])
        status = 2 if found.status == compare_readings.NOT_COMPARABLE else status
        out.write(f"{label(run)}: {summary(found)}\n")
        ranked.setdefault(run["arm"], [])
        if eligible(found):
            ranked[run["arm"]].append((found.pooled.share, run, found))
    bests = []
    for arm, runs in ranked.items():
        runs.sort(key=lambda entry: -entry[0])
        if not runs:
            out.write(f"best of {arm}: none eligible\n")
            continue
        share, best, found = runs[0]
        bests.append((share, best, found))
        point = " ".join(f"{name}={value}" for name, value in best["point"].items()) or "as built"
        out.write(f"best of {arm}: {point} ({share * compare_readings.POINTS:+.2f} pt)\n")
        for name, values in sheet["grid"].get(arm, {}).items():
            value = best["point"].get(name)
            if len(values) > 1 and value in (min(values), max(values)):
                side = "lower" if value == min(values) else "upper"
                out.write(f"{EDGE}: {arm}'s best sits on {name}'s {side} bound ({name}={value}); "
                          "reported, not chased\n")
        if len(runs) > 1:
            noise(best, runs[1][1], root, sheet, out)
    bests.sort(key=lambda entry: -entry[0])
    if bests:
        then = f", then {label(bests[1][1])}" if len(bests) > 1 else ""
        out.write(f"best arm: {label(bests[0][1])}{then}\n")
        if len(bests) > 1:
            noise(bests[0][1], bests[1][1], root, sheet, out)
        for measure in bests[0][2].measures:
            if measure.name.startswith("genre "):
                out.write(measure.line() + "\n")
    if len(ranked) >= sheet["arms"]:
        out.write(f"{BUDGET_SPENT}: {len(ranked)} arms run of {sheet['arms']}; the sheet goes to "
                  "its one decision\n")
    return status


def language_of(name: str) -> str:
    """The language a readings file belongs to: its file name up to the first dot."""
    return Path(name).name.split(".", 1)[0]


def compose(path: Path, where: dict, out: Path, stream, tools: Path = ROOT) -> dict[str, str]:
    """Writes into [out] the composite of the sheet's runs whose point matches [where]: each
    language's files from the run that gives its context rows' top three the highest net with no
    fall in that language's files, the first such run on a tie. Returns each language's run,
    recorded in COMPOSITE_RECORD. Refused when a frozen tool under [tools] changed."""
    sheet = json.loads(path.read_text(encoding="utf-8"))
    refusal = tools_refusal(sheet, tools)
    if refusal is not None:
        raise SystemExit(refusal)
    root = path.parent
    best: dict[str, tuple[int, str]] = {}
    for run in sheet["runs"]:
        if any(run["point"].get(key) != value for key, value in where.items()):
            continue
        found = compare_readings.verdict(root / sheet["base"], root / run["readings"],
                                         sheet["budget"], sheet["margin"])
        if found.status == compare_readings.NOT_COMPARABLE:
            stream.write(f"{label(run)}: not comparable: {found.reason}\n")
            continue
        falls = {language_of(m.file) for m in found.measures if m.falls}
        for measure in found.measures:
            language = language_of(measure.file)
            if not measure.series or language in falls:
                continue
            net = len(measure.fixed) - len(measure.lost)
            if language not in best or net > best[language][0]:
                best[language] = (net, run["readings"])
    out.mkdir(parents=True, exist_ok=True)
    choices = {language: run for language, (_, run) in sorted(best.items())}
    for language, run in choices.items():
        for name in compare_readings.files_under(root / run):
            if language_of(name) == language:
                target = out / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(root / run / name, target)
        stream.write(f"{language}: {run} (net {best[language][0]:+d} rows)\n")
    (out / COMPOSITE_RECORD).write_text(json.dumps(choices, indent=2) + "\n", encoding="utf-8")
    return choices


def tree_of(root: Path = ROOT) -> str:
    """HEAD, and a digest of every change against it and every untracked file when there are
    any."""
    def git(*arguments: str) -> bytes:
        return subprocess.run(["git", "-C", str(root), *arguments], check=True,
                              capture_output=True).stdout
    head = git("rev-parse", "--short=12", "HEAD").decode().strip()
    digest = hashlib.sha256(git("diff", "HEAD", "--binary"))
    listed = git("ls-files", "--others", "--exclude-standard", "-z")
    untracked = [name for name in listed.split(b"\0") if name]
    for name in sorted(untracked):
        digest.update(name + b"\0" + (root / name.decode()).read_bytes())
    changed = bool(git("status", "--porcelain"))
    return f"{head}+{digest.hexdigest()[:12]}" if changed else head


def record_read(ledger: Path, sheet: str, words: str, out, tree: str | None = None) -> int:
    """Records a TEST read of [sheet] in [ledger]; 1, recording nothing, when the ledger already
    holds one and no [words] allow another."""
    earlier = []
    if ledger.is_file():
        earlier = [line.split("\t") for line in ledger.read_text(encoding="utf-8").splitlines()
                   if line]
    earlier = [line for line in earlier if line[2] == sheet]
    if earlier and not words:
        when, at = earlier[0][0], earlier[0][1]
        out.write(f"{SECOND_TEST_READ}: sheet {sheet} was read at {when} on tree {at}; "
                  "--second-test-read gives the words that allow another\n")
        return 1
    when = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    ledger.parent.mkdir(parents=True, exist_ok=True)
    with ledger.open("a", encoding="utf-8") as file:
        file.write(f"{when}\t{tree or tree_of()}\t{sheet}\t{words or NOT_RECORDED}\n")
    out.write(f"TEST read of {sheet} recorded in {ledger}\n")
    return 0


def genre_counts(path: Path) -> Counter:
    """The sentences of a corpus by genre, from its sentence lines."""
    counts = Counter()
    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.startswith(SENTENCE_LINE):
                counts[line[len(SENTENCE_LINE):].split(" ", 1)[0]] += 1
    return counts


def genres(dev: Path, test: Path, out) -> int:
    """Writes each language's DEV and TEST sentences by genre to [out], and each TEST genre DEV
    lacks; 1 when there is one."""
    missing = 0
    for corpus in sorted(test.glob("context_*.txt")):
        language = corpus.stem[len("context_"):]
        tested, developed = genre_counts(corpus), genre_counts(dev / f"dev_{language}.txt")
        for side, counts in (("TEST", tested), ("DEV", developed)):
            listed = ", ".join(f"{genre} {count}" for genre, count in sorted(counts.items()))
            out.write(f"{language} {side}: {listed or 'none'}\n")
        for genre in sorted(set(tested) - set(developed)):
            out.write(f"{TEST_GENRE_MISSING}: {language} {genre}\n")
            missing += 1
    return 1 if missing else 0


def selftest() -> int:
    header = (f"{compare_readings.HEADER} {' '.join(compare_readings.CONTEXT_MEASURES)}\n"
              f"{compare_readings.FIELDS} sentence genre\n")
    # The judging tools the sheets below are frozen with, each a line of its own name.
    tools_directory = tempfile.TemporaryDirectory()
    tools = Path(tools_directory.name)
    for name in FROZEN_TOOLS:
        (tools / name).parent.mkdir(parents=True, exist_ok=True)
        (tools / name).write_text(f"{name}\n", encoding="utf-8")

    def readings(directory: Path, right: range | set, rows: int = 20000,
                 name: str = "en.tsv") -> Path:
        """A run of context rows, the rows in [right] with the target in the top three, each row
        its own sentence."""
        directory.mkdir(parents=True, exist_ok=True)
        text = header + "".join(
            f"0\t{int(index in right)}\t0\t0\tp{index} |w{index}\ts{index}\tweb\t-\t-\n"
            for index in range(rows))
        (directory / name).write_text(text, encoding="utf-8")
        return directory

    def judged(sheet: dict, root: Path) -> tuple[int, str]:
        path = root / "sheet.json"
        path.write_text(json.dumps({**sheet, "tools": digests(tools)}), encoding="utf-8")
        out = io.StringIO()
        return judge(path, out, tools), out.getvalue()

    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        readings(root / "base", set())
        for name, fixed in (("k1", 100), ("k2", 300), ("k3", 302), ("z", 200)):
            readings(root / name, range(fixed))
        runs = [{"arm": "target", "point": {"k": k}, "readings": f"k{k}"} for k in (1, 2, 3)]
        sheet = {"sheet": "test", "arms": 3, "budget": "ranker", "margin": 0.25, "base": "base",
                 "grid": {"target": {"k": [1, 2, 3]}, "zwf": {}},
                 "runs": runs + [{"arm": "zwf", "point": {}, "readings": "z"}]}
        status, report = judged(sheet, root)
        assert status == 0, report
        # k3 is best, by 2 rows over k2: on the grid's upper bound, and within noise of k2.
        assert "best of target: k=3" in report, report
        assert f"{EDGE}: target's best sits on k's upper bound" in report, report
        assert f"{WITHIN_NOISE}: target k=3 and target k=2" in report, report
        assert BUDGET_SPENT not in report, report
        # Across the arms, target's best leads zwf by 102 rows of 20,000: beyond noise.
        assert "best arm: target k=3, then zwf" in report, report
        assert f"{WITHIN_NOISE}: target k=3 and zwf" not in report, report

        # At the budget, the sheet goes to its decision; k=2 at the centre is no edge; and zwf,
        # one row behind it, is within noise of it.
        readings(root / "z299", range(299))
        sheet["arms"] = 2
        sheet["runs"] = runs[:2] + [{"arm": "zwf", "point": {}, "readings": "z299"}]
        status, report = judged(sheet, root)
        assert f"{BUDGET_SPENT}: 2 arms run of 2" in report and f"{EDGE}:" not in report, report
        assert f"{WITHIN_NOISE}: target k=2 and zwf" in report, report

        # A run that falls is never chosen, however high its net: against a base with rows
        # 1,000-1,399 right, one run fixes 1,000 rows and loses those 400 (churn over 4 : 1),
        # the other keeps them and fixes 200.
        readings(root / "base-kept", range(1000, 1400))
        readings(root / "churny", range(1000))
        readings(root / "kept", set(range(200)) | set(range(1000, 1400)))
        status, report = judged({"sheet": "test", "arms": 3, "budget": "small", "margin": 0.25,
                                 "base": "base-kept", "grid": {"a": {}, "b": {}},
                                 "runs": [{"arm": "a", "point": {}, "readings": "churny"},
                                          {"arm": "b", "point": {}, "readings": "kept"}]}, root)
        assert "a: FALL" in report and "best arm: b" in report, report

        # A sheet is judged only by the tools it was frozen with: with none recorded, or with
        # one changed since, no run is judged; a frozen sheet is never frozen again.
        path = root / "sheet.json"
        path.write_text(json.dumps(sheet), encoding="utf-8")
        out = io.StringIO()
        assert judge(path, out, tools) == 2, out.getvalue()
        assert f"{TOOLS_CHANGED}: {', '.join(FROZEN_TOOLS)};" in out.getvalue(), out.getvalue()
        assert "best" not in out.getvalue(), out.getvalue()
        freeze(path, tools)
        out = io.StringIO()
        assert judge(path, out, tools) == 0, out.getvalue()
        evaluator = tools / "native-tests" / "suggest_eval.cpp"
        frozen = evaluator.read_bytes()
        evaluator.write_text("changed\n", encoding="utf-8")
        out = io.StringIO()
        assert judge(path, out, tools) == 2, out.getvalue()
        assert f"{TOOLS_CHANGED}: native-tests/suggest_eval.cpp;" in out.getvalue(), out.getvalue()
        assert "best" not in out.getvalue(), out.getvalue()
        try:
            freeze(path, tools)
        except SystemExit:
            pass
        else:
            raise AssertionError("a frozen sheet was frozen again")
        evaluator.write_bytes(frozen)
        # Every tool that writes or reads the rows a run is judged by is frozen with the judges.
        rows_tools = ("native-tests/gesture_replay.cpp", "native-tests/tcn_replay.cpp",
                      "native-tests/touch_eval.cpp", "native-tests/rows_file.hpp",
                      "native-tests/context_runs.hpp", "native-tests/replay_corpora.hpp",
                      "native-tests/test_support.hpp", "tools/gesture_replay.py",
                      "tools/tcn_replay.py",
                      "keyboard/src/test/java/com/borderkeys/predict/CorrectionDevReadingsTest.kt",
                      "keyboard/src/test/java/com/borderkeys/predict/Pipeline.kt",
                      "keyboard/src/test/java/com/borderkeys/predict/PersonalCorpusTest.kt",
                      "keyboard/src/test/java/com/borderkeys/predict/TwoLanguageCorpusTest.kt")
        for name in rows_tools:
            tool_file = tools / name
            tool_file.parent.mkdir(parents=True, exist_ok=True)
            written = tool_file.read_bytes() if tool_file.is_file() else b""
            tool_file.write_text("changed\n", encoding="utf-8")
            out = io.StringIO()
            assert judge(path, out, tools) == 2, out.getvalue()
            assert f"{TOOLS_CHANGED}: {name};" in out.getvalue(), out.getvalue()
            tool_file.write_bytes(written)

    # Each frozen tool is in the repository, and every native tests' header a frozen one includes
    # is frozen with it.
    for name in FROZEN_TOOLS:
        assert (ROOT / name).is_file(), f"{name} is frozen and not in the repository"
        if name.startswith("native-tests/"):
            source = (ROOT / name).read_text(encoding="utf-8")
            for included in re.findall(r'#include "([^"]+)"', source):
                if (ROOT / "native-tests" / included).is_file():
                    assert f"native-tests/{included}" in FROZEN_TOOLS, f"{name} includes {included}"

    with tempfile.TemporaryDirectory() as directory:
        # The composite at one λ: each language from the run that gives its top three the highest
        # net with no fall in its own files. r3's English nets most but loses 400 rows of a
        # base that had them, over a 4 : 1 budget, so English comes from r1, German from r2;
        # r4's German is better still, at another λ.
        root = Path(directory)

        def two(directory: Path, english: range | set, german: range | set) -> None:
            readings(directory, english)
            readings(directory, german, name="de.tsv")

        two(root / "base", range(1000, 1400), set())
        two(root / "r1", set(range(300)) | set(range(1000, 1400)), range(100))
        two(root / "r2", set(range(100)) | set(range(1000, 1400)), range(300))
        two(root / "r3", range(1000), range(50))
        two(root / "r4", range(1000, 1400), range(400))
        sheet = {"sheet": "test", "arms": 3, "budget": "small", "margin": 0.25, "base": "base",
                 "grid": {"t": {"lambda": [1, 2]}},
                 "runs": [{"arm": "t", "point": {"lambda": 1, "alpha2": name}, "readings": name}
                          for name in ("r1", "r2", "r3")]
                         + [{"arm": "t", "point": {"lambda": 2, "alpha2": "r4"}, "readings": "r4"}]}
        path = root / "sheet.json"
        path.write_text(json.dumps(sheet), encoding="utf-8")
        try:
            compose(path, {"lambda": 1}, root / "composite", io.StringIO(), tools)
        except SystemExit as refusal:
            assert TOOLS_CHANGED in str(refusal), refusal
        else:
            raise AssertionError("a sheet with no tools recorded was composed")
        freeze(path, tools)
        out = io.StringIO()
        choices = compose(path, {"lambda": 1}, root / "composite", out, tools)
        assert choices == {"de": "r2", "en": "r1"}, (choices, out.getvalue())
        for language, run in choices.items():
            assert (root / "composite" / f"{language}.tsv").read_bytes() == \
                (root / run / f"{language}.tsv").read_bytes()
        assert json.loads((root / "composite" / COMPOSITE_RECORD).read_text()) == choices

    with tempfile.TemporaryDirectory() as directory:
        ledger = Path(directory) / "test-ledger.tsv"
        out = io.StringIO()
        assert record_read(ledger, "next-word", "", out, tree="abc") == 0, out.getvalue()
        out = io.StringIO()
        assert record_read(ledger, "next-word", "", out, tree="abd") == 1
        assert SECOND_TEST_READ in out.getvalue() and "abc" in out.getvalue(), out.getvalue()
        assert record_read(ledger, "other", "", io.StringIO(), tree="abd") == 0
        assert record_read(ledger, "next-word", "read it again, said so", io.StringIO(),
                           tree="abe") == 0
        lines = ledger.read_text(encoding="utf-8").splitlines()
        assert len(lines) == 3 and lines[-1].endswith("\tread it again, said so"), lines

    with tempfile.TemporaryDirectory() as directory:
        dev, test = Path(directory) / "dev", Path(directory) / "test"
        dev.mkdir()
        test.mkdir()
        line = "# sentence {} s{}\nwords here\n"
        (dev / "dev_en.txt").write_text(
            line.format("eng_news_2023_1M", 1) + line.format("answers", 2), encoding="utf-8")
        (test / "context_en.txt").write_text(line.format("answers", 3) + line.format("reviews", 4),
                                             encoding="utf-8")
        out = io.StringIO()
        genres(dev, test, out)
        report = out.getvalue()
        assert f"{TEST_GENRE_MISSING}: en reviews" in report, report
        assert f"{TEST_GENRE_MISSING}: en answers" not in report, report
    tools_directory.cleanup()
    print("judge_arms selftest: ok")
    return 0


def main() -> int:
    if "--selftest" in sys.argv[1:]:
        return selftest()
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    freezing = commands.add_parser("freeze", help="record the judging tools' SHA-256 in a sheet")
    freezing.add_argument("sheet", type=Path)
    judging = commands.add_parser("judge", help="judge a sheet's arms")
    judging.add_argument("sheet", type=Path)
    reading = commands.add_parser("ledger", help="record a TEST read of a sheet")
    reading.add_argument("ledger", type=Path)
    reading.add_argument("sheet")
    reading.add_argument("--second-test-read", default="", metavar="WORDS",
                         help="the words that allow a second TEST read of the sheet")
    composing = commands.add_parser("compose", help="each language from its best run")
    composing.add_argument("sheet", type=Path)
    composing.add_argument("out", type=Path)
    composing.add_argument("--where", action="append", default=[], metavar="NAME=VALUE",
                           help="only the runs whose point has this value; repeatable")
    counting = commands.add_parser("genres", help="DEV's genres beside TEST's")
    counting.add_argument("dev", type=Path)
    counting.add_argument("test", type=Path)
    arguments = parser.parse_args()
    if arguments.command == "freeze":
        freeze(arguments.sheet)
        return 0
    if arguments.command == "judge":
        return judge(arguments.sheet, sys.stdout)
    if arguments.command == "compose":
        where = {}
        for pair in arguments.where:
            name, _, value = pair.partition("=")
            where[name] = json.loads(value) if value[:1] in "-0123456789[{\"" else value
        compose(arguments.sheet, where, arguments.out, sys.stdout)
        return 0
    if arguments.command == "ledger":
        return record_read(arguments.ledger, arguments.sheet, arguments.second_test_read,
                           sys.stdout)
    return genres(arguments.dev, arguments.test, sys.stdout)


if __name__ == "__main__":
    sys.exit(main())
