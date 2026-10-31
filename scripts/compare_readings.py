#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Compares two readings directories row by row and gives the verdict on the change: the rows
each corpus fixed and lost, whether the change is worse beyond chance, and whether its churn is
over budget beyond chance.

A rows file opens with `# measures: <name> [<name> ...]` and may follow it with
`# fields: <name> ...`. Each row after them holds one 1 or 0 per measure, the row's key (what was
typed, with its context), one value per named field, then the reading's own fields,
tab-separated, in corpus order. The fields read are `sentence` and `genre`; one side's are
enough, and when both sides carry them they agree. Files are paired by their path under the two
directories; any other file that does not open with the measures line (a report) is listed as
not judged.

For each file and measure, a row is fixed when its verdict goes from 0 to 1 and lost when it goes
from 1 to 0. A measure with fewer than FEWEST_CHANGED_ROWS changed rows is reported, never judged
alone: the small files of one family (`native/autocorrect` for `native/autocorrect_twins_ro.tsv`)
are pooled per measure and judged together as one more corpus. Otherwise the net, (fixed - lost) / rows, is bounded by resampling whole sentences (each
row alone when neither side names its sentence), and the churn, the lost share among the changed
(previous word, target) pairs, by Wilson's interval.

Context rows (`suggest_eval --context`) are judged on their top three, each file on its own: it
falls when the net's lower bound is below -margin or the churn's lower bound is above the budget;
the other measures are reported beside it. When the rows carry two or more genres, a genre whose
net is negative beyond chance after Holm across every genre falls. The top three pooled over
every context file is reported as better beyond chance when the pooled lower bound is above 0.
Every other corpus falls when its net is below -margin beyond chance, or its churn above the
budget beyond chance, each by Holm across them all.

    python3 scripts/compare_readings.py <before dir> <after dir> [--budget small|ranker]
        [--margin POINTS]
    python3 scripts/compare_readings.py --selftest

Exits NO_FALL, FALL, or NOT_COMPARABLE when a rows file is on one side only, lacks its measures
line, carries it on one side only, names other measures, holds other rows, or puts a row in
another sentence.
"""

from __future__ import annotations

import argparse
import io
import math
import sys
import tempfile
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from random import Random
from statistics import NormalDist

HEADER = "# measures:"
FIELDS = "# fields:"

# The fields a rows file may name after its key.
SENTENCE = "sentence"
GENRE = "genre"

# What suggest_eval --context writes per word and letters typed; its top three is judged, the
# others are reported beside it.
CONTEXT_MEASURES = ("first", "top-three", "first-without-context", "top-three-without-context")
JUDGED_MEASURE = "top-three"

# The churn budget: the share of changed pairs a change may lose, by its kind.
SMALL_RULE_FIXED_PER_LOST = 4
RANKER_FIXED_PER_LOST = 2
CHURN_BUDGETS = {
    "small": 1 / (SMALL_RULE_FIXED_PER_LOST + 1),
    "ranker": 1 / (RANKER_FIXED_PER_LOST + 1),
}
DEFAULT_BUDGET = "small"

# The net test's margin, in points of the file's rows.
MARGIN_POINTS = 0.25
POINTS = 100

# The net test resamples whole sentences this many times, from this seed and the file's name.
RESAMPLES = 9999
BOOTSTRAP_SEED = 20261025

# Each bound leaves this share outside it: the two ends of a 95% interval.
OUTSIDE_EACH_BOUND = 0.025
BOUND_Z = NormalDist().inv_cdf(1 - OUTSIDE_EACH_BOUND)

# A file or measure with fewer changed rows is reported and traced, never judged alone.
FEWEST_CHANGED_ROWS = 30

# The fewest small files of one family that are pooled and judged together.
FEWEST_POOLED_FILES = 2

# A rows file's suffix: one without the measures line cannot be compared.
ROWS_SUFFIX = ".tsv"

# Context rows are split by genre when they carry at least this many.
FEWEST_GENRES = 2

# The key's letters typed and its target word sit either side of this mark.
TARGET_MARK = "|"

SMALL = "reported: fewer than"
CHURN_FALL = "FALL: churn over budget"
WORSE_FALL = "FALL: worse"
BESIDE = "reported beside"
GENRE_FALL = "FALL: a genre negative beyond chance"
BETTER = "better beyond chance"
NOT_BETTER = "not shown better"
POOLED = "pooled"

NO_FALL = 0
FALL = 1
NOT_COMPARABLE = 2


@dataclass
class Row:
    verdicts: tuple[bool, ...]
    key: str
    # The sentence the row was asked in and its genre, when the rows carry them.
    sentence: str | None
    genre: str | None
    reading: str


@dataclass
class Rows:
    measures: tuple[str, ...]
    rows: list[Row]


class NotComparable(Exception):
    pass


def read_rows(path: Path) -> Rows | None:
    """The rows of [path], or None when it does not open with the measures line."""
    lines = path.read_text(encoding="utf-8", errors="replace").split("\n")
    if not lines[0].startswith(HEADER):
        return None
    measures = tuple(lines[0][len(HEADER):].split())
    fields: tuple[str, ...] = ()
    body = 1
    if len(lines) > 1 and lines[1].startswith(FIELDS):
        fields = tuple(lines[1][len(FIELDS):].split())
        body = 2
    rows = []
    for number, line in enumerate(lines[body:], start=body + 1):
        if not line:
            continue
        cells = line.split("\t")
        reading_at = len(measures) + 1 + len(fields)
        if len(cells) < reading_at or any(c not in ("0", "1") for c in cells[:len(measures)]):
            raise NotComparable(f"{path}:{number}: not {len(measures)} verdicts, a key and "
                                f"{len(fields)} fields")
        named = dict(zip(fields, cells[len(measures) + 1:reading_at]))
        rows.append(Row(
            verdicts=tuple(c == "1" for c in cells[:len(measures)]),
            key=cells[len(measures)],
            sentence=named.get(SENTENCE),
            genre=named.get(GENRE),
            reading="\t".join(cells[reading_at:]),
        ))
    return Rows(measures, rows)


def pair(key: str) -> str:
    """The (previous word, target) pair a row asks: its key without the letters typed. A key
    with no target mark is its own pair."""
    if TARGET_MARK not in key:
        return key
    context, target = key.rsplit(TARGET_MARK, 1)
    previous = context.rsplit(" ", 1)[0]
    return f"{previous}{TARGET_MARK}{target}"


def wilson_lower(part: int, whole: int) -> float:
    """The lower bound of the share [part] / [whole], Wilson's score interval."""
    share = part / whole
    z2 = BOUND_Z * BOUND_Z
    spread = BOUND_Z * math.sqrt(share * (1 - share) / whole + z2 / (4 * whole * whole))
    return (share + z2 / (2 * whole) - spread) / (1 + z2 / whole)


def binomial(random: Random, trials: int, chance: float) -> int:
    """A draw of the number of [trials] that hit at [chance]: `binomialvariate` where the
    Python has it (3.12 on), else the same law drawn as geometric gaps between hits."""
    if hasattr(random, "binomialvariate"):
        return random.binomialvariate(trials, chance)
    if chance <= 0 or trials <= 0:
        return 0
    if chance >= 1:
        return trials
    if chance > 0.5:
        return trials - binomial(random, trials, 1 - chance)
    # The trials between hits are geometric: each gap is drawn at once, so the draw takes about
    # trials * chance steps.
    miss = math.log(1 - chance)
    hits = 0
    at = int(math.log(1 - random.random()) / miss) + 1
    while at <= trials:
        hits += 1
        at += int(math.log(1 - random.random()) / miss) + 1
    return hits


def resample(sizes: list[int], nets: list[list[int]], seed: str) -> list[tuple[int, list[int]]]:
    """RESAMPLES draws of len([sizes]) sentences with replacement, each as its rows and its net
    per measure ([nets][measure][sentence]). Sentences alike in rows and nets are drawn by count;
    a sentence like no other is drawn on its own."""
    count = len(sizes)
    alike = Counter((sizes[at], tuple(net[at] for net in nets)) for at in range(count))
    groups = sorted((shape, members) for shape, members in alike.items() if members > 1)
    single = sorted(shape for shape, members in alike.items() if members == 1)
    single_sizes = [size for size, _ in single]
    single_nets = [[shape[measure] for _, shape in single] for measure in range(len(nets))]
    picks = range(len(single))
    random = Random(f"{seed}:{BOOTSTRAP_SEED}")
    draws = []
    for _ in range(RESAMPLES):
        hits = binomial(random, count, len(single) / count) if single else 0
        drawn = random.choices(picks, k=hits)
        rows = sum(map(single_sizes.__getitem__, drawn))
        totals = [sum(map(net.__getitem__, drawn)) for net in single_nets]
        left = count - hits
        pool = count - len(single)
        for (size, shape), members in groups:
            taken = left if members == pool else binomial(random, left, members / pool)
            rows += taken * size
            for measure, net in enumerate(shape):
                totals[measure] += taken * net
            left -= taken
            pool -= members
        draws.append((rows, totals))
    return draws


def bounds(shares: list[float]) -> tuple[float, float]:
    """The lower and upper 95% bounds of resampled [shares], by percentile."""
    ordered = sorted(shares)
    outside = int(OUTSIDE_EACH_BOUND * len(ordered))
    return ordered[outside], ordered[len(ordered) - 1 - outside]


@dataclass
class Measure:
    name: str
    # The rows file it was read from, by its path under the readings directory.
    file: str
    fixed: list[tuple[Row, Row]]
    lost: list[tuple[Row, Row]]
    judged: bool
    rows: int
    # Context rows are judged on their own; every other corpus by Holm across them all.
    secondary: bool
    text: str = ""
    verdicts: list[str] = field(default_factory=list)
    # A secondary measure's chance of looking this much worse, and this churny, by luck.
    p_worse: float | None = None
    p_churn: float | None = None
    # A genre's chance of a net this negative by luck.
    p_negative: float | None = None
    falls: bool = False
    # Whether its lost and fixed rows are listed under it; a genre's are listed with its file's.
    traced: bool = True
    better: bool = False
    # A context file's judged measure, resampled: each draw's rows and net, for the pool.
    series: list[tuple[int, int]] = field(default_factory=list)

    def line(self) -> str:
        return (f"{self.text}: {'; '.join(self.verdicts) or 'no fall'}"
                f"{', ' + BETTER if self.better else ''}")


def net_text(fixed: int, lost: int, rows: int, lower: float, upper: float) -> str:
    return (f"net {fixed - lost:+d} rows ({(fixed - lost) / rows * POINTS:+.2f} pt, 95% "
            f"{lower * POINTS:+.2f} to {upper * POINTS:+.2f}")


def churn_p(lost: int, changed: int, budget: float) -> float:
    """The chance of a lost share this high among [changed] pairs when the true share is
    [budget]: the score test Wilson's interval inverts."""
    spread = math.sqrt(budget * (1 - budget) / changed)
    return 1 - NormalDist().cdf((lost / changed - budget) / spread)


def holm(chances: list[float]) -> set[int]:
    """The indices of [chances] Holm's step-down procedure rejects at OUTSIDE_EACH_BOUND."""
    rejected = set()
    for rank, index in enumerate(sorted(range(len(chances)), key=chances.__getitem__)):
        if chances[index] > OUTSIDE_EACH_BOUND / (len(chances) - rank):
            break
        rejected.add(index)
    return rejected


def sentences_of(name: str, before: Rows, after: Rows) -> list[object]:
    """Each row's sentence: from whichever side carries it, the two agreeing; a row alone
    when neither does."""
    out: list[object] = []
    for index, (was, now) in enumerate(zip(before.rows, after.rows)):
        if was.sentence is not None and now.sentence is not None and \
                (was.sentence, was.genre) != (now.sentence, now.genre):
            raise NotComparable(f"{name}: row {index + 1} in sentence {was.sentence} before, "
                                f"{now.sentence} after")
        source = now if now.sentence is not None else was
        out.append((source.genre, source.sentence) if source.sentence is not None else index)
    return out


def judge(name: str, before: Rows, after: Rows, budget: float, margin: float) -> list[Measure]:
    """[name]'s measures, each with its fixed and lost rows, its net test against [margin] and
    its churn against [budget]."""
    if before.measures != after.measures:
        raise NotComparable(f"{name}: measures {' '.join(before.measures)} before, "
                            f"{' '.join(after.measures)} after")
    if len(before.rows) != len(after.rows):
        raise NotComparable(f"{name}: {len(before.rows)} rows before, {len(after.rows)} after")
    for was, now in zip(before.rows, after.rows):
        if was.key != now.key:
            raise NotComparable(f"{name}: key {was.key!r} before where {now.key!r} is after")
    context = before.measures == CONTEXT_MEASURES
    cluster = sentences_of(name, before, after)
    at = {sentence: index for index, sentence in enumerate(dict.fromkeys(cluster))}
    sizes = [0] * len(at)
    nets = [[0] * len(at) for _ in before.measures]
    for row, sentence in enumerate(cluster):
        sizes[at[sentence]] += 1
        for index in range(len(before.measures)):
            was, now = before.rows[row].verdicts[index], after.rows[row].verdicts[index]
            nets[index][at[sentence]] += int(now) - int(was)
    draws = resample(sizes, nets, name) if sizes else []
    measures = []
    for index, name_of_measure in enumerate(before.measures):
        pairs = list(zip(before.rows, after.rows))
        measure = Measure(
            name=name_of_measure,
            file=name,
            fixed=[(was, now) for was, now in pairs
                   if not was.verdicts[index] and now.verdicts[index]],
            lost=[(was, now) for was, now in pairs
                  if was.verdicts[index] and not now.verdicts[index]],
            judged=not context or name_of_measure == JUDGED_MEASURE,
            rows=len(pairs),
            secondary=not context)
        if context and measure.judged:
            measure.series = [(total, totals[index]) for total, totals in draws]
        fixed, lost = len(measure.fixed), len(measure.lost)
        text = f"{name} {name_of_measure}: fixed {fixed}, lost {lost}"
        if fixed + lost < FEWEST_CHANGED_ROWS:
            measure.text = text
            measure.verdicts.append(f"{SMALL} {FEWEST_CHANGED_ROWS} changed rows")
            measures.append(measure)
            continue
        shares = [totals[index] / total for total, totals in draws]
        lower, upper = bounds(shares)
        pairs_fixed = len({pair(was.key) for was, _ in measure.fixed})
        pairs_lost = len({pair(was.key) for was, _ in measure.lost})
        churn_lower = wilson_lower(pairs_lost, pairs_fixed + pairs_lost)
        measure.text = (
            f"{text}, {net_text(fixed, lost, measure.rows, lower, upper)}, margin {-margin:.2f}); "
            f"pairs fixed {pairs_fixed}, lost {pairs_lost}, lost share "
            f"{pairs_lost / (pairs_fixed + pairs_lost):.3f} (95% from {churn_lower:.3f}), budget "
            f"{budget:.3f}")
        if not measure.judged:
            measure.verdicts.append(f"{BESIDE} {JUDGED_MEASURE}")
        elif measure.secondary:
            not_worse = sum(1 for share in shares if share >= -margin / POINTS)
            measure.p_worse = (1 + not_worse) / (RESAMPLES + 1)
            measure.p_churn = churn_p(pairs_lost, pairs_fixed + pairs_lost, budget)
            measure.text += f", p worse {measure.p_worse:.4f}, p over budget {measure.p_churn:.4f}"
        else:
            if lower < -margin / POINTS:
                measure.verdicts.append(WORSE_FALL)
            if churn_lower > budget:
                measure.verdicts.append(CHURN_FALL)
            measure.falls = bool(measure.verdicts)
            measure.better = lower > 0
        measures.append(measure)
    genres = sorted({sentence[0] for sentence in at if isinstance(sentence, tuple)})
    if context and len(genres) >= FEWEST_GENRES:
        measures.extend(split_by_genre(name, before, after, cluster, genres))
    return measures


def split_by_genre(name: str, before: Rows, after: Rows, cluster: list[object],
                   genres: list[str]) -> list[Measure]:
    """[name]'s judged measure per genre, each genre's sentences resampled on their own, with
    its chance of a net this negative by luck."""
    index = before.measures.index(JUDGED_MEASURE)
    out = []
    for genre in genres:
        rows = [row for row, sentence in enumerate(cluster)
                if isinstance(sentence, tuple) and sentence[0] == genre]
        at = {sentence: place
              for place, sentence in enumerate(dict.fromkeys(cluster[row] for row in rows))}
        sizes = [0] * len(at)
        nets = [0] * len(at)
        measure = Measure(name=f"genre {genre} {JUDGED_MEASURE}", file=name, fixed=[], lost=[],
                          judged=True, rows=len(rows), secondary=False, traced=False)
        for row in rows:
            was, now = before.rows[row], after.rows[row]
            sizes[at[cluster[row]]] += 1
            nets[at[cluster[row]]] += int(now.verdicts[index]) - int(was.verdicts[index])
            if was.verdicts[index] != now.verdicts[index]:
                (measure.fixed if now.verdicts[index] else measure.lost).append((was, now))
        fixed, lost = len(measure.fixed), len(measure.lost)
        measure.text = f"  {name} genre {genre} {JUDGED_MEASURE}: fixed {fixed}, lost {lost}"
        if fixed + lost < FEWEST_CHANGED_ROWS:
            measure.verdicts.append(f"{SMALL} {FEWEST_CHANGED_ROWS} changed rows")
        else:
            draws = resample(sizes, [nets], f"{name}:{genre}")
            shares = [totals[0] / total for total, totals in draws]
            lower, upper = bounds(shares)
            measure.p_negative = (1 + sum(1 for share in shares if share >= 0)) / (RESAMPLES + 1)
            measure.text += (f", {net_text(fixed, lost, len(rows), lower, upper)}), p negative "
                             f"{measure.p_negative:.4f}")
        out.append(measure)
    return out


def judge_secondary(measures: list[Measure]) -> None:
    """Gives every judged secondary measure its verdict: worse beyond chance, and churn over
    budget beyond chance, each by Holm across them all."""
    judged = [measure for measure in measures if measure.p_worse is not None]
    for chance, verdict in (("p_worse", f"{WORSE_FALL} beyond chance"),
                            ("p_churn", f"{CHURN_FALL} beyond chance")):
        for index in holm([getattr(measure, chance) for measure in judged]):
            judged[index].verdicts.append(verdict)
            judged[index].falls = True
    for measure in judged:
        measure.verdicts.append(
            f"{'' if measure.falls else 'no fall '}after Holm across {len(judged)}")


def judge_genres(measures: list[Measure]) -> None:
    """Gives every judged genre its verdict: negative beyond chance, by Holm across them all."""
    judged = [measure for measure in measures if measure.p_negative is not None]
    rejected = holm([measure.p_negative for measure in judged])
    for index, measure in enumerate(judged):
        measure.falls = index in rejected
        measure.verdicts.append(f"{GENRE_FALL + ', ' if measure.falls else 'no fall '}"
                                f"after Holm across {len(judged)}")


def write(measures: list[Measure], out) -> None:
    """Writes each measure's line, then its lost and fixed rows."""
    for measure in measures:
        out.write(measure.line() + "\n")
        if not measure.traced:
            continue
        for label, pairs in (("lost  ", measure.lost), ("fixed ", measure.fixed)):
            for was, now in pairs:
                out.write(f"  {label} {was.key}\n      before  {was.reading}\n      after   {now.reading}\n")


@dataclass
class Pool:
    """The context files' judged measure pooled over them all."""
    files: int
    fixed: int
    lost: int
    rows: int
    lower: float
    upper: float

    @property
    def share(self) -> float:
        return (self.fixed - self.lost) / self.rows

    @property
    def better(self) -> bool:
        return self.lower > 0

    def line(self) -> str:
        return (f"{POOLED} over {self.files} context file{'s' if self.files > 1 else ''} "
                f"{JUDGED_MEASURE}: fixed {self.fixed}, lost {self.lost}, "
                f"{net_text(self.fixed, self.lost, self.rows, self.lower, self.upper)}): "
                f"{BETTER if self.better else NOT_BETTER}")


def pool(measures: list[Measure]) -> Pool | None:
    """[measures] pooled, each draw summed over them; None when there are none."""
    if not measures:
        return None
    draws = zip(*(measure.series for measure in measures))
    lower, upper = bounds([sum(net for _, net in draw) / sum(rows for rows, _ in draw)
                           for draw in draws])
    return Pool(len(measures), sum(len(measure.fixed) for measure in measures),
                sum(len(measure.lost) for measure in measures),
                sum(measure.rows for measure in measures), lower, upper)


def family_of(name: str) -> str:
    """A rows file's family: its directory and the first part of its name, `native/autocorrect`
    for `native/autocorrect_twins_ro.tsv`."""
    path = Path(name)
    first = path.stem.split("_")[0]
    return first if path.parent == Path(".") else f"{path.parent.as_posix()}/{first}"


def pool_small(files: dict[str, tuple[Rows, Rows]], measures: list[Measure], budget: float,
               margin: float) -> list[Measure]:
    """Each family's secondary measures too small to judge alone, judged together as one corpus,
    every key and sentence kept apart by its file; a family of fewer than FEWEST_POOLED_FILES
    such files is left reported."""
    groups: dict[tuple[str, str], list[Measure]] = {}
    for measure in measures:
        if measure.secondary and len(measure.fixed) + len(measure.lost) < FEWEST_CHANGED_ROWS:
            groups.setdefault((family_of(measure.file), measure.name), []).append(measure)
    pooled = []
    for (family, measure_name), group in sorted(groups.items()):
        if len(group) < FEWEST_POOLED_FILES:
            continue
        sides: tuple[list[Row], list[Row]] = ([], [])
        for measure in group:
            for side, rows in zip(sides, files[measure.file]):
                index = rows.measures.index(measure_name)
                side.extend(Row((row.verdicts[index],), f"{measure.file}:{row.key}",
                                None if row.sentence is None else f"{measure.file}:{row.sentence}",
                                row.genre, row.reading) for row in rows.rows)
        for measure in judge(family, Rows((measure_name,), sides[0]), Rows((measure_name,), sides[1]),
                             budget, margin):
            measure.text = measure.text.replace(
                f"{family} {measure_name}:", f"{family} {measure_name}, pooled over {len(group)} files:", 1)
            pooled.append(measure)
    return pooled


def files_under(directory: Path) -> set[str]:
    return {path.relative_to(directory).as_posix() for path in directory.rglob("*") if path.is_file()}


@dataclass
class Verdict:
    status: int
    measures: list[Measure] = field(default_factory=list)
    pooled: Pool | None = None
    # Why the two runs cannot be compared, when they cannot.
    reason: str = ""
    not_judged: list[str] = field(default_factory=list)


def verdict(before: Path, after: Path, budget: str = DEFAULT_BUDGET,
            margin: float = MARGIN_POINTS) -> Verdict:
    """The verdict on the readings in [after] against those in [before]."""
    before_names = files_under(before)
    after_names = files_under(after)
    found = Verdict(NO_FALL)
    judged: dict[str, tuple[Rows, Rows]] = {}
    try:
        for name in sorted(after_names - before_names):
            if name.endswith(ROWS_SUFFIX) or read_rows(after / name) is not None:
                raise NotComparable(f"{name}: judged after, missing before")
        for name in sorted(before_names):
            was = read_rows(before / name)
            if name not in after_names:
                if was is not None or name.endswith(ROWS_SUFFIX):
                    raise NotComparable(f"{name}: judged before, missing after")
                continue
            now = read_rows(after / name)
            if (was is None) != (now is None):
                raise NotComparable(f"{name}: a measures header on one side only")
            if was is None:
                if name.endswith(ROWS_SUFFIX):
                    raise NotComparable(f"{name}: a rows file without its measures line")
                found.not_judged.append(name)
                continue
            judged[name] = (was, now)
            found.measures.extend(judge(name, was, now, CHURN_BUDGETS[budget], margin))
        found.measures.extend(pool_small(judged, found.measures, CHURN_BUDGETS[budget], margin))
    except NotComparable as reason:
        return Verdict(NOT_COMPARABLE, reason=str(reason))
    judge_secondary(found.measures)
    judge_genres(found.measures)
    found.pooled = pool([measure for measure in found.measures if measure.series])
    found.status = FALL if any(measure.falls for measure in found.measures) else NO_FALL
    return found


def compare(before: Path, after: Path, out, budget: str = DEFAULT_BUDGET,
            margin: float = MARGIN_POINTS) -> int:
    """Writes the verdict on [after] against [before] to [out], each measure with its fixed and
    lost rows; returns the exit status."""
    found = verdict(before, after, budget, margin)
    if found.status == NOT_COMPARABLE:
        out.write(f"not comparable: {found.reason}\n")
        return NOT_COMPARABLE
    write(found.measures, out)
    if found.pooled is not None:
        out.write(found.pooled.line() + "\n")
    if found.not_judged:
        out.write(f"not judged: {' '.join(found.not_judged)}\n")
    return found.status


def selftest() -> None:
    class Uniform:
        """A random source with no binomialvariate, as before Python 3.12."""

        def __init__(self) -> None:
            self.source = Random(BOOTSTRAP_SEED)

        def random(self) -> float:
            return self.source.random()

    assert binomial(Uniform(), 50, 0.0) == 0, "a draw at chance 0 hits none"
    assert binomial(Uniform(), 50, 1.0) == 50, "a draw at chance 1 hits every trial"
    assert 0 < binomial(Uniform(), 400, 0.5) < 400, "a draw at chance one half hits some"
    uniform = Uniform()
    for chance in (0.05, 0.5, 0.9):
        mean = sum(binomial(uniform, 200, chance) for _ in range(400)) / 400
        assert abs(mean - 200 * chance) < 3, f"draws at chance {chance} average {mean}"

    def run(before_files: dict[str, str], after_files: dict[str, str],
            budget: str = DEFAULT_BUDGET, margin: float = MARGIN_POINTS) -> tuple[int, str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for side, files in (("before", before_files), ("after", after_files)):
                for name, text in files.items():
                    path = root / side / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(text, encoding="utf-8")
                (root / side).mkdir(exist_ok=True)
            out = io.StringIO()
            status = compare(root / "before", root / "after", out, budget=budget, margin=margin)
            return status, out.getvalue()

    def rows(measures: str, verdicts: list[str]) -> str:
        return f"{HEADER} {measures}\n" + "".join(
            f"{verdict}\tw{index}\treading {verdict}\n" for index, verdict in enumerate(verdicts))

    same = rows("right", ["1", "0", "1"])
    status, report = run({"a.tsv": same}, {"a.tsv": same})
    assert status == NO_FALL and "fixed 0, lost 0" in report, report

    four_one_before = rows("right", ["0", "0", "0", "0", "1"])
    four_one_after = rows("right", ["1", "1", "1", "1", "0"])
    status, report = run({"a.tsv": four_one_before}, {"a.tsv": four_one_after})
    assert status == NO_FALL and "fixed 4, lost 1" in report and "FALL" not in report, report
    assert "lost   w4" in report and "fixed  w0" in report, report

    three_one_after = rows("right", ["1", "1", "1", "0", "0"])
    status, report = run({"a.tsv": four_one_before}, {"a.tsv": three_one_after})
    assert status == NO_FALL and "fixed 3, lost 1" in report and SMALL in report, report

    two_before = f"{HEADER} first top-three\n1\t1\tw0\tx\n0\t0\tw1\tx\n"
    two_after = f"{HEADER} first top-three\n0\t1\tw0\tx\n0\t1\tw1\tx\n"
    status, report = run({"a.tsv": two_before}, {"a.tsv": two_after})
    assert status == NO_FALL, report
    assert f"a.tsv first: fixed 0, lost 1" in report and SMALL in report, report

    # The verdict's worked cases, as distinct (previous word, target) pairs among unchanged rows:
    # one lost row alone is reported; 1,800 / 454 sits on the 4 : 1 line by chance; 121 / 109
    # loses more than either budget allows; 545 / 175 is better beyond doubt and over a 4 : 1
    # budget only.
    def context(fixed: int, lost: int, unchanged: int) -> tuple[str, str]:
        verdicts = [("0", "1")] * fixed + [("1", "0")] * lost + [("1", "1")] * unchanged
        return tuple(f"{HEADER} {' '.join(CONTEXT_MEASURES)}\n" + "".join(
            f"0\t{pair[side]}\t0\t0\tp{index} |w{index}\t-\t-\n"
            for index, pair in enumerate(verdicts)) for side in (0, 1))

    before, after = context(0, 1, 2000)
    status, report = run({"context/en.tsv": before}, {"context/en.tsv": after})
    assert status == NO_FALL and "context/en.tsv top-three: fixed 0, lost 1" in report, report
    assert SMALL in report, report

    before, after = context(1800, 454, 60000)
    status, report = run({"context/en.tsv": before}, {"context/en.tsv": after})
    assert status == NO_FALL and "lost share 0.201" in report and CHURN_FALL not in report, report

    before, after = context(121, 109, 20000)
    status, report = run({"context/en.tsv": before}, {"context/en.tsv": after})
    assert status == FALL and CHURN_FALL in report, report
    status, report = run({"context/en.tsv": before}, {"context/en.tsv": after}, budget="ranker")
    assert status == FALL and CHURN_FALL in report, report

    before, after = context(545, 175, 60000)
    status, report = run({"context/en.tsv": before}, {"context/en.tsv": after})
    assert status == FALL and CHURN_FALL in report and "lost share 0.243" in report, report
    status, report = run({"context/en.tsv": before}, {"context/en.tsv": after}, budget="ranker")
    assert status == NO_FALL and CHURN_FALL not in report, report
    assert f"no fall, {BETTER}" in report and f"{POOLED} over 1 context file" in report, report

    # Pooled over the languages, the change is better beyond chance or not shown better; neither
    # is a fall. One lost German row beside English's 545 / 175 keeps the pool better; English's
    # 55 / 45 alone does not show it.
    de_before, de_after = context(0, 1, 20000)
    status, report = run({"context/en.tsv": before, "context/de.tsv": de_before},
                         {"context/en.tsv": after, "context/de.tsv": de_after}, budget="ranker")
    assert status == NO_FALL and f"{POOLED} over 2 context files" in report, report
    assert report.split(POOLED)[1].rstrip().endswith(BETTER), report
    before, after = context(55, 45, 60000)
    status, report = run({"context/en.tsv": before}, {"context/en.tsv": after}, budget="ranker")
    assert report.split(POOLED)[1].rstrip().endswith(NOT_BETTER) and BETTER not in report, report

    # Churn counts pairs, not rows: 30 lost pairs against 20 fixed ones, each fixed pair asked
    # at four depths and twice over, is 30 / 190 in rows and 30 / 50 in pairs.
    lost_rows = [("1", "0", f"p{index} |w{index}") for index in range(30)]
    fixed_rows = [("0", "1", f"q{index} {'wv'[:depth]}|wv{index}")
                  for index in range(20) for depth in range(4) for _ in range(2)]
    side = lambda at: f"{HEADER} {' '.join(CONTEXT_MEASURES)}\n" + "".join(
        f"0\t{row[at]}\t0\t0\t{row[2]}\t-\t-\n" for row in lost_rows + fixed_rows)
    status, report = run({"context/en.tsv": side(0)}, {"context/en.tsv": side(1)})
    assert status == FALL and "pairs fixed 20, lost 30" in report and CHURN_FALL in report, report

    # The net test resamples whole sentences. Three sentences of 20 rows turn right and three
    # turn wrong (all one pair, so the churn passes): net 0 in 20,000 rows. As six sentences the
    # change is too uncertain to keep within the margin; as 120 rows of their own it is not.
    def sentences(fields: bool, renamed: int = -1) -> tuple[str, str]:
        texts = []
        for at in (0, 1):
            lines = [f"{HEADER} {' '.join(CONTEXT_MEASURES)}\n"]
            if fields:
                lines.append(f"{FIELDS} sentence genre\n")
            for sentence in range(1000):
                for word in range(20):
                    index = sentence * 20 + word
                    if sentence < 3:
                        verdicts, key = ("0", "1"), f"f{index} |x{index}"
                    elif sentence < 6:
                        verdicts, key = ("1", "0"), "p |w"
                    else:
                        verdicts, key = ("1", "1"), f"u{index} |y{index}"
                    named = f"s{sentence}" if at == 1 or sentence != renamed else "other"
                    middle = f"\t{named}\tweb" if fields else ""
                    lines.append(f"0\t{verdicts[at]}\t0\t0\t{key}{middle}\t-\t-\n")
            texts.append("".join(lines))
        return tuple(texts)

    with_fields, without_fields = sentences(True), sentences(False)
    status, report = run({"context/en.tsv": with_fields[0]}, {"context/en.tsv": with_fields[1]})
    assert status == FALL and WORSE_FALL in report and CHURN_FALL not in report, report
    assert "net +0 rows (+0.00 pt" in report, report
    status, report = run({"context/en.tsv": without_fields[0]},
                         {"context/en.tsv": without_fields[1]})
    assert status == NO_FALL and WORSE_FALL not in report, report
    status, report = run({"context/en.tsv": without_fields[0]}, {"context/en.tsv": with_fields[1]})
    assert status == FALL and WORSE_FALL in report, report
    status, report = run({"context/en.tsv": with_fields[0]}, {"context/en.tsv": with_fields[1]},
                         margin=1.0)
    assert status == NO_FALL and WORSE_FALL not in report, report
    renamed = sentences(True, renamed=500)
    status, report = run({"context/en.tsv": renamed[0]}, {"context/en.tsv": renamed[1]})
    assert status == NOT_COMPARABLE and "sentence" in report, report

    # On context rows only the top three is judged; first place, with and without the words
    # before, is reported beside it.
    first_lost = [f"{HEADER} {' '.join(CONTEXT_MEASURES)}\n" + "".join(
        f"{1 - at}\t1\t1\t1\tp{index} |w{index}\t-\t-\n" for index in range(5000)) for at in (0, 1)]
    status, report = run({"context/en.tsv": first_lost[0]}, {"context/en.tsv": first_lost[1]})
    assert status == NO_FALL and "context/en.tsv first: fixed 0, lost 5000" in report, report
    assert BESIDE in report and WORSE_FALL not in report, report

    # A secondary corpus falls only beyond chance, after Holm across them all. One that loses 29
    # pairs for 71 is over the 4 : 1 budget alone (p about 0.012); three such corpora are not.
    def secondary(fixed: int, lost: int, unchanged: int, lost_pairs: int = 0) -> list[str]:
        keys = ([("0", "1", f"f{index}") for index in range(fixed)]
                + [("1", "0", f"l{index % lost_pairs if lost_pairs else index}")
                   for index in range(lost)]
                + [("1", "1", f"u{index}") for index in range(unchanged)])
        return [f"{HEADER} right\n" + "".join(f"{key[at]}\t{key[2]}\t-\n" for key in keys)
                for at in (0, 1)]

    churny = secondary(71, 29, 900)
    status, report = run({"native/a.tsv": churny[0]}, {"native/a.tsv": churny[1]})
    assert status == FALL and CHURN_FALL in report, report
    three = {f"native/{name}.tsv": churny for name in "abc"}
    status, report = run({n: t[0] for n, t in three.items()}, {n: t[1] for n, t in three.items()})
    assert status == NO_FALL and CHURN_FALL not in report and "after Holm" in report, report
    # Holm steps down: with one corpus far over budget (33 lost of 100), the second needs only
    # 0.025 itself (28 of 100, about 0.023), where α / 2 for both would clear it.
    steps = {"native/a.tsv": secondary(67, 33, 900), "native/b.tsv": secondary(72, 28, 900)}
    status, report = run({n: t[0] for n, t in steps.items()}, {n: t[1] for n, t in steps.items()})
    assert status == FALL and report.count(f"{CHURN_FALL} beyond chance") == 2, report

    # Net 0 in 200 rows is no fall, however wide its interval; 60 rows lost against 20 fixed in
    # 1,000 is worse beyond chance, with its losses all one pair so that the churn passes.
    status, report = run(*({"kotlin/a.tsv": text} for text in secondary(20, 20, 160, lost_pairs=1)))
    assert status == NO_FALL and WORSE_FALL not in report, report
    status, report = run(*({"kotlin/a.tsv": text} for text in secondary(20, 60, 920, lost_pairs=1)))
    assert status == FALL and WORSE_FALL in report and CHURN_FALL not in report, report

    # Context rows are split by genre when they carry two or more. News gains 300 rows and
    # reviews lose 60: better overall, yet reviews are negative beyond chance and block. Ten
    # lost reviews rows are reported, not judged; 40 lost against 30 fixed are within chance; one
    # genre alone is the file's own net test.
    def genres(news_fixed: int, reviews_lost: int, single: bool = False,
               reviews_fixed: int = 0) -> list[str]:
        texts = []
        for at in (0, 1):
            lines = [f"{HEADER} {' '.join(CONTEXT_MEASURES)}\n{FIELDS} sentence genre\n"]
            for genre, sentences, changed, verdicts in (("news", 2000, news_fixed, ("0", "1")),
                                                        ("reviews", 500, reviews_lost, ("1", "0"))):
                for sentence in range(sentences):
                    for word in range(10):
                        index = sentence * 10 + word
                        value = verdicts[at] if word == 0 and sentence < changed else "1"
                        if genre == "reviews" and word == 0 and \
                                changed <= sentence < changed + reviews_fixed:
                            value = "01"[at]
                        named = "web" if single else genre
                        lines.append(f"0\t{value}\t0\t0\t{genre}{index} |w{genre}{index}\t"
                                     f"{genre}{sentence}\t{named}\t-\t-\n")
            texts.append("".join(lines))
        return texts

    status, report = run(*({"context/en.tsv": text} for text in genres(300, 60)))
    assert status == FALL and WORSE_FALL not in report and CHURN_FALL not in report, report
    assert f"genre reviews {JUDGED_MEASURE}: fixed 0, lost 60" in report, report
    assert GENRE_FALL in report and "genre news" in report, report
    status, report = run(*({"context/en.tsv": text} for text in genres(300, 10)))
    assert status == NO_FALL and GENRE_FALL not in report and SMALL in report, report
    status, report = run(*({"context/en.tsv": text} for text in genres(300, 40, reviews_fixed=30)))
    assert status == NO_FALL and GENRE_FALL not in report and "no fall after Holm" in report, report
    status, report = run(*({"context/en.tsv": text} for text in genres(300, 60, single=True)))
    assert status == NO_FALL and "genre " not in report, report

    status, report = run({"a.tsv": rows("right", ["1", "1"])}, {"a.tsv": rows("right", ["1"])})
    assert status == NOT_COMPARABLE and "rows" in report, report

    status, report = run({"a.tsv": rows("right", ["1"])}, {})
    assert status == NOT_COMPARABLE and "missing after" in report, report

    status, report = run({"a.tsv": rows("right", ["1"])}, {"a.tsv": "1\tw0\treading\n"})
    assert status == NOT_COMPARABLE and "header" in report, report

    status, report = run({"a.tsv": rows("right", ["1"])}, {"a.tsv": rows("right", ["right"])})
    assert status == NOT_COMPARABLE and "verdicts" in report, report

    status, report = run({"a.tsv": rows("right", ["1"])}, {"a.tsv": rows("first", ["1"])})
    assert status == NOT_COMPARABLE and "measures" in report, report

    status, report = run({"a.tsv": rows("right", ["1"])}, {"a.tsv": rows("right", ["1"]).replace("w0", "v0")})
    assert status == NOT_COMPARABLE and "key" in report, report

    status, report = run({"a.tsv": same}, {"a.tsv": same, "sub/b.tsv": rows("right", ["0"])})
    assert status == NOT_COMPARABLE and "missing before" in report, report

    status, report = run({"a.tsv": "1\tw0\treading\n"}, {"a.tsv": "1\tw0\treading\n"})
    assert status == NOT_COMPARABLE and "measures line" in report, report

    # Small corpora of one family are judged together: three autocorrect files losing 12 rows
    # each, none large enough alone, fall as one; three gaining 10 rows for 2 do not; a family of
    # one file stays reported.
    def small(fixed: int, lost: int, unchanged: int) -> tuple[str, str]:
        verdicts = [("0", "1")] * fixed + [("1", "0")] * lost + [("1", "1")] * unchanged
        return tuple(rows("right", [pair[side] for pair in verdicts]) for side in (0, 1))

    def family_run(fixed: int, lost: int, files: int) -> tuple[int, str]:
        made = {f"native/autocorrect_{index}_en.tsv": small(fixed, lost, 88) for index in range(files)}
        return run({name: sides[0] for name, sides in made.items()},
                   {name: sides[1] for name, sides in made.items()})

    status, report = family_run(0, 12, 3)
    assert status == FALL and "native/autocorrect right, pooled over 3 files: fixed 0, lost 36" in report, report
    assert "pairs fixed 0, lost 36" in report, report
    status, report = family_run(10, 2, 3)
    assert status == NO_FALL and "pooled over 3 files: fixed 30, lost 6" in report, report
    assert "FALL" not in report, report
    status, report = family_run(0, 12, 1)
    assert status == NO_FALL and "pooled" not in report and SMALL in report, report

    status, report = run({"a.tsv": same, "SUMMARY.txt": "x\n"}, {"a.tsv": same, "SUMMARY.txt": "y\n"})
    assert status == NO_FALL and "not judged: SUMMARY.txt" in report, report

    print("selftest ok: fixed and lost rows per measure; the net test by sentence, the churn by "
          "pair, Holm, the genres and the pool; and every way two runs cannot be compared")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("before", nargs="?", type=Path)
    parser.add_argument("after", nargs="?", type=Path)
    parser.add_argument("--budget", choices=sorted(CHURN_BUDGETS), default=DEFAULT_BUDGET,
                        help="the churn budget by the change's kind")
    parser.add_argument("--margin", type=float, default=MARGIN_POINTS,
                        help="the net test's margin, in points of each file's rows")
    parser.add_argument("--selftest", action="store_true", help="prove the comparison on synthetic rows")
    arguments = parser.parse_args()
    if arguments.selftest:
        selftest()
        return NO_FALL
    if arguments.before is None or arguments.after is None:
        parser.error("two readings directories are required unless --selftest is given")
    return compare(arguments.before, arguments.after, sys.stdout, budget=arguments.budget,
                   margin=arguments.margin)


if __name__ == "__main__":
    sys.exit(main())
