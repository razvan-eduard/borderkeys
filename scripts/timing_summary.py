#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Summarises the runs scripts/timing.sh takes of native-tests/timing_eval, and orders them.

A run is a directory `<build>-<run>` under the runs directory, the run counted from 1, holding
one timing_eval file per language. Each file opens with HEADER and holds one line per timed
call: its group (workload, language, model and kind) and its microseconds. A percentile is read
by nearest rank, the value at rank ceil(p / 100 * n) of the n sorted.

The summary prints each group's p50 and p95 over every run's calls, each run's beside them, and
the calls per run. With two builds, `before` and `after`, it gives each group its verdict: held
when the median of after's runs' p95s is at most the median of before's plus before's range
(its highest run's p95 less its lowest), SLOWER otherwise, with after's range beside it. The
verdict is one-sided and as wide as before's spread: a noisy before holds a slower after. With
one build it gives none.

`order <runs>` prints each run's number and the two builds in the order it times them, as
scripts/timing.sh reads them: before first on the odd runs, after first on the even ones.

    python3 scripts/timing_summary.py <runs dir>
    python3 scripts/timing_summary.py order <runs>
    python3 scripts/timing_summary.py --selftest

Exits HELD, SLOWER when a group is slower, or NOT_COMPARABLE when the runs did not time the same
calls (a build with fewer runs, a group timed a different number of times in one run), when a
file is not timing_eval's, or when the builds are neither one nor before and after.
"""

from __future__ import annotations

import argparse
import io
import re
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from statistics import median

# The whole of a percentile, and the two the summary reads.
PERCENT = 100
P50 = 50
P95 = 95

# The two builds a comparison times, by the names of their run directories.
BEFORE = "before"
AFTER = "after"

# The exit statuses, and the words a report names them by.
HELD = 0
SLOWER = 1
NOT_COMPARABLE = 2
HELD_WORD = "held"
SLOWER_WORD = "SLOWER"
NOT_COMPARABLE_WORD = "NOT COMPARABLE"

# The command that prints the runs' order.
ORDER = "order"

# The first line of each file timing_eval writes: the fields of a group, then the call's time.
HEADER = "workload\tlanguage\tmodel\tkind\tmicroseconds"
GROUP_FIELDS = 4
# A run's directory: its build's name and its number from 1.
RUN_DIRECTORY = re.compile(r"(?P<build>.+)-(?P<run>[1-9][0-9]*)")

# A group of calls: its workload, language, model and kind.
Group = tuple[str, ...]
# One run's calls, each group's times in microseconds in the order timed.
Table = dict[Group, list[float]]


def nearest_rank(samples: list[float], percent: int) -> float:
    """The [percent]th percentile of [samples] by nearest rank: the value at rank
    ceil(percent / 100 * n) of the n sorted, the first when that rank is 0."""
    ordered = sorted(samples)
    rank = -(-percent * len(ordered) // PERCENT)
    return ordered[max(rank, 1) - 1]


def run_order(runs: int) -> list[tuple[int, tuple[str, str]]]:
    """Each of [runs] runs from 1, with the two builds in the order it times them: before first
    on the odd runs, after first on the even ones."""
    return [(run, (BEFORE, AFTER) if run % 2 == 1 else (AFTER, BEFORE)) for run in range(1, runs + 1)]


def read_table(directory: Path) -> Table:
    """The calls of every file in one run's [directory], grouped; a file that does not open with
    HEADER, or a line that is not a group and a time, is refused with a ValueError."""
    table: Table = {}
    for path in sorted(directory.glob("*.tsv")):
        lines = path.read_text(encoding="utf-8").splitlines()
        if not lines or lines[0] != HEADER:
            raise ValueError(f"{directory.name}/{path.name}: does not open with the header")
        for number, line in enumerate(lines[1:], 2):
            fields = line.split("\t")
            if len(fields) != GROUP_FIELDS + 1:
                raise ValueError(f"{directory.name}/{path.name}:{number}: not a group and a time")
            table.setdefault(tuple(fields[:GROUP_FIELDS]), []).append(float(fields[GROUP_FIELDS]))
    return table


def read_runs(root: Path) -> dict[str, list[Table]]:
    """Each build's runs under [root], in run order, from the directories `<build>-<run>`."""
    numbered: dict[str, dict[int, Table]] = {}
    if not root.is_dir():
        return {}
    for directory in sorted(path for path in root.iterdir() if path.is_dir()):
        named = RUN_DIRECTORY.fullmatch(directory.name)
        if named is not None:
            numbered.setdefault(named["build"], {})[int(named["run"])] = read_table(directory)
    return {build: [runs[number] for number in sorted(runs)] for build, runs in numbered.items()}


def coverage(builds: dict[str, list[Table]]) -> str | None:
    """Why [builds]' runs did not time the same calls: a build with fewer runs, or a group timed
    a different number of times in one run than in the first run of the first build; None when
    every run timed the same."""
    names = sorted(builds, key=lambda build: (build != BEFORE, build))
    first = names[0]
    for build in names[1:]:
        if len(builds[build]) != len(builds[first]):
            return f"{first} has {len(builds[first])} runs, {build} {len(builds[build])}"
    reference = builds[first][0]
    for build in names:
        for number, table in enumerate(builds[build], 1):
            for group in sorted(set(reference) | set(table)):
                expected = len(reference.get(group, []))
                found = len(table.get(group, []))
                if found != expected:
                    return (f"{' '.join(group)}: {expected} calls in {first}-1, {found} in "
                            f"{build}-{number}")
    return None


@dataclass
class Verdict:
    """One group's verdict: after's p95, the bound it is held to, whether it held, and the range
    of each build's runs' p95s."""
    after_p95: float
    before_p95: float
    before_range: float
    after_range: float

    @property
    def bound(self) -> float:
        return self.before_p95 + self.before_range

    @property
    def held(self) -> bool:
        return self.after_p95 <= self.bound


def run_percentiles(runs: list[Table], group: Group, percent: int) -> list[float]:
    """[group]'s [percent]th percentile in each of [runs]."""
    return [nearest_rank(table[group], percent) for table in runs]


def verdict(before: list[Table], after: list[Table], group: Group) -> Verdict:
    """[group]'s verdict: the median of after's runs' p95s held to the median of before's plus
    before's range, its highest run's p95 less its lowest; after's range is read beside it."""
    before_p95s = run_percentiles(before, group, P95)
    after_p95s = run_percentiles(after, group, P95)
    return Verdict(after_p95=median(after_p95s), before_p95=median(before_p95s),
                   before_range=max(before_p95s) - min(before_p95s),
                   after_range=max(after_p95s) - min(after_p95s))


def summarise(root: Path, out) -> int:
    """Writes the summary of the runs under [root] into [out] and returns its exit status."""
    try:
        builds = read_runs(root)
    except ValueError as error:
        out.write(f"{NOT_COMPARABLE_WORD}: {error}\n")
        return NOT_COMPARABLE
    if not builds:
        out.write(f"{NOT_COMPARABLE_WORD}: no runs under {root}\n")
        return NOT_COMPARABLE
    comparing = sorted(builds) == sorted((BEFORE, AFTER))
    if len(builds) > 1 and not comparing:
        out.write(f"{NOT_COMPARABLE_WORD}: the builds are {' and '.join(sorted(builds))}, not one, "
                  f"nor {BEFORE} and {AFTER}\n")
        return NOT_COMPARABLE
    reason = coverage(builds)
    if reason is not None:
        out.write(f"{NOT_COMPARABLE_WORD}: {reason}\n")
        return NOT_COMPARABLE
    names = [BEFORE, AFTER] if comparing else list(builds)
    groups = list(builds[names[0]][0])
    slower = 0
    for group in groups:
        out.write(f"{' '.join(group)}: calls {len(builds[names[0]][0][group])} per run\n")
        for build in names:
            runs = builds[build]
            pooled = [value for table in runs for value in table[group]]
            parts = []
            for percent in (P50, P95):
                each = " ".join(f"{value:.2f}" for value in run_percentiles(runs, group, percent))
                parts.append(f"p{percent} {nearest_rank(pooled, percent):.2f} ({each})")
            out.write(f"  {build:<7} {'  '.join(parts)}\n")
        if comparing:
            found = verdict(builds[BEFORE], builds[AFTER], group)
            slower += 0 if found.held else 1
            out.write(f"  {HELD_WORD if found.held else SLOWER_WORD}: p95 {found.after_p95:.2f} us, "
                      f"bound {found.bound:.2f} us (before {found.before_p95:.2f} + its range "
                      f"{found.before_range:.2f}; after's range {found.after_range:.2f})\n")
    runs = len(builds[names[0]])
    if not comparing:
        out.write(f"{len(groups)} groups, {runs} runs of {names[0]}\n")
        return HELD
    if slower:
        out.write(f"{SLOWER_WORD}: {slower} of {len(groups)} groups, {runs} runs\n")
        return SLOWER
    out.write(f"{HELD_WORD}: {len(groups)} groups, {runs} runs\n")
    return HELD


def write_runs(root: Path, build: str, tables: list[Table]) -> None:
    """Writes [tables] as [build]'s runs under [root], one file per run, as timing_eval writes
    them."""
    for number, table in enumerate(tables, 1):
        directory = root / f"{build}-{number}"
        directory.mkdir()
        lines = [HEADER] + ["\t".join(group) + f"\t{value}" for group, values in table.items()
                            for value in values]
        (directory / "en.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")


def selftest() -> None:
    ten = [float(value) for value in range(10, 0, -1)]
    assert nearest_rank(ten, 50) == 5.0, nearest_rank(ten, 50)
    assert nearest_rank(ten, 95) == 10.0, nearest_rank(ten, 95)
    twenty = [float(value) for value in range(1, 21)]
    assert nearest_rank(twenty, 50) == 10.0, nearest_rank(twenty, 50)
    assert nearest_rank(twenty, 95) == 19.0, nearest_rank(twenty, 95)
    assert nearest_rank([7.0], 95) == 7.0
    assert nearest_rank([3.0, 1.0], 0) == 1.0

    assert run_order(5) == [(1, (BEFORE, AFTER)), (2, (AFTER, BEFORE)), (3, (BEFORE, AFTER)),
                            (4, (AFTER, BEFORE)), (5, (BEFORE, AFTER))], run_order(5)
    assert run_order(1) == [(1, (BEFORE, AFTER))], run_order(1)

    next_word = ("typed", "en-US", "none", "0")
    slipped = ("taps", "en-US", "history", "slipped")
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        tables = [{next_word: [3.0, 1.0], slipped: [9.5]}, {next_word: [2.0, 2.5], slipped: [8.0]}]
        write_runs(root, BEFORE, tables)
        write_runs(root, AFTER, tables)
        builds = read_runs(root)
        assert sorted(builds) == [AFTER, BEFORE], sorted(builds)
        assert builds[BEFORE] == tables, builds[BEFORE]
        assert coverage(builds) is None, coverage(builds)
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        write_runs(root, BEFORE, tables)
        write_runs(root, AFTER, [tables[0], {next_word: [2.0], slipped: [8.0]}])
        reason = coverage(read_runs(root))
        assert reason == "typed en-US none 0: 2 calls in before-1, 1 in after-2", reason
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        write_runs(root, BEFORE, tables)
        write_runs(root, AFTER, [tables[0], {next_word: [2.0, 2.5]}])
        reason = coverage(read_runs(root))
        assert reason == "taps en-US history slipped: 1 calls in before-1, 0 in after-2", reason
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        write_runs(root, BEFORE, tables)
        write_runs(root, AFTER, tables[:1])
        reason = coverage(read_runs(root))
        assert reason == "before has 2 runs, after 1", reason
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        write_runs(root, BEFORE, tables)
        (root / f"{BEFORE}-2" / "en.tsv").write_text("typed\ten-US\n", encoding="utf-8")
        try:
            read_runs(root)
        except ValueError as error:
            assert "before-2/en.tsv" in str(error), error
        else:
            raise AssertionError("a file without the header was read")

    # Five runs of 1..20 microseconds: p50 10, p95 19 in each.
    steady = [{next_word: twenty} for _ in range(5)]
    found = verdict(steady, steady, next_word)
    assert found.held and found.bound == 19.0 and found.after_p95 == 19.0, found
    # The same median, the tail slower: p95 40.
    tail = [{next_word: twenty[:18] + [40.0, 50.0]} for _ in range(5)]
    found = verdict(steady, tail, next_word)
    assert not found.held and found.after_p95 == 40.0, found
    # Before's p95 runs 18 to 22 (median 19, range 4): 23 is within, 23.5 is not.
    spread = [{next_word: twenty[:18] + [p95, p95]} for p95 in (18.0, 19.0, 19.0, 21.0, 22.0)]
    found = verdict(spread, [{next_word: twenty[:18] + [23.0, 23.0]} for _ in range(5)], next_word)
    assert found.held and found.bound == 23.0, found
    found = verdict(spread, [{next_word: twenty[:18] + [23.5, 23.5]} for _ in range(5)], next_word)
    assert not found.held, found

    def summary_of(runs: dict[str, list[Table]]) -> tuple[int, str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for build, tables in runs.items():
                write_runs(root, build, tables)
            out = io.StringIO()
            return summarise(root, out), out.getvalue()

    status, report = summary_of({BEFORE: steady, AFTER: steady})
    assert status == HELD and f"{HELD_WORD}: p95 19.00 us, bound 19.00 us" in report, report
    assert "p50 10.00 (10.00 10.00 10.00 10.00 10.00)" in report and "calls 20 per run" in report, report
    status, report = summary_of({BEFORE: steady, AFTER: tail})
    assert status == SLOWER and f"{SLOWER_WORD}: p95 40.00 us, bound 19.00 us" in report, report
    # Both builds' spreads are printed: before's widens the bound, after's is read beside it.
    status, report = summary_of({BEFORE: spread, AFTER: steady})
    assert status == HELD, report
    assert "(before 19.00 + its range 4.00; after's range 0.00)" in report, report
    status, report = summary_of({BEFORE: steady, AFTER: spread})
    assert status == HELD, report
    assert "(before 19.00 + its range 0.00; after's range 4.00)" in report, report
    status, report = summary_of({BEFORE: steady, AFTER: steady[:4]})
    assert status == NOT_COMPARABLE, report
    assert f"{NOT_COMPARABLE_WORD}: before has 5 runs, after 4" in report, report
    status, report = summary_of({"tree": steady})
    assert status == HELD and HELD_WORD not in report and "p95 19.00 (19.00" in report, report
    status, report = summary_of({BEFORE: steady, "tree": steady})
    assert status == NOT_COMPARABLE and "before and tree" in report, report
    print("timing_summary selftest: ok")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("target", nargs="*", help="the runs directory, or `order` and the runs")
    parser.add_argument("--selftest", action="store_true", help="prove the summary on synthetic runs")
    arguments = parser.parse_args()
    if arguments.selftest:
        selftest()
        return HELD
    if len(arguments.target) == 2 and arguments.target[0] == ORDER:
        for run, builds in run_order(int(arguments.target[1])):
            print(run, *builds)
        return HELD
    if len(arguments.target) != 1:
        parser.error(f"a runs directory, or {ORDER} and the number of runs, is required")
    return summarise(Path(arguments.target[0]), sys.stdout)


if __name__ == "__main__":
    sys.exit(main())
