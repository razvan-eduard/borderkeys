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

Two kinds of name, asked for separately
---------------------------------------
`--kind persons` (the default, and what this script has always done) asks for given names and
family names, and counts how many real people Wikidata records with each. `--kind entities` asks
for companies, countries, islands and organisations, and counts how many Wikipedias have an
article. Both write the same four columns, but the fourth means something different in each, so
they are deliberately separate runs into separate files rather than one mixed list: a surname
carried by 50 people and a company written up in 50 languages are not the same evidence, and a
reader of the output should not have to guess which one a row came from.

Entities are the only route to `paribas`, `ubisoft` and `bytedance` -- words no spell checker
holds in any case form, so `flag_names.py`'s capitalisation rule cannot see them either.

The `mul` label, and why the person query was quietly missing most of its answers
--------------------------------------------------------------------------------
Wikidata has moved language-neutral labels -- which is what a name usually is -- to the `mul`
language code, and a query filtering `LANG(?label) = "en"` cannot see them. Measured 2026-09-20,
that is most of the answer: given names went from 89,263 to 145,741 for English and from 36,077
to 92,555 for Romanian once `mul` was accepted alongside the language's own code. "Ubisoft" has
no `en` label at all, only a `mul` one. This is not a widening, it is a repair: those rows were
always meant to be in the result and silently stopped being returned.

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

# A given name or a family name (Q101352) -- not "subclass of" either, which would pull in every
# more-specific naming convention Wikidata models (e.g. "Icelandic patronymic") and cost a much
# more expensive server-side traversal for names this project has no way to render any
# differently anyway. Given names are the one place the flat "instance of" read is not enough:
# Wikidata types most of them as a *male*, *female* or *unisex* given name rather than as the
# bare class, so a query for Q202444 alone misses "Maria" and "Laurențiu" while finding the
# rarer items someone typed as plain "given name". The four classes are listed explicitly rather
# than traversed.
GIVEN_NAME_CLASSES = ("Q202444", "Q12308941", "Q11879590", "Q3409032")
FAMILY_NAME = "Q101352"

# The entity side. Unlike the name classes above these ARE traversed with "subclass of", because
# there is no flat list to enumerate: a bank, a football club, a record label and a university
# are all organisations only by way of a chain of intermediate classes, and Wikidata types
# companies inconsistently enough that a flat read of "instance of" finds ByteDance and misses
# Ubisoft. Measured on qlever.dev, the closure costs a few seconds, not the server-side blowup
# the given-name comment warns about -- that warning is about traversing NAME classes, which
# fan out into every naming convention on earth and buy nothing this project can render.
ENTITY_ROOTS = ("Q43229", "Q6256", "Q23442")  # organisation, country, island

# Subtracted from the organisation closure, and this is the whole reason the roots are not just
# "organisation". A municipality is both an organisation and a settlement, so the closure walks
# straight into every village on the planet: measured, the organisation root alone returned
# 149,252 single-token English labels, of which the overwhelming majority were places like
# "Zavattarello", "Zawiercie" and "Wittenberge". Removing these two takes that to 12,761 without
# losing a single company.
#
# Settlements are excluded rather than thresholded because sitelinks cannot rank them: several
# Wikipedias generate one stub per populated place by bot, so an Italian hamlet and a national
# broadcaster both come back with fifty-odd languages and the count stops meaning notability.
# Capital cities are not lost by this -- they arrive through the country root instead.
SETTLEMENT = "Q486972"
ADMINISTRATIVE = "Q56061"

# How many Wikipedias must carry an article before a label is worth having. Ten is low on
# purpose: it is a floor on being a real subject at all, not on fame, and the decision that
# actually adds a word to a pack is make_pack.py's NAME_ADD_MIN_USES, which is stricter.
MIN_SITELINKS = 10

# Below this there is not enough word to judge, and two-letter entity labels are overwhelmingly
# ticker symbols and country codes rather than anything anyone types. Same floor, same reason,
# as flag_names.py's own MIN_LENGTH.
MIN_NAME_LENGTH = 3

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
MAX_RETRIES = 8


def label_filter(language: str) -> str:
    """Accept this language's own label and the language-neutral one.

    See the module doc: `mul` is where Wikidata now keeps a label that is the same in every
    language, which is what a proper name usually is, and leaving it out is why the person query
    had been returning barely half its answers.
    """
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
                # The shared public endpoint's own rate limiter, not a transient network blip --
                # a 1-2-3 second backoff is exactly the pace that got it to answer with 429 in
                # the first place. Respect Retry-After when it sends one; 30 seconds otherwise is
                # long enough to actually clear the window rather than knock on it again.
                wait = float(error.headers.get("Retry-After", 30))
                print(f"rate limited, waiting {wait:.0f}s...", file=sys.stderr)
                time.sleep(wait)
            elif error.code in (500, 502, 503):
                # Seen from the qlever mirror on a query it had just answered, so it is load and
                # not the query: worth the same backoff rather than giving up on the run.
                time.sleep(REQUEST_DELAY_SECONDS * (attempt + 1) * 5)
            else:
                time.sleep(REQUEST_DELAY_SECONDS * (attempt + 1))
        except (urllib.error.URLError, json.JSONDecodeError, TimeoutError) as error:
            last_error = error
            time.sleep(REQUEST_DELAY_SECONDS * (attempt + 1))
    raise SystemExit(f"query failed after {MAX_RETRIES} attempts: {last_error}")


def usable_entity_label(label: str) -> bool:
    """Whether a label is a word this project can actually put in a pack.

    Letters only, so "3M", "10bet.com" and "1-800-Flowers" are out; the trie is keyed on letters
    and a digit in a word would never be reached by typing anyway.

    All upper case is out, and that one is not obvious. The proper-noun flag is a single bit and
    the spelling stored beside it is the lower-cased one, so the only thing the flag can produce
    is a leading capital -- "Bbc", "Fbi", "Nasa". For an acronym that is not a fix, it is a new
    and more visible kind of wrong than the uncapitalised word it replaces, so these are left
    alone until the format can carry a spelling of its own. A thousand English labels a run.
    """
    return (label.isalpha() and len(label) >= MIN_NAME_LENGTH and not label.isupper())


def entity_query(language: str, min_sitelinks: int) -> str:
    """Companies, countries and islands, ranked by how many Wikipedias carry an article.

    No LIMIT/OFFSET: unlike the person query this returns thousands rather than hundreds of
    thousands of rows, and paging a GROUP BY costs the server the whole grouping per page.

    Multi-word labels are refused here rather than split into words. Splitting was measured on
    2026-09-20 and is badly wrong: every ordinary word inside an organisation's name becomes a
    candidate, so English gained "united", "district", "congress" and "museum" and Romanian
    gained "tău" ("your") and "cealaltă" ("the other"), and the treebank cannot refuse any of
    them -- it tags them as proper nouns precisely BECAUSE they occur inside names. The packs
    grew 140%. The cost of refusing is real and accepted: "BNP Paribas" and "SoftBank Group"
    contribute nothing, so `paribas` arrives only where it is a label in its own right.
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
      # Cast, do not compare. Wikidata declares this xsd:integer but the qlever mirror parses it
      # as xsd:int, and a bare ">= 10" against an xsd:int silently matches NOTHING there -- no
      # error, no rows, a filter that looks like it is working and is discarding the whole
      # result set. Found by a count that came back zero against data that was plainly present.
      FILTER(xsd:integer(?links) >= {min_sitelinks})
      ?item rdfs:label ?nameLabel .
      {label_filter(language)}
      FILTER(!CONTAINS(?nameLabel, " "))
    }}
    GROUP BY ?nameLabel
    """


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
    # Each branch also returns how many distinct people carry the name: that count is the one
    # real measure of how established a name is, and make_pack.py weighs it against how common
    # the same word is in the corpus -- a word among the language's most frequent needs far
    # more people behind it before it may capitalise itself every time it is typed.
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

    # A name can legitimately appear more than once across given-name and family-name items
    # (Wikidata models them as separate entities even when the string is identical, e.g. many
    # surnames also exist as given names) -- deduplicated here since the pack format has no use
    # for the same word twice, keeping the larger of its people counts. That count goes out as
    # a fourth column, which make_pack.py reads and build_dict.py ignores.
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
