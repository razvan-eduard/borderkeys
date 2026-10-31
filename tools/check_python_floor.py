#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Checks that every tool and script runs on the oldest Python the build meets, PYTHON_FLOOR.

A file under `tools/` or `scripts/` is read as PYTHON_FLOOR's grammar, so syntax newer than it is
refused. An annotation that writes a union with `|` is evaluated when its function or class is
defined, which fails before Python 3.10, so a file holding one must open with `from __future__
import annotations`. Each file that breaks either rule is printed with the line; the exit status is
then non-zero. Runs in CI beside the other self-tests.

    python3 tools/check_python_floor.py
    python3 tools/check_python_floor.py --selftest
"""

import ast
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# The oldest Python the build meets: macOS's /usr/bin/python3, the one a Gradle daemon started
# from Android Studio finds on its PATH.
PYTHON_FLOOR = (3, 9)

# The folders whose top-level Python files are checked, under the repository root: the tools the
# build runs and the ones they import, and the scripts that take and judge the readings.
FOLDERS = ("tools", "scripts")


def defers_annotations(tree: ast.Module) -> bool:
    """Whether the module opens with `from __future__ import annotations`."""
    return any(isinstance(node, ast.ImportFrom) and node.module == "__future__"
               and any(alias.name == "annotations" for alias in node.names)
               for node in tree.body)


def annotations_of(tree: ast.Module) -> list[ast.expr]:
    """Every annotation the module evaluates at definition: arguments, returns, annotated names."""
    found = []
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            arguments = node.args
            every = arguments.posonlyargs + arguments.args + arguments.kwonlyargs
            every += [argument for argument in (arguments.vararg, arguments.kwarg) if argument]
            found.extend(argument.annotation for argument in every if argument.annotation)
            if node.returns is not None:
                found.append(node.returns)
        elif isinstance(node, ast.AnnAssign):
            found.append(node.annotation)
    return found


def writes_union(annotation: ast.expr) -> bool:
    return any(isinstance(node, ast.BinOp) and isinstance(node.op, ast.BitOr)
               for node in ast.walk(annotation))


def problems(path: Path) -> list[tuple[int, str]]:
    """Each line of [path] that fails on PYTHON_FLOOR, with what fails there."""
    source = path.read_text(encoding="utf-8")
    try:
        tree = ast.parse(source, filename=str(path), feature_version=PYTHON_FLOOR)
    except SyntaxError as error:
        return [(error.lineno or 0, f"syntax newer than Python {PYTHON_FLOOR[0]}.{PYTHON_FLOOR[1]}")]
    if defers_annotations(tree):
        return []
    lines = sorted({annotation.lineno for annotation in annotations_of(tree) if writes_union(annotation)})
    return [(line, "a union annotation without `from __future__ import annotations`") for line in lines]


def failing(root: Path, folders: tuple = FOLDERS) -> list[tuple[Path, int, str]]:
    """Every problem in the folders' top-level Python files, in file and line order."""
    paths = sorted(path for folder in folders for path in (root / folder).glob("*.py"))
    return [(path, line, what) for path in paths for line, what in problems(path)]


def selftest() -> int:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        (root / "tools").mkdir()
        (root / "scripts").mkdir()
        (root / "tools" / "bare.py").write_text(
            "def f(x: int) -> str | None:\n    return None\n", encoding="utf-8")
        (root / "tools" / "deferred.py").write_text(
            "from __future__ import annotations\n\ndef f(x: int | None) -> str | None:\n"
            "    return None\n", encoding="utf-8")
        (root / "tools" / "field.py").write_text(
            "class A:\n    name: str | None = None\n", encoding="utf-8")
        (root / "tools" / "newer.py").write_text(
            "match 1:\n    case 1:\n        pass\n", encoding="utf-8")
        (root / "tools" / "plain.py").write_text(
            "def f(x: list[int]) -> dict[str, int]:\n    return {}\n", encoding="utf-8")
        (root / "scripts" / "judge.py").write_text(
            "def g(rows: int | None) -> None:\n    pass\n", encoding="utf-8")
        # A syntax error's line is the running Python's to report, so only its file is compared.
        found = [(f"{path.parent.name}/{path.name}", line if "union" in what else None)
                 for path, line, what in failing(root)]
        assert found == [("scripts/judge.py", 1), ("tools/bare.py", 1), ("tools/field.py", 2),
                         ("tools/newer.py", None)], found
    print("check_python_floor selftest: ok")
    return 0


def main() -> int:
    if "--selftest" in sys.argv[1:]:
        return selftest()
    found = failing(ROOT)
    for path, line, what in found:
        print(f"{path.relative_to(ROOT)}:{line}: {what}")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
