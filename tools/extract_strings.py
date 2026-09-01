#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Lift user-facing text out of Kotlin sources into the translation catalogue.

Run once per batch of screens, then read the diff: this is a mechanical aid, not an authority.
It rewrites the call sites it recognises and leaves everything else alone, so a literal it does
not understand stays in the source where the no-hardcoded-text test will find it.
"""

import json
import pathlib
import re
import sys

# Call sites whose first positional argument is text a person reads.
POSITIONAL = (
    "SectionHeader", "Explanation", "Text", "SettingRow", "ColourRow", "ThemeSlider",
    "ModeChip", "SpeedChip", "ActionRow", "InfoRow", "LinkRow",
)
# Named arguments that carry text a person reads.
NAMED = (
    "title", "subtitle", "label", "text", "description", "summary", "licenseNote",
    "placeholder", "confirmLabel", "dismissLabel", "contentDescription",
)

STRING = re.compile(r'"(?:[^"\\\n]|\\.)*"')
UNESCAPE = {"n": "\n", "t": "\t", '"': '"', "\\": "\\", "$": "$", "'": "'", "r": "\r"}


def unescape(raw):
    """Turns a Kotlin literal's body into the text it denotes, and \\uXXXX into its character."""
    out, i = [], 0
    while i < len(raw):
        c = raw[i]
        if c == "\\" and i + 1 < len(raw):
            nxt = raw[i + 1]
            if nxt == "u" and i + 5 < len(raw):
                out.append(chr(int(raw[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append(UNESCAPE.get(nxt, nxt))
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def read_expression(text, start):
    """Reads a run of string literals joined by `+` from `start`.

    Returns (end index, list of literal bodies) or None when `start` is not a literal. Stops at
    the first token that is not a literal or a `+`, so `"a" + count` -- a literal plus a value --
    is left alone rather than half-converted.
    """
    parts, i = [], start
    while True:
        m = STRING.match(text, i)
        if not m:
            return None if not parts else (i, parts)
        parts.append(m.group(0)[1:-1])
        i = m.end()
        j = i
        while j < len(text) and text[j] in " \t\r\n":
            j += 1
        if j < len(text) and text[j] == "+":
            k = j + 1
            while k < len(text) and text[k] in " \t\r\n":
                k += 1
            if STRING.match(text, k):
                i = k
                continue
        # Stop after the last literal, never after a `+` whose right side is not one: swallowing
        # that `+` turns `"a " + b` into `strings[...] b`, which does not parse.
        return i, parts


def split_interpolation(body):
    """Rewrites `$x` and `${x.y()}` as `%s`, returning (pattern, [argument source])."""
    out, args, i = [], [], 0
    while i < len(body):
        if body[i] == "\\" and i + 1 < len(body):
            out.append(body[i:i + 2])
            i += 2
            continue
        if body[i] == "$" and i + 1 < len(body):
            if body[i + 1] == "{":
                depth, j = 1, i + 2
                while j < len(body) and depth:
                    depth += (body[j] == "{") - (body[j] == "}")
                    j += 1
                args.append(body[i + 2:j - 1])
                out.append("%s")
                i = j
                continue
            m = re.match(r"\$([A-Za-z_][A-Za-z0-9_]*)", body[i:])
            if m:
                args.append(m.group(1))
                out.append("%s")
                i += m.end()
                continue
        out.append(body[i])
        i += 1
    return "".join(out), args


def make_key(prefix, value, taken):
    words = re.findall(r"[A-Za-z0-9]+", value.lower())[:6]
    stem = "_".join(words)[:44].strip("_") or "text"
    key = f"{prefix}_{stem}"
    if key not in taken:
        return key
    n = 2
    while f"{key}_{n}" in taken:
        n += 1
    return f"{key}_{n}"


def sites(text):
    """Yields (index of the literal, kind) for every call site worth rewriting."""
    for name in POSITIONAL:
        for m in re.finditer(r"\b" + name + r"\(\s*", text):
            yield m.end(), name
    for name in NAMED:
        for m in re.finditer(r"\b" + name + r"\s*=\s*", text):
            yield m.end(), name


def process(path, prefix, catalogue):
    text = path.read_text(encoding="utf-8")
    found = sorted(set(sites(text)), key=lambda s: -s[0])
    changed = 0
    for start, _ in found:
        read = read_expression(text, start)
        if not read:
            continue
        end, parts = read
        pattern, args = split_interpolation("".join(parts))
        value = unescape(pattern)
        if not re.search(r"[A-Za-z]{2}", value):
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
    root = pathlib.Path(".")
    catalogue = {}
    total = 0
    for arg in sys.argv[1:]:
        path = root / arg
        prefix = re.sub(r"(Screen|View)?\.kt$", "", path.name)
        prefix = re.sub(r"(?<!^)(?=[A-Z])", "_", prefix).lower()
        n = process(path, prefix, catalogue)
        total += n
        print(f"{n:4d}  {path}")
    out = pathlib.Path("build/extracted.json")
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(catalogue, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{total} sites -> {len(catalogue)} keys in {out}")


if __name__ == "__main__":
    main()
