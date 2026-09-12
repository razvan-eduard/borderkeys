#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Builds a per-language list of proper names from Wikidata, for make_pack.py's --names.

Standard library only, like everything else in tools/: `urllib.request` against Wikidata's own
public SPARQL endpoint, nothing installed.

Wikidata (CC0 -- see docs/licensing.md section 2, and https://www.wikidata.org/wiki/Wikidata:Licensing)
is a *classification* source, not a *frequency* one: it knows "Ana" is a given name in Romanian,
not how common it is relative to "Ecaterina". Every name this emits gets the same flat,
moderate frequency (see FLAT_FREQUENCY below) -- high enough to surface as a suggestion, low
enough to never outrank an actual common word from the real corpus. That is a documented
approximation, not real usage data; see docs/dictionaries.md's own entry for this source.

Coverage is uneven by design, not by bug: Wikidata's language coverage skews toward the
languages with the most editors (English, German, French, Spanish), and a thinner language
(Romanian, here) will come back with genuinely fewer names. Printed counts at the end say so
plainly rather than silently producing an uneven pack.

Output is exactly build_dict.py's own 3-column word-list format ("word<TAB>frequency<TAB>name"),
so a names file can be fed to build_dict.py directly (a names-only pack, useful for testing the
proper-noun flag in isolation) or merged in by make_pack.py --names.

Usage
-----
    ./make_names.py --language ro --out names_ro.tsv
    ./make_names.py --language en --out names_en.tsv --limit 20000
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ENDPOINT = "https://query.wikidata.org/sparql"

# A given name (Q202444) or a family name (Q101352) -- not "subclass of" either, which would
# pull in every more-specific naming convention Wikidata models (e.g. "Icelandic patronymic") and
# cost a much more expensive server-side traversal for names this project has no way to render
# any differently anyway.
GIVEN_NAME = "Q202444"
FAMILY_NAME = "Q101352"

# One flat tier for every name this script emits -- see the module doc for why a real per-name
# frequency is out of scope. Chosen low relative to LOG_PROB_SCALE=10's effective range (a
# quantised value of 40 is -4.0 nats, i.e. roughly as likely as a word making up ~2% of a
# reference corpus) so a name is offered but a real common word always wins a close contest.
FLAT_FREQUENCY = 40

# The public endpoint times out and rate-limits expensive queries; paging keeps each request
# small and fast rather than asking for everything at once and hoping. Wikidata's own etiquette
# guide (https://www.mediawiki.org/wiki/Wikidata_Query_Service/User_Manual) asks for a real
# User-Agent identifying the tool, which is also required in practice -- an unidentified client
# is the first thing their rate limiter throttles.
PAGE_SIZE = 5000
USER_AGENT = "BorderKeys-make_names.py/1.0 (https://github.com/borderkeys/borderkeys)"
REQUEST_DELAY_SECONDS = 1.0
MAX_RETRIES = 3


def query_page(language: str, limit: int, offset: int, endpoint: str, min_given_uses: int,
               min_family_uses: int) -> list[str]:
    # Deliberately no ORDER BY: sorting the whole union before LIMIT/OFFSET can even apply is
    # exactly what turned a page that should take a couple of seconds into one that didn't
    # return inside a minute against the public endpoint. Final ordering happens once, in
    # Python, over the already-deduplicated full set -- see fetch_names -- so nothing downstream
    # needs the server to have sorted anything.
    #
    # A real measurement issue found 2026-09-12: the unfiltered query returns 420k-608k rows per
    # language, most of it name-shaped noise Wikidata classifies as a given/family name but that
    # essentially no one actually has (place names, transliteration artefacts, historical
    # naming-convention oddities). Wikipedia sitelink count on the name-as-a-concept item looked
    # like a fix at first but wasn't reproducible on retest (near-zero every time, not the ~27k it
    # first appeared to be) -- most "name" items simply don't carry their own sitelinks at all.
    #
    # What actually works: how many real Wikidata people are recorded as having this name, via
    # wdt:P735 (given name) / wdt:P734 (family name) -- the exact properties this taxonomy exists
    # to support. Given and family names need different thresholds: family names are individually
    # much rarer per name (usage>=1 alone barely filters anything, e.g. 342,660 of Romanian's
    # 420,223 unfiltered rows), while given names cluster on far fewer distinct values and are
    # already well-filtered at usage>=1. Confirmed 2026-09-12 that an absolute family-name
    # threshold (50) holds steady (13k-15k) across languages with very different raw sizes
    # (ro/en/de), so this doesn't need to scale per language.
    query = f"""
    PREFIX wd: <http://www.wikidata.org/entity/>
    PREFIX wdt: <http://www.wikidata.org/prop/direct/>
    PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
    SELECT DISTINCT ?nameLabel WHERE {{
      {{
        SELECT ?nameLabel WHERE {{
          ?name wdt:P31 wd:{GIVEN_NAME} .
          ?name rdfs:label ?nameLabel .
          FILTER(LANG(?nameLabel) = "{language}")
          ?person wdt:P735 ?name .
        }}
        GROUP BY ?nameLabel
        HAVING (COUNT(DISTINCT ?person) >= {min_given_uses})
      }}
      UNION
      {{
        SELECT ?nameLabel WHERE {{
          ?name wdt:P31 wd:{FAMILY_NAME} .
          ?name rdfs:label ?nameLabel .
          FILTER(LANG(?nameLabel) = "{language}")
          ?person wdt:P734 ?name .
        }}
        GROUP BY ?nameLabel
        HAVING (COUNT(DISTINCT ?person) >= {min_family_uses})
      }}
    }}
    LIMIT {limit}
    OFFSET {offset}
    """
    url = f"{endpoint}?{urllib.parse.urlencode({'query': query})}"
    request = urllib.request.Request(
        url,
        headers={"Accept": "application/sparql-results+json", "User-Agent": USER_AGENT},
    )
    last_error: Exception | None = None
    for attempt in range(MAX_RETRIES):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                payload = json.loads(response.read())
            return [row["nameLabel"]["value"] for row in payload["results"]["bindings"]]
        except urllib.error.HTTPError as error:
            last_error = error
            if error.code == 429:
                # The shared public endpoint's own rate limiter, not a transient network blip --
                # a 1-2-3 second backoff is exactly the pace that got it to answer with 429 in
                # the first place. Respect Retry-After when it sends one; 30 seconds otherwise is
                # long enough to actually clear the window rather than knock on it again.
                wait = float(error.headers.get("Retry-After", 30))
                print(f"rate limited, waiting {wait:.0f}s...", file=sys.stderr)
                time.sleep(wait)
            else:
                time.sleep(REQUEST_DELAY_SECONDS * (attempt + 1))
        except (urllib.error.URLError, json.JSONDecodeError, TimeoutError) as error:
            last_error = error
            time.sleep(REQUEST_DELAY_SECONDS * (attempt + 1))
    raise SystemExit(f"query failed after {MAX_RETRIES} attempts: {last_error}")


def fetch_names(language: str, limit: int | None, page_size: int, endpoint: str,
                 min_given_uses: int, min_family_uses: int) -> list[str]:
    """Pages through Wikidata until a page comes back short (the real end of the result set) or
    `limit` is reached, whichever comes first."""
    names: list[str] = []
    offset = 0
    while True:
        page_limit = page_size if limit is None else min(page_size, limit - len(names))
        if page_limit <= 0:
            break
        page = query_page(language, page_limit, offset, endpoint, min_given_uses, min_family_uses)
        names.extend(page)
        offset += len(page)
        if len(page) < page_limit:
            break  # short page: no more results, not just this page's own cap
        time.sleep(REQUEST_DELAY_SECONDS)
    return names


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--language", required=True,
                        help="Wikidata label language code, e.g. ro, en, de, es, fr, it")
    parser.add_argument("--out", required=True)
    parser.add_argument("--limit", type=int, default=None,
                        help="cap on names fetched (default: no cap, page until exhausted)")
    parser.add_argument("--page-size", type=int, default=PAGE_SIZE,
                        help=f"rows requested per page (default: {PAGE_SIZE}); lower this if the "
                             "public endpoint is truncating large responses mid-transfer")
    parser.add_argument("--endpoint", default=ENDPOINT,
                        help=f"SPARQL endpoint (default: {ENDPOINT}); "
                             "https://qlever.dev/api/wikidata is a Wikidata-compatible mirror "
                             "that has proven far more reliable against this exact query shape")
    parser.add_argument("--min-given-uses", type=int, default=1,
                        help="only keep a given name if at least this many distinct Wikidata "
                             "people are recorded as having it (wdt:P735) -- default 1, since "
                             "given names are already well-filtered at that floor")
    parser.add_argument("--min-family-uses", type=int, default=50,
                        help="only keep a family name if at least this many distinct Wikidata "
                             "people are recorded as having it (wdt:P734) -- default 50; usage>=1 "
                             "barely filters family names at all (e.g. 342,660 of Romanian's "
                             "420,223 unfiltered rows), unlike given names")
    arguments = parser.parse_args()

    print(f"fetching {arguments.language} names from Wikidata ({arguments.endpoint})...",
          file=sys.stderr)
    names = fetch_names(arguments.language, arguments.limit, arguments.page_size,
                         arguments.endpoint, arguments.min_given_uses, arguments.min_family_uses)

    # A name can legitimately appear more than once across given-name and family-name items
    # (Wikidata models them as separate entities even when the string is identical, e.g. many
    # surnames also exist as given names) -- deduplicated here since the pack format has no use
    # for the same word twice.
    unique = sorted(set(names))

    with open(arguments.out, "w", encoding="utf-8") as handle:
        for name in unique:
            handle.write(f"{name}\t{FLAT_FREQUENCY}\tname\n")

    print(f"wrote {len(unique)} names ({len(names) - len(unique)} duplicates dropped) "
          f"for {arguments.language!r} to {arguments.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
