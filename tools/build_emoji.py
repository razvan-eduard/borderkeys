#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Turns Unicode's emoji-test.txt into the panel's two assets.

The groups in that file are the ones every emoji keyboard uses, in the order the Unicode
consortium recommends for exactly this purpose, so the panel gets its categories from the
source rather than from someone's idea of which face belongs where. The same file carries each
emoji's name, which becomes the search index: one `emoji<TAB>name` line per emoji.

Skin-tone and hair variants are dropped. They multiply the list by six for a choice the panel
has no room to offer, and the base emoji is what the modifier modifies.

    python3 tools/build_emoji.py --source emoji-test.txt \\
        --out keyboard/src/main/assets/emoji/emoji.txt \\
        --names-out keyboard/src/main/assets/emoji/emoji_names.txt \\
        --annotations cldr/common \\
        --keywords-out keyboard/src/main/assets/emoji/keywords

The keywords are Unicode's CLDR annotations: for every emoji, the words people search it by,
in each language, from `common/annotations/<lang>.xml` and `common/annotationsDerived/<lang>.xml`
of a CLDR checkout. One `emoji<TAB>keyword|keyword|...` line per emoji the palette holds, one
file per language, so the panel loads the languages that are switched on and nothing else.
"""

import argparse
import pathlib
import re
import xml.etree.ElementTree as ElementTree

# The languages the panel can search in: the six the application speaks.
LANGUAGES = ("en", "de", "es", "fr", "it", "ro")

# CLDR writes most emoji without the presentation selector; the palette carries it where
# emoji-test.txt's fully-qualified form does. Both sides are compared without it.
PRESENTATION_SELECTOR = "\ufe0f"

# The keyboard shows eight tabs. Unicode's "Component" group is modifiers on their own, which
# are not emoji anyone inserts, and People & Body is folded into Smileys as every keyboard does.
GROUPS = {
    "Smileys & Emotion": "smileys",
    "People & Body": "smileys",
    "Animals & Nature": "nature",
    "Food & Drink": "food",
    "Travel & Places": "travel",
    "Activities": "activities",
    "Objects": "objects",
    "Symbols": "symbols",
    "Flags": "flags",
}

SKIN_TONES = {0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF}
HAIR = {0x1F9B0, 0x1F9B1, 0x1F9B2, 0x1F9B3}

LINE = re.compile(r"^([0-9A-F ]+);\s*(\S+)\s*#\s*\S+\s+E\d+\.\d+\s+(.*?)\s*$")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=pathlib.Path, required=True)
    parser.add_argument("--out", type=pathlib.Path, required=True)
    parser.add_argument("--names-out", type=pathlib.Path, default=None,
                        help="the search index, one emoji and its name per line")
    parser.add_argument("--annotations", type=pathlib.Path, default=None,
                        help="a CLDR checkout's common/ directory, for the keywords")
    parser.add_argument("--keywords-out", type=pathlib.Path, default=None,
                        help="the directory the per-language keyword files are written to")
    arguments = parser.parse_args()
    if (arguments.annotations is None) != (arguments.keywords_out is None):
        parser.error("--annotations and --keywords-out go together")

    order = []
    for name in GROUPS.values():
        if name not in order:
            order.append(name)
    buckets = {name: [] for name in order}
    names = []

    group = None
    for line in arguments.source.read_text(encoding="utf-8").splitlines():
        if line.startswith("# group:"):
            group = line.split(":", 1)[1].strip()
            continue
        match = LINE.match(line)
        if not match or GROUPS.get(group) is None:
            continue
        if match.group(2) != "fully-qualified":
            continue
        points = [int(p, 16) for p in match.group(1).split()]
        if any(p in SKIN_TONES or p in HAIR for p in points):
            continue
        emoji = "".join(chr(p) for p in points)
        buckets[GROUPS[group]].append(emoji)
        names.append((emoji, match.group(3).lower()))

    # One line per category: the name, a tab, then the emoji separated by spaces. No emoji
    # contains a space or a tab, so this needs no parser -- which keeps a JSON library out of
    # the keyboard process for a file that is a list of lists of strings.
    lines = [name + "\t" + " ".join(buckets[name]) for name in order]
    arguments.out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    if arguments.names_out is not None:
        arguments.names_out.write_text(
            "".join(f"{emoji}\t{name}\n" for emoji, name in names), encoding="utf-8",
        )
    total = sum(len(v) for v in buckets.values())
    for name in order:
        print(f"{name:12s} {len(buckets[name]):5d}")
    print(f"{total} emoji, {arguments.out.stat().st_size / 1024:.0f} KB")
    if arguments.names_out is not None:
        print(f"{len(names)} names, {arguments.names_out.stat().st_size / 1024:.0f} KB")
    if arguments.annotations is not None:
        write_keywords(arguments.annotations, arguments.keywords_out,
                       [emoji for emoji, _ in names])


def read_annotations(path: pathlib.Path) -> dict[str, list[str]]:
    """The keywords per emoji in one CLDR annotation file: the untyped annotations, split on
    the bar the file separates them with. The `tts` entries are the names and are left out;
    the palette carries Unicode's own name already."""
    keywords: dict[str, list[str]] = {}
    if not path.is_file():
        return keywords
    for element in ElementTree.parse(path).getroot().iter("annotation"):
        if element.get("type") is not None or not element.text:
            continue
        emoji = element.get("cp", "").replace(PRESENTATION_SELECTOR, "")
        words = [word.strip().lower() for word in element.text.split("|")]
        keywords.setdefault(emoji, []).extend(word for word in words if word)
    return keywords


def write_keywords(common: pathlib.Path, out: pathlib.Path, palette: list[str]) -> None:
    out.mkdir(parents=True, exist_ok=True)
    for language in LANGUAGES:
        keywords = read_annotations(common / "annotations" / f"{language}.xml")
        for emoji, words in read_annotations(
                common / "annotationsDerived" / f"{language}.xml").items():
            keywords.setdefault(emoji, []).extend(words)
        lines = []
        for emoji in palette:
            words = keywords.get(emoji.replace(PRESENTATION_SELECTOR, ""))
            if not words:
                continue
            distinct = list(dict.fromkeys(words))
            lines.append(emoji + "\t" + "|".join(distinct))
        target = out / f"{language}.txt"
        target.write_text("".join(line + "\n" for line in lines), encoding="utf-8")
        print(f"{language}: {len(lines)} of {len(palette)} emoji have keywords, "
              f"{target.stat().st_size / 1024:.0f} KB")


if __name__ == "__main__":
    main()
