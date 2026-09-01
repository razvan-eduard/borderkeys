#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Second pass: the text the call-site pass could not see -- `when` branches, `append`, `?:`.

Works literal by literal rather than by call site, so it needs a list of the strings that are
identifiers rather than words. That list is the whole point of the file: everything not on it is
text a person reads, which is the assumption that keeps a new string from quietly staying English.
"""

import json
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from extract_strings import STRING, read_expression, split_interpolation, unescape, make_key

# Identifiers, MIME types, file names and flavour names: not words, must not be translated.
TECHNICAL = {
    "qwerty_ro", "text/csv", "text/*", "*/*", "model.gguf", "%.2f", "plus", "core",
    "imported.bkd", "borderkeys-dictionary.csv", "unknown",
    "android.settings.INPUT_METHOD_SUBTYPE_SETTINGS",
}


def process(path, prefix, catalogue):
    text = path.read_text(encoding="utf-8")
    hits = [m.start() for m in STRING.finditer(text)]
    changed = 0
    for start in reversed(hits):
        # Skip anything inside a comment line.
        line_start = text.rfind("\n", 0, start) + 1
        stripped = text[line_start:start].lstrip()
        if stripped.startswith("//") or stripped.startswith("*"):
            continue
        read = read_expression(text, start)
        if not read:
            continue
        end, parts = read
        raw = "".join(parts)
        if raw in TECHNICAL or not re.search(r"[A-Za-z]{2}", raw):
            continue
        # A package path or an intent action is an identifier, not a sentence.
        if raw.count("/") > 1 or raw.count(".") > 2:
            continue
        pattern, args = split_interpolation(raw)
        value = unescape(pattern)
        if value.strip() in TECHNICAL:
            continue
        key = make_key(prefix, value.replace("%s", " "), catalogue)
        catalogue[key] = value
        call = f"strings[Keys.{key.upper()}]" if not args else \
            "strings.getString(Keys.{}, {})".format(key.upper(), ", ".join(args))
        text = text[:start] + call + text[end:]
        changed += 1
    path.write_text(text, encoding="utf-8")
    return changed


def main():
    catalogue = json.load(open("build/extracted.json", encoding="utf-8"))
    before = len(catalogue)
    for arg in sys.argv[1:]:
        path = pathlib.Path(arg)
        prefix = re.sub(r"(Screen|View)?\.kt$", "", path.name)
        prefix = re.sub(r"(?<!^)(?=[A-Z])", "_", prefix).lower()
        print(f"{process(path, prefix, catalogue):4d}  {path}")
    json.dump(catalogue, open("build/extracted.json", "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    print(f"{len(catalogue) - before} new keys, {len(catalogue)} total")


if __name__ == "__main__":
    main()
