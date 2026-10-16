#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Checks that every Kotlin file's imports are in ktlint's order and all used.

ktlint's order: every import that is not of `java.`, `javax.` or `kotlin.` first, then those
three groups in that order, then the aliased imports, each group in codepoint order of the full
name. An import is unused when its last name, or its alias, appears nowhere else in the file; a
`*` import is never judged unused, the delegate names `getValue`, `setValue` and
`provideDelegate` count as used when the file delegates with `by`, and an operator-convention
name (`plus`, `times`, `get`, `invoke` and the rest) counts as used, since its use is a symbol.
The files checked are every `.kt` and `.kts` file the repository tracks, or the files given.
Exit status is non-zero when any file disagrees, each named with what disagrees. Runs in CI
beside the other self-tests.

    python3 tools/check_imports.py
    python3 tools/check_imports.py keyboard/src/main/java/com/borderkeys/ime/Composer.kt
    python3 tools/check_imports.py --selftest
"""

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DELEGATES = {"getValue", "setValue", "provideDelegate"}
OPERATORS = {
    "plus", "minus", "times", "div", "rem", "get", "set", "invoke", "contains", "iterator",
    "compareTo", "rangeTo", "rangeUntil", "unaryMinus", "unaryPlus", "not", "inc", "dec",
    "plusAssign", "minusAssign", "timesAssign", "divAssign", "remAssign",
    "component1", "component2", "component3", "component4", "component5",
}
GROUPS = ("java.", "javax.", "kotlin.")
ALIAS_GROUP = len(GROUPS) + 1
IMPORT = re.compile(r"^import ([\w.]+)(?: as (\w+))?$", re.M)


def layout_key(name: str, alias: str = "") -> tuple:
    if alias:
        return (ALIAS_GROUP, name)
    for rank, prefix in enumerate(GROUPS, start=1):
        if name.startswith(prefix):
            return (rank, name)
    return (0, name)


def findings(text: str) -> list:
    imports = IMPORT.findall(text)
    keys = [layout_key(full, alias) for full, alias in imports]
    found = []
    if keys != sorted(keys):
        found.append("imports out of order")
    body = re.sub(r"^import .*$", "", text, flags=re.M)
    for full, alias in imports:
        name = alias or full.rsplit(".", 1)[-1]
        if name == "*" or name in OPERATORS:
            continue
        if name in DELEGATES and re.search(r"\bby\b", body):
            continue
        if not re.search(r"\b" + re.escape(name) + r"\b", body):
            found.append(f"unused import {full}")
    return found


def kotlin_files() -> list:
    listing = subprocess.run(
        ["git", "-C", str(ROOT), "ls-files", "-z", "--", "*.kt", "*.kts"],
        capture_output=True, text=True, check=True,
    )
    return [ROOT / name for name in listing.stdout.split("\0") if name]


def selftest() -> int:
    out_of_order = ["imports out of order"]
    cases = [
        ("ordered", "import com.a.B\nimport org.c.D\nimport java.io.File\nimport javax.inject.Inject\n"
                    "import kotlin.math.ln\nimport z.Z as Zed\n\nval x = B(D(File(Inject(ln(Zed)))))\n", []),
        ("kotlinx before java", "import kotlinx.coroutines.flow.Flow\nimport java.io.File\n\nval x = Flow(File)\n", []),
        ("java before com", "import java.io.File\nimport com.a.B\n\nval x = B(File)\n", out_of_order),
        ("javax before java", "import javax.inject.Inject\nimport java.io.File\n\nval x = Inject(File)\n",
         out_of_order),
        ("kotlin before javax", "import kotlin.math.ln\nimport javax.inject.Inject\n\nval x = ln(Inject)\n",
         out_of_order),
        ("within the first group", "import com.b.X\nimport com.a.Y\n\nval x = X(Y)\n", out_of_order),
        ("an alias before a plain import", "import com.a.B as Bee\nimport com.z.Z\n\nval x = Bee(Z)\n",
         out_of_order),
        ("unused", "import com.a.B\nimport com.a.C\n\nval x = B\n", ["unused import com.a.C"]),
        ("unused, a prefix of a used name", "import com.a.Json\nimport com.a.JsonObject\n\nval x = JsonObject\n",
         ["unused import com.a.Json"]),
        ("unused, a used name in another case",
         "import com.a.JsonPrimitive\nimport com.a.jsonPrimitive\n\nval x = JsonPrimitive\n",
         ["unused import com.a.jsonPrimitive"]),
        ("alias used by its alias", "import com.a.B as Bee\n\nval x = Bee\n", []),
        ("delegate with by", "import androidx.compose.runtime.getValue\n\nval x by lazy { 1 }\n", []),
        ("delegate without by", "import androidx.compose.runtime.getValue\n\nval x = byte\n",
         ["unused import androidx.compose.runtime.getValue"]),
        ("operator used as a symbol", "import androidx.compose.ui.unit.times\n\nval w = 2 * 8\n", []),
        ("star", "import com.a.*\n\nval x = 1\n", []),
    ]
    for label, text, expected in cases:
        got = findings(text)
        if got != expected:
            print(f"check_imports selftest: {label}: expected {expected}, got {got}")
            return 1
    print("check_imports selftest: ok")
    return 0


def main() -> int:
    arguments = sys.argv[1:]
    if "--help" in arguments:
        print(__doc__.strip())
        return 0
    if "--selftest" in arguments:
        return selftest()
    paths = [Path(arg) for arg in arguments] or kotlin_files()
    disagreed = 0
    for path in paths:
        shown = path.relative_to(ROOT) if path.is_absolute() else path
        for what in findings(path.read_text(encoding="utf-8")):
            print(f"{shown}: {what}")
            disagreed += 1
    if disagreed:
        return 1
    print(f"check_imports: {len(paths)} files, imports in order and used")
    return 0


if __name__ == "__main__":
    sys.exit(main())
