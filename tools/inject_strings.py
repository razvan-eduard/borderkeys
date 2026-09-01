#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Gives every composable that looks up text a `strings` to look it up in.

Adds the import and, at the top of each function whose body mentions `strings`, the line that
reads it out of the composition local. Functions that are not composable are left alone and
reported, because those need the manager passed as a parameter -- a decision, not a rewrite.
"""

import pathlib
import re
import sys

FUNCTION = re.compile(r"^((?:private |internal |public )?fun\s+\w+)", re.MULTILINE)


def body_range(text, start):
    """The span between the `{` opening a function at or after `start` and its match.

    Returns None for an expression body (`fun x() = ...`), which has no block to insert into.
    """
    i = text.find("{", start)
    if i < 0:
        return None
    depth, j = 0, i
    while j < len(text):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return i, j
        j += 1
    return i, len(text)


def main():
    manual = []
    for arg in sys.argv[1:]:
        path = pathlib.Path(arg)
        text = path.read_text(encoding="utf-8")
        if "strings[" not in text and "strings.getString" not in text:
            continue
        edits = []
        for m in FUNCTION.finditer(text):
            span = body_range(text, m.start())
            if span is None:
                continue
            open_brace, close_brace = span
            body = text[open_brace:close_brace]
            if "strings[" not in body and "strings.getString" not in body:
                continue
            preceding = text[max(0, m.start() - 400):m.start()]
            if "@Composable" not in preceding.split("\n\n")[-1]:
                manual.append(f"{path}: {m.group(1)}")
                continue
            edits.append(open_brace)
        for open_brace in reversed(edits):
            line_start = text.rfind("\n", 0, open_brace) + 1
            indent = re.match(r"\s*", text[line_start:]).group(0)
            insert = "\n" + indent + "    val strings = LocalStrings.current"
            text = text[:open_brace + 1] + insert + text[open_brace + 1:]
        if "import com.borderkeys.i18n.Keys" not in text:
            text = re.sub(r"^(package [\w.]+\n)", r"\1\nimport com.borderkeys.i18n.Keys\n",
                          text, count=1, flags=re.MULTILINE)
        if path.parent.name == "screen" and "import com.borderkeys.settings.LocalStrings" not in text:
            text = text.replace("import com.borderkeys.i18n.Keys",
                                "import com.borderkeys.i18n.Keys\nimport com.borderkeys.settings.LocalStrings", 1)
        path.write_text(text, encoding="utf-8")
        print(f"{len(edits):4d}  {path}")
    if manual:
        print("\nnot composable -- pass the manager in by hand:")
        for line in manual:
            print("  " + line)


if __name__ == "__main__":
    main()
