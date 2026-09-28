#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Builds a per-language list of proper names from Wikidata, for make_pack.py's --names.

Standard library only: `urllib.request` against Wikidata's public SPARQL endpoint.

Wikidata is CC0 (docs/licensing.md section 2, https://www.wikidata.org/wiki/Wikidata:Licensing)
and carries no frequencies, so every name gets FLAT_FREQUENCY. The counts printed at the end
show how many names each language came back with.

Each row is "word<TAB>frequency<TAB>name<TAB>evidence": build_dict.py's word-list format, plus
an evidence count that make_pack.py reads and build_dict.py ignores.

Two kinds of name, asked for in separate runs
----------------------------------------------
`--kind persons` (the default) asks for given names and family names; the evidence is how many
people Wikidata records with each. `--kind entities` asks for companies, countries, islands and
organisations; the evidence is how many Wikipedias carry an article.

Labels are read in the language's own code and in `mul`, where Wikidata keeps language-neutral
labels.

Usage
-----
    ./make_names.py --language ro --out names_ro.tsv
    ./make_names.py --language en --out names_en.tsv --limit 20000
    ./make_names.py --language en --kind entities --out entities_en.tsv
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

# Given names (the bare class, and the male, female and unisex ones) and family names, read
# through "instance of" alone, without "subclass of".
GIVEN_NAME_CLASSES = ("Q202444", "Q12308941", "Q11879590", "Q3409032")
FAMILY_NAME = "Q101352"

# The entity roots, traversed with "subclass of".
ENTITY_ROOTS = ("Q43229", "Q6256", "Q23442")  # organisation, country, island

# Subtracted from the organisation closure: settlements and administrative units. Capital cities
# arrive through the country root.
SETTLEMENT = "Q486972"
ADMINISTRATIVE = "Q56061"

# How many Wikipedias must carry an article before a label is kept; make_pack.py's stricter
# NAME_ADD_MIN_USES decides what joins a pack.
MIN_SITELINKS = 10

# The shortest label kept, as flag_names.py's MIN_LENGTH.
MIN_NAME_LENGTH = 3

# The one frequency every name gets.
FLAT_FREQUENCY = 40

# Paged requests, with a User-Agent naming the tool as the query service's manual asks
# (https://www.mediawiki.org/wiki/Wikidata_Query_Service/User_Manual).
PAGE_SIZE = 5000
USER_AGENT = "BorderKeys-make_names.py/1.0 (https://github.com/borderkeys/borderkeys)"
REQUEST_DELAY_SECONDS = 1.0
MAX_RETRIES = 8


def label_filter(language: str) -> str:
    """Accept this language's own label and the language-neutral `mul` one."""
    return f'FILTER(LANG(?nameLabel) = "{language}" || LANG(?nameLabel) = "mul")'


def run_query(query: str, endpoint: str) -> list[tuple[str, int]]:
    """One request, with the retries the shared public endpoints need."""
    url = f"{endpoint}?{urllib.parse.urlencode({'query': query})}"
    request = urllib.request.Request(
        url,
        headers={"Accept": "application/sparql-results+json", "User-Agent": USER_AGENT},
    )
    last_error: Exception | None = None
    for attempt in range(MAX_RETRIES):
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                payload = json.loads(response.read())
            return [(row["nameLabel"]["value"], int(row["uses"]["value"]))
                    for row in payload["results"]["bindings"]]
        except urllib.error.HTTPError as error:
            last_error = error
            if error.code == 429:
                # Rate limited: Retry-After when sent, 30 seconds otherwise.
                wait = float(error.headers.get("Retry-After", 30))
                print(f"rate limited, waiting {wait:.0f}s...", file=sys.stderr)
                time.sleep(wait)
            elif error.code in (500, 502, 503):
                # Server load: a longer backoff.
                time.sleep(REQUEST_DELAY_SECONDS * (attempt + 1) * 5)
            else:
                time.sleep(REQUEST_DELAY_SECONDS * (attempt + 1))
        except (urllib.error.URLError, json.JSONDecodeError, TimeoutError) as error:
            last_error = error
            time.sleep(REQUEST_DELAY_SECONDS * (attempt + 1))
    raise SystemExit(f"query failed after {MAX_RETRIES} attempts: {last_error}")


def usable_entity_label(label: str) -> bool:
    """Whether a label can go in a pack: letters only, at least MIN_NAME_LENGTH, and not all
    upper case."""
    return (label.isalpha() and len(label) >= MIN_NAME_LENGTH and not label.isupper())


def entity_query(language: str, min_sitelinks: int) -> str:
    """Companies, countries and islands, ranked by how many Wikipedias carry an article.

    One unpaged request. Multi-word labels are refused, not split into words.
    """
    roots = "\n      UNION\n".join(
        f"      {{ ?item wdt:P31/wdt:P279* wd:{root} . }}" if root != "Q43229" else
        f"""      {{
        ?item wdt:P31/wdt:P279* wd:{root} .
        MINUS {{ ?item wdt:P31/wdt:P279* wd:{SETTLEMENT} }}
        MINUS {{ ?item wdt:P31/wdt:P279* wd:{ADMINISTRATIVE} }}
      }}"""
        for root in ENTITY_ROOTS)
    return f"""
    PREFIX wd: <http://www.wikidata.org/entity/>
    PREFIX wdt: <http://www.wikidata.org/prop/direct/>
    PREFIX wikibase: <http://wikiba.se/ontology#>
    PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
    PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
    SELECT ?nameLabel (MAX(xsd:integer(?links)) AS ?uses) WHERE {{
{roots}
      ?item wikibase:sitelinks ?links .
      # Cast: the qlever mirror parses this as xsd:int, which a bare ">=" never matches.
      FILTER(xsd:integer(?links) >= {min_sitelinks})
      ?item rdfs:label ?nameLabel .
      {label_filter(language)}
      FILTER(!CONTAINS(?nameLabel, " "))
    }}
    GROUP BY ?nameLabel
    """


def query_page(language: str, limit: int, offset: int, endpoint: str, min_given_uses: int,
               min_family_uses: int) -> list[str]:
    # No ORDER BY; fetch_names orders the deduplicated set. Each branch counts the distinct
    # people Wikidata records with the name (wdt:P735 given, wdt:P734 family) and keeps names at
    # or above that branch's threshold; the count is the evidence make_pack.py reads.
    query = f"""
    PREFIX wd: <http://www.wikidata.org/entity/>
    PREFIX wdt: <http://www.wikidata.org/prop/direct/>
    PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
    SELECT ?nameLabel ?uses WHERE {{
      {{
        SELECT ?nameLabel (COUNT(DISTINCT ?person) AS ?uses) WHERE {{
          VALUES ?givenClass {{ {" ".join("wd:" + q for q in GIVEN_NAME_CLASSES)} }}
          ?name wdt:P31 ?givenClass .
          ?name rdfs:label ?nameLabel .
          {label_filter(language)}
          ?person wdt:P735 ?name .
        }}
        GROUP BY ?nameLabel
        HAVING (COUNT(DISTINCT ?person) >= {min_given_uses})
      }}
      UNION
      {{
        SELECT ?nameLabel (COUNT(DISTINCT ?person) AS ?uses) WHERE {{
          ?name wdt:P31 wd:{FAMILY_NAME} .
          ?name rdfs:label ?nameLabel .
          {label_filter(language)}
          ?person wdt:P734 ?name .
        }}
        GROUP BY ?nameLabel
        HAVING (COUNT(DISTINCT ?person) >= {min_family_uses})
      }}
    }}
    LIMIT {limit}
    OFFSET {offset}
    """
    return run_query(query, endpoint)


def fetch_names(language: str, limit: int | None, page_size: int, endpoint: str,
                 min_given_uses: int, min_family_uses: int) -> list[str]:
    """Pages through Wikidata until a page comes back short (the real end of the result set) or
    `limit` is reached, whichever comes first."""
    names: list[tuple[str, int]] = []
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
    parser.add_argument("--kind", choices=("persons", "entities"), default="persons",
                        help="persons (default): given and family names, counted by how many "
                             "people Wikidata records with each. entities: companies, countries "
                             "and islands, counted by how many Wikipedias carry an article. The "
                             "fourth column means a different thing in each, so write them to "
                             "different files -- see the module doc")
    parser.add_argument("--min-sitelinks", type=int, default=MIN_SITELINKS,
                        help=f"--kind entities only: Wikipedias that must carry an article "
                             f"(default {MIN_SITELINKS})")
    arguments = parser.parse_args()

    print(f"fetching {arguments.language} {arguments.kind} from Wikidata "
          f"({arguments.endpoint})...", file=sys.stderr)
    if arguments.kind == "entities":
        names = run_query(entity_query(arguments.language, arguments.min_sitelinks),
                          arguments.endpoint)
        names = [(label, count) for label, count in names if usable_entity_label(label)]
    else:
        names = fetch_names(arguments.language, arguments.limit, arguments.page_size,
                            arguments.endpoint, arguments.min_given_uses,
                            arguments.min_family_uses)

    # A name found as both a given and a family name is written once, with the larger count.
    uses: dict[str, int] = {}
    for name, count in names:
        uses[name] = max(uses.get(name, 0), count)
    unique = sorted(uses)

    with open(arguments.out, "w", encoding="utf-8") as handle:
        for name in unique:
            handle.write(f"{name}\t{FLAT_FREQUENCY}\tname\t{uses[name]}\n")

    print(f"wrote {len(unique)} {arguments.kind} ({len(names) - len(unique)} duplicates dropped) "
          f"for {arguments.language!r} to {arguments.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
