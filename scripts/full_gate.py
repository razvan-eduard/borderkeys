#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Runs the verify job of .github/workflows/ci.yml on this machine, step by step as CI runs it,
then lists every step and every JVM test that failed: the full gate a change passes before it is
chosen.

Each step's `run:` goes to bash with -eo pipefail from the repository root, with the job's and
the step's env, CI set (the pipeline harness and the tap corpus fail rather than skip without
their inputs), GITHUB_ENV and GITHUB_STEP_SUMMARY pointed at files in the out dir, and every
`./gradlew` call made `--offline`. A step with no `run:` (an action) is passed over, and so is
each step HOST_LIMITED names, with its reason. Each step's log is `<out>/NN.log`; the JVM tests
listed are those whose result files the run wrote.

    python3 scripts/full_gate.py <out dir> [--from STEP] [--to STEP]
    python3 scripts/full_gate.py --list
    python3 scripts/full_gate.py --selftest
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
JOB = "verify"

# Steps a developer's machine cannot run, with the reason.
HOST_LIMITED = {
    "Install NDK and CMake": "CI provisioning; the local SDK carries its own",
    "Language-pack loader under libFuzzer": "Apple clang ships no libFuzzer",
}

# Where Gradle writes the JUnit results of every module.
RESULTS_GLOB = "*/build/test-results/**/*.xml"

GRADLEW = re.compile(r"\./gradlew\b")


@dataclass
class Step:
    name: str
    run: str | None = None
    env: dict[str, str] = field(default_factory=dict)


def indent_of(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def value_of(text: str) -> str:
    """A YAML scalar as written on one line: unquoted, quotes taken off."""
    text = text.strip()
    if len(text) >= 2 and text[0] == text[-1] and text[0] in "'\"":
        return text[1:-1]
    return text


def parse_job(text: str, job: str) -> tuple[dict[str, str], list[Step]]:
    """The job's env and its steps, read from a workflow written as this repository writes it:
    jobs at two spaces, a job's keys at four, steps as `- name:` at six, a step's keys at eight,
    a `run: |` block deeper than its key."""
    lines = text.split("\n")
    start = next(index for index, line in enumerate(lines) if line == f"  {job}:")
    end = next((index for index in range(start + 1, len(lines))
                if indent_of(lines[index]) == 2 and lines[index].strip() and not lines[index].lstrip().startswith("#")),
               len(lines))
    env: dict[str, str] = {}
    steps: list[Step] = []
    index = start + 1
    while index < end:
        line = lines[index]
        stripped = line.strip()
        if indent_of(line) == 4 and stripped == "env:":
            index += 1
            while index < end and (not lines[index].strip() or indent_of(lines[index]) > 4):
                entry = lines[index].strip()
                if entry and not entry.startswith("#") and ":" in entry:
                    key, value = entry.split(":", 1)
                    env[key.strip()] = value_of(value)
                index += 1
            continue
        if indent_of(line) == 6 and stripped.startswith("- name:"):
            steps.append(Step(value_of(stripped[len("- name:"):])))
        elif steps and indent_of(line) == 8 and stripped.startswith("run:"):
            rest = stripped[len("run:"):].strip()
            if rest in ("|", "|-", ">"):
                body: list[str] = []
                index += 1
                while index < end and (not lines[index].strip() or indent_of(lines[index]) > 8):
                    body.append(lines[index])
                    index += 1
                depth = min(indent_of(part) for part in body if part.strip())
                steps[-1].run = "\n".join(part[depth:] for part in body).strip("\n") + "\n"
                continue
            steps[-1].run = value_of(rest) + "\n"
        elif steps and indent_of(line) == 8 and stripped == "env:":
            index += 1
            while index < end and (not lines[index].strip() or indent_of(lines[index]) > 8):
                entry = lines[index].strip()
                if entry and not entry.startswith("#") and ":" in entry:
                    key, value = entry.split(":", 1)
                    steps[-1].env[key.strip()] = value_of(value)
                index += 1
            continue
        index += 1
    return env, steps


def offline(run: str) -> str:
    return GRADLEW.sub("./gradlew --offline", run)


def failed_tests(root: Path, since: float) -> list[str]:
    """Every JVM test a result file written after [since] records as failed or in error."""
    failed = []
    for path in sorted(root.glob(RESULTS_GLOB)):
        if path.stat().st_mtime < since:
            continue
        for case in ElementTree.parse(path).getroot().iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                module = path.relative_to(root).parts[0]
                failed.append(f"{module}: {case.get('classname', '').rsplit('.', 1)[-1]} > {case.get('name')}")
    return failed


def gate(out: Path, first: str | None, last: str | None) -> int:
    env, steps = parse_job(WORKFLOW.read_text(encoding="utf-8"), JOB)
    names = [step.name for step in steps]
    chosen = steps[names.index(first) if first else 0:(names.index(last) + 1) if last else len(steps)]
    out.mkdir(parents=True, exist_ok=True)
    base = {**os.environ, **env, "CI": "true", "GITHUB_ENV": str(out / "github-env"),
            "GITHUB_STEP_SUMMARY": str(out / "step-summary.md")}
    started = time.time()
    failed_steps = []
    for number, step in enumerate(chosen):
        if step.run is None:
            continue
        if step.name in HOST_LIMITED:
            print(f"passed over: {step.name} ({HOST_LIMITED[step.name]})", flush=True)
            continue
        log = out / f"{number:02d}.log"
        step_env = {**base, **step.env}
        with log.open("w", encoding="utf-8") as handle:
            code = subprocess.run(["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c", offline(step.run)],
                                  cwd=ROOT, env=step_env, stdout=handle, stderr=subprocess.STDOUT).returncode
        print(f"{'ok' if code == 0 else f'FAILED ({code})'}: {step.name}", flush=True)
        if code != 0:
            failed_steps.append(f"{step.name}  [{log}]")
    tests = failed_tests(ROOT, started)
    print(f"\n{len(failed_steps)} steps failed" + "".join(f"\n  {step}" for step in failed_steps))
    print(f"{len(tests)} JVM tests failed" + "".join(f"\n  {test}" for test in tests))
    return 1 if failed_steps or tests else 0


def selftest() -> int:
    workflow = """name: x
jobs:
  first:
    steps:
      - name: Elsewhere
        run: echo elsewhere
  verify:
    runs-on: ubuntu-latest

    env:
      # a comment
      HEAP: -Xmx6g
    steps:
      - name: Checkout
        uses: actions/checkout@v7

      - name: Block
        run: |
          set -euo pipefail
          ./gradlew test --no-daemon

            indented
      - name: "Single"
        env:
          ONE: '1'
        run: python3 tools/a.py --selftest
  smoke:
    steps:
      - name: After
        run: echo after
"""
    env, steps = parse_job(workflow, "verify")
    assert env == {"HEAP": "-Xmx6g"}, env
    assert [step.name for step in steps] == ["Checkout", "Block", "Single"], steps
    assert steps[0].run is None, steps[0]
    assert steps[1].run == "set -euo pipefail\n./gradlew test --no-daemon\n\n  indented\n", repr(steps[1].run)
    assert steps[2].run == "python3 tools/a.py --selftest\n" and steps[2].env == {"ONE": "1"}, steps[2]
    assert offline(steps[1].run).count("./gradlew --offline test") == 1, offline(steps[1].run)

    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        results = root / "keyboard" / "build" / "test-results" / "testDebugUnitTest"
        results.mkdir(parents=True)
        (results / "TEST-a.xml").write_text(
            '<testsuite><testcase classname="com.x.PipelineTest" name="every case"><failure message="m"/>'
            '</testcase><testcase classname="com.x.Ok" name="fine"/>'
            '<testcase classname="com.x.Broken" name="throws"><error message="e"/></testcase></testsuite>',
            encoding="utf-8")
        found = failed_tests(root, 0.0)
        assert found == ["keyboard: PipelineTest > every case", "keyboard: Broken > throws"], found
        assert failed_tests(root, time.time() + 60) == [], "stale results counted"
    print("full_gate selftest: ok")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("out", nargs="?", type=Path)
    parser.add_argument("--from", dest="first", help="the first step, by name")
    parser.add_argument("--to", dest="last", help="the last step, by name")
    parser.add_argument("--list", action="store_true", help="list the job's steps")
    parser.add_argument("--selftest", action="store_true")
    arguments = parser.parse_args()
    if arguments.selftest:
        return selftest()
    if arguments.list:
        _, steps = parse_job(WORKFLOW.read_text(encoding="utf-8"), JOB)
        for step in steps:
            kind = "action" if step.run is None else HOST_LIMITED.get(step.name, "run")
            print(f"{step.name}  [{kind}]")
        return 0
    if arguments.out is None:
        parser.error("an out dir is required")
    return gate(arguments.out, arguments.first, arguments.last)


if __name__ == "__main__":
    sys.exit(main())
