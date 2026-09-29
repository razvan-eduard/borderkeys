#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Keeps a word list to the rows its language's own evidence supports.

    python3 tools/classify_wordlist.py --tag ro_RO                   # report only
    python3 tools/classify_wordlist.py --tag ro_RO --apply           # rewrite the list
    python3 tools/classify_wordlist.py --all --review /tmp/review    # every bundled list

Evidence for a row, any one of which vouches for it:
  spelled      a spelling dictionary of the language accepts it as written, or capitalised in
               a language that capitalises its nouns
  acronym      a spelling dictionary accepts it only in capitals
  keyboard     the word list of Android's stock keyboard for the language holds it in lower case
  spoken       the language's subtitle frequency list counts it SPOKEN_MIN_COUNT times or more
  written      the language's Leipzig news corpus writes it in lower case WRITTEN_MIN times, and
               WRITTEN_RATIO times as often as capitalised inside a sentence
  native       it is spelled with a letter of NATIVE_LETTERS[tag]
  treebank     the language's treebank tagged it (<tag>.pos)
  pairs        the corpus wrote it beside PAIR_MIN_PARTNERS words, each PAIR_MIN_COUNT times
  name         this repository's name lists flag it
  contraction  the language's contraction table spells it
  base         it is the possessive or the elision of a row with evidence
  included     dictionaries/<tag>.words-include lists it
Strong evidence is spelled, acronym, keyboard, contraction, treebank and included.

A row is a name when a spelling dictionary or the keyboard list holds it only capitalised, or
when it is capitalised: the language's Leipzig news corpus writes it capitalised inside a
sentence CAPITALISED_RATIO times as often as in lower case. Being a name saves a common row from
the typo and short rules; only being capitalised keeps a rare one. In a language that
capitalises its nouns, capitalised marks a noun or a name.

Against a row:
  excluded     dictionaries/<tag>.words-exclude lists it
  twin         an ordinary word of the same folded key, carrying more diacritics and at least as
               common, is accepted by a spelling dictionary, and no spelling dictionary accepts
               this row; in KEYBOARD_OVERRULES_SPELLING also when one does but the stock keyboard
               list holds the other spelling and not this one. In CAPITALS_DROP_ACCENTS a twin
               TWIN_DOMINANCE times rarer than the other spelling goes; otherwise a row flagged or
               held capitalised goes only when the corpus writes it in lower case at least as
               often as capitalised inside a sentence, and any other row stays only when the
               corpus writes it as a name
  typo         a row with strong evidence one edit away is commoner by the TYPO_GAPS margin; in
               the commonest band any evidence or being a name shields a row, past it only
               strong evidence, spoken or being a name does
  foreign      another bundled language holds it FOREIGN_MARGIN zipf more often, above
               FOREIGN_FLOOR, and nothing strong vouches for it here
  short        two letters, rarer than SHORT_ZIPF, without strong evidence; three letters, as
               rare and without any evidence

The decision, in order:
  excluded, included, twin
  one letter                                 kept
  two letters                                the short rule
  a row the name lists added                 kept
  foreign, typo                              dropped
  the commonest BANDS[tag] corpus rows       kept unless the short rule applies
  the rest                                   kept only with evidence

A run that would drop a guard word or keep a GUARD_DROPS word writes nothing and fails. The
evidence is fetched into --evidence on first use from the pinned sources below and checked
against its SHA-256. A spelling dictionary that refuses its probe words, or accepts nonsense,
stops the run. Needs the `hunspell` binary on PATH.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
import re
import subprocess
import sys
import tarfile
import unicodedata
import urllib.request
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_dict import fold_word  # noqa: E402
from make_names import FLAT_FREQUENCY  # noqa: E402

REPOSITORY = Path(__file__).resolve().parent.parent

LIBREOFFICE = ("https://raw.githubusercontent.com/LibreOffice/dictionaries/"
               "32b006a2c22a4ac7e8ed3f03346f7b3d85a970a4/{path}")
STOCK_KEYBOARD = ("https://raw.githubusercontent.com/LineageOS/"
                  "android_packages_inputmethods_LatinIME/ee832c1131fadaca239fb6d01f3391f894a8db6c/"
                  "dictionaries/{code}_wordlist.combined.gz")
SUBTITLES = ("https://raw.githubusercontent.com/hermitdave/FrequencyWords/"
             "525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/{code}/{code}_full.txt")
LEIPZIG = "https://downloads.wortschatz-leipzig.de/corpora/{name}.tar.gz"


@dataclass(frozen=True)
class Sources:
    """A language's spelling dictionaries (LibreOffice paths without the extension), its stock
    keyboard lists, its subtitle list, and the Leipzig corpus its capitals are counted in."""
    spelling: tuple[str, ...]
    keyboard: tuple[str, ...]
    spoken: str
    capitals: str


SOURCES = {
    "en_US": Sources(("en/en_US", "en/en_GB"), ("en", "en_US", "en_GB"), "en", "eng_news_2020_1M"),
    "ro_RO": Sources(("ro/ro_RO",), ("ro",), "ro", "ron_news_2020_1M"),
    "de_DE": Sources(("de/de_DE_frami", "de/de_AT_frami", "de/de_CH_frami"), ("de",), "de",
                     "deu_news_2020_1M"),
    "fr_FR": Sources(("fr_FR/dictionaries/fr",), ("fr",), "fr", "fra_news_2020_1M"),
    "es_ES": Sources(("es/es_ES", "es/es_MX"), ("es",), "es", "spa_news_2020_1M"),
    "it_IT": Sources(("it_IT/it_IT",), ("it",), "it", "ita_news_2020_1M"),
}

SHA256 = {
    "en_US.dic": "f0b1a234bd178bdd01875b2a392a9647f888b8fe879f79c52aae62c2759b3647",
    "en_US.aff": "e746c882dd6f303c2c46e7452804b9201115a6942cfeb15f18f8edf774d2e24e",
    "en_GB.dic": "04e90f34f5263bf26780e9c4a442e9ad16584e227af49ddd1b3b21b01df5b29c",
    "en_GB.aff": "0fd6ed120ef28957847d98ba5149b117e27116cf81b5aa36208453f6755a36fd",
    "ro_RO.dic": "c26a9356f598a0ae89e7be650f6bdd9ba70acce66b41d7ab14c0c68639b6ed33",
    "ro_RO.aff": "0c83a02f0ac5202c068e60e1aef5ce99e13d7f6c92ae8e68ca8b9e06829edfd1",
    "de_DE_frami.dic": "4ca3c958b0e5545910999bc246f668840bf8ede3df8e5e6790d05edd5a586c38",
    "de_DE_frami.aff": "646bf3333ac69c23e9d794533ee5241d6f755c359e8fe10a648f87613743d594",
    "de_AT_frami.dic": "d821e0a5a029b66df696c982a4cd55eb792139c33716ccbde364941db2448cbb",
    "de_AT_frami.aff": "8b428f6215e36e145626e3d150cd0ffe273e1ad9a9ec0a1bbd31ee468ee39edc",
    "de_CH_frami.dic": "80a4df38cdc3e16d2f042f8eba4a99668ab9c075662e4262c62f8bba46c7bd16",
    "de_CH_frami.aff": "d86884be4c3f07e5e08057884856feabf2872b4e2f90a587a11376082bc4126b",
    "fr.dic": "b78a868e31dd6e373b6c3217969afb898a9acde828a5e7ef97308da42218c88c",
    "fr.aff": "c176610cd5dc4846806a65ddd029f422d87978bf58f224aa44222662a16a2de5",
    "es_ES.dic": "6975dddec3d5d2c676069537bc67b4b5f786c65c5d4cf6703a82acf779ac9ec1",
    "es_ES.aff": "e73a9bf8e1383f4986a5dc9e2fbed49371c0c61f511c626d15586bd433c1cad9",
    "es_MX.dic": "44ce35af220962c68f97962776639bef271f7d90a85ba924b539a36e33315f82",
    "es_MX.aff": "d966cb748e4a688ed75ec84b50c0835ac438e5325ee4fce703a093794f9cba7e",
    "it_IT.dic": "bae1e3501dcd2a923669592493b3fde6c02aae7c7aab83bf5e5b49077e73dd64",
    "it_IT.aff": "951afaa19272f13555b8823e8bcf9ccf78f8fe1a07835bdfb912ab3e4d537c2b",
    "en_wordlist.combined.gz": "07682388185c285d307e341d1733331af8699f735b4137e9f22571017fab69d2",
    "en_US_wordlist.combined.gz":
        "0f78dd455b532be169a23f233227b811fabced4b5bd7fc9c40cc05839793bcbd",
    "en_GB_wordlist.combined.gz":
        "2d8555b0f1e256be9953a4575595f2fc76d559bffdf3fa4c9bd7f263185d9b58",
    "ro_wordlist.combined.gz": "47139886aea4be76bd5857a6c62cc2b84f2094d58cb2c7b8e7dc05e17452960f",
    "de_wordlist.combined.gz": "07ae553cd55f9901412065bad7617379908ed5f2a701d226ffde85a9b8ffd5c0",
    "fr_wordlist.combined.gz": "cc917a0a81acab0ea12089c2fb06b5c6be98c75f23a7605bfdbbeb698abfd65e",
    "es_wordlist.combined.gz": "889ad52bce2933e2a30a0560b8d5a76f5334500e776a7db7c7cb3e9e79fc2652",
    "it_wordlist.combined.gz": "4ac1fa3b112130416843f5abc2a61fdbd2395a41fb6a719790cd7c45934218ec",
    "en_full.txt": "7fea67ab954e2c01df6c608c9826e594cf36f8823b3243554f88245fb75dc506",
    "ro_full.txt": "a0d0993701e251e91b9047753b6032be751217a5aaba36ed879163e020e33ded",
    "de_full.txt": "a5fc13f4efaaf70fd9226d11295fd3cd4c2264852ee1fad22c0abaf64acec8dc",
    "fr_full.txt": "dda23c3025fc195b3a7996af26371dcf3abd1aa2cd7218dbbb7c92a49780ab0c",
    "es_full.txt": "b4794c425f3500c21a0ff9367175e36795a978c928a36670e9ad6ae206fb7728",
    "it_full.txt": "b23b0c6a3f59c1da1c7caa667b9df5699e95323fee20a05f343c7d7dae73c4be",
    "eng_news_2020_1M.tar.gz": "be782eb82690415241d623fd2448dfd3fc68102ac1ce971107cb130420abbb41",
    "ron_news_2020_1M.tar.gz": "6daa196d4c175ed447f227140618732edf7ca5889f56e53c290916ffaabd8d0d",
    "deu_news_2020_1M.tar.gz": "ba5f9f8fe0c1fb58817094c3b7475be83c4d2730395c119be58a2f04c34665e3",
    "fra_news_2020_1M.tar.gz": "9a7a252453483e43a061213ac6b05b5558a70de18b09d0bb7e7de6b249f393b3",
    "spa_news_2020_1M.tar.gz": "89fb1319f53b341466c065152467cb1bd3789a3ed9aa143807b1952503ef1d50",
    "ita_news_2020_1M.tar.gz": "b0728fc9d2ace61947b16b61162ca89be1c04531b4a56053f55a14bb68dae20c",
}

# How many of the commonest corpus rows are judged by what counts against them; the rest need
# evidence.
BANDS = {"en_US": 65_000, "es_ES": 45_000, "fr_FR": 40_000, "de_DE": 40_000,
         "it_IT": 40_000, "ro_RO": 40_000}

# Languages whose spelling dictionaries accept nouns only capitalised.
CAPITALISES_EVERY_NOUN = {"de_DE"}

# The letters each bundled language uses and none of the other five does.
NATIVE_LETTERS = {"ro_RO": "ășț", "es_ES": "ñ", "de_DE": "ßäö", "fr_FR": "çœêëû", "it_IT": "ìò",
                  "en_US": ""}

# Languages where a spelling the dictionary accepts is still a twin when the stock keyboard list
# holds the spelling with more diacritics and not this one.
KEYBOARD_OVERRULES_SPELLING = {"ro_RO"}

# Words every spelling dictionary of the language must accept, and one none may.
PROBES = {"en_US": ("the", "house"), "ro_RO": ("și", "casa"), "de_DE": ("und", "über"),
          "fr_FR": ("être", "maison"), "es_ES": ("años", "casa"), "it_IT": ("perché", "casa")}
NONSENSE = "qzxqzxqz"

# The zipf margin by which a commoner neighbour makes a row its typo, by the row's length:
# (shortest, longest, margin).
TYPO_GAPS = ((6, 99, 2.0), (4, 5, 2.5), (3, 3, 3.0))
# How common a row with strong evidence has to be to count as a neighbour a typo is made of.
TYPO_TARGET_ZIPF = 3.5

FOREIGN_FLOOR = 3.0
FOREIGN_MARGIN = 1.0
SHORT_ZIPF = 3.0

SPOKEN_MIN_COUNT = 50
PAIR_MIN_COUNT = 10
PAIR_MIN_PARTNERS = 3

# A row the corpus writes capitalised inside a sentence at least CAPITALISED_MIN times, and
# CAPITALISED_RATIO times as often as in lower case, is written as a name.
CAPITALISED_MIN = 3
CAPITALISED_RATIO = 3

# A row the corpus writes in lower case at least WRITTEN_MIN times, and WRITTEN_RATIO times as
# often as capitalised inside a sentence, is written as an ordinary word.
WRITTEN_MIN = 5
WRITTEN_RATIO = 9

# In CAPITALS_DROP_ACCENTS, a twin TWIN_DOMINANCE times rarer than its ordinary spelling with
# more diacritics goes however the corpus capitalises it.
TWIN_DOMINANCE = 10
CAPITALS_DROP_ACCENTS = {"fr_FR", "es_ES", "ro_RO"}

# Dropped rows at least this common are written to the borderline review file.
BORDERLINE_ZIPF = 2.5

COVERAGE_TOPS = (10_000, 20_000, 50_000)

ELISIONS = ("l'", "d'", "j'", "m'", "n'", "s'", "t'", "c'", "qu'", "un'", "dell'", "all'",
            "nell'", "sull'", "dall'", "quest'")

GUARD_WORDS = {
    "en_US": ("the", "and", "you", "gonna", "wanna", "okay", "hello", "thanks", "don't", "i'm",
              "cafe", "resume", "naive"),
    "ro_RO": ("și", "că", "să", "ca", "sa", "fata", "casa", "mâine", "este", "mulțumesc",
              "acasă", "bună", "salut", "între", "până", "țară", "puțin", "mulți"),
    "de_DE": ("und", "nicht", "danke", "hallo", "schon", "schön", "für", "über", "würde",
              "wurde", "zahlen", "zählen"),
    "es_ES": ("gracias", "hola", "años", "también", "si", "sí", "el", "él", "tu", "tú", "más",
              "como", "cómo", "que", "qué"),
    "fr_FR": ("être", "merci", "bonjour", "aujourd'hui", "salut", "ou", "où", "la", "là", "du",
              "sur", "sûr", "des", "déjà", "très"),
    "it_IT": ("grazie", "ciao", "perché", "però", "è", "da", "si", "sì", "la", "là", "ne",
              "più", "già"),
}

# Rows a run must drop.
GUARD_DROPS = {"ro_RO": ("maine",)}


# --------------------------------------------------------------------------------------------
# Evidence files.
# --------------------------------------------------------------------------------------------

def fetch(directory: Path, name: str, url: str) -> Path:
    """[name] in [directory], downloaded from [url] when absent, checked against SHA256."""
    path = directory / name
    if not path.is_file():
        directory.mkdir(parents=True, exist_ok=True)
        print(f"fetching {url}", file=sys.stderr)
        partial = path.with_name(path.name + ".partial")
        with urllib.request.urlopen(url, timeout=600) as response, partial.open("wb") as out:
            while chunk := response.read(1 << 20):
                out.write(chunk)
        partial.replace(path)
    hasher = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1 << 20):
            hasher.update(chunk)
    digest = hasher.hexdigest()
    if digest != SHA256[name]:
        raise SystemExit(f"{path}: SHA-256 {digest} is not the pinned {SHA256[name]}")
    return path


def spelling_dictionaries(directory: Path, sources: Sources) -> list[Path]:
    """The base paths hunspell -d takes, one per spelling dictionary, fetched."""
    bases = []
    for path in sources.spelling:
        base = Path(path).name
        for extension in ("dic", "aff"):
            fetch(directory, f"{base}.{extension}", LIBREOFFICE.format(path=f"{path}.{extension}"))
        bases.append(directory / base)
    return bases


def keyboard_words(directory: Path, sources: Sources) -> tuple[set[str], set[str]]:
    """The stock keyboard lists' words: those held in lower case, and those held only
    capitalised, lower-cased."""
    lower: set[str] = set()
    capitalised: set[str] = set()
    for code in sources.keyboard:
        name = f"{code}_wordlist.combined.gz"
        path = fetch(directory, name, STOCK_KEYBOARD.format(code=code))
        with gzip.open(path, "rt", encoding="utf-8") as handle:
            for line in handle:
                if not line.startswith(" word="):
                    continue
                word = line[len(" word="):].split(",", 1)[0]
                if word == word.lower():
                    lower.add(word)
                else:
                    capitalised.add(word.lower())
    if len(lower) < 10_000:
        raise SystemExit(f"the stock keyboard lists for {sources.keyboard} hold {len(lower)} words")
    return lower, capitalised - lower


def spoken_counts(directory: Path, sources: Sources) -> dict[str, int]:
    """The subtitle list: word to count, commonest first."""
    name = f"{sources.spoken}_full.txt"
    path = fetch(directory, name, SUBTITLES.format(code=sources.spoken))
    counts: dict[str, int] = {}
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            parts = line.split()
            if len(parts) != 2:
                continue
            try:
                counts.setdefault(parts[0].lower(), int(parts[1]))
            except ValueError:
                continue
    if len(counts) < 10_000:
        raise SystemExit(f"{path} holds {len(counts)} words")
    return counts


TOKEN = re.compile(r"[^\W\d_]+(?:['’-][^\W\d_]+)*")


def capital_counts(directory: Path, sources: Sources,
                   words: set[str]) -> dict[str, tuple[int, int]]:
    """For each of [words]: how often the corpus writes it in lower case, and how often
    capitalised other than as a sentence's first word."""
    if not words:
        return {}
    name = sources.capitals
    path = fetch(directory, f"{name}.tar.gz", LEIPZIG.format(name=name))
    lower: Counter = Counter()
    capitalised: Counter = Counter()
    with tarfile.open(path) as archive:
        member = next((m for m in archive.getmembers() if m.name.endswith("-sentences.txt")), None)
        if member is None:
            raise SystemExit(f"{path} holds no sentences file")
        handle = archive.extractfile(member)
        for raw in handle:
            sentence = raw.decode("utf-8", "replace").rstrip("\n").split("\t", 1)[-1]
            for position, match in enumerate(TOKEN.finditer(sentence)):
                token = match.group(0)
                folded = token.lower()
                if folded not in words:
                    continue
                if token == folded:
                    lower[folded] += 1
                elif position > 0:
                    capitalised[folded] += 1
    return {word: (lower[word], capitalised[word]) for word in words}


def hunspell_accepted(words: list[str], dictionary: Path, transform) -> set[str]:
    """Every word of [words] the dictionary accepts once [transform] is applied to it."""
    if not words:
        return set()
    shaped = [transform(word) for word in words]
    try:
        result = subprocess.run(
            ["hunspell", "-d", str(dictionary), "-i", "UTF-8", "-l"],
            input="\n".join(shaped) + "\n", capture_output=True, text=True, check=False,
        )
    except FileNotFoundError:
        raise SystemExit("hunspell is not on PATH")
    if result.returncode != 0:
        raise SystemExit(f"hunspell -d {dictionary} failed: {result.stderr.strip()}")
    refused = {line.strip() for line in result.stdout.splitlines() if line.strip()}
    return {word for word, form in zip(words, shaped) if form not in refused}


def check_dictionary(tag: str, dictionary: Path) -> None:
    """Stops the run when [dictionary] refuses its language's probe words or accepts nonsense."""
    probes = list(PROBES[tag])
    accepted = hunspell_accepted(probes + [NONSENSE], dictionary, lambda w: w)
    if NONSENSE in accepted or not all(probe in accepted for probe in probes):
        raise SystemExit(f"{dictionary}: accepted {sorted(accepted)} of {probes + [NONSENSE]}")


# --------------------------------------------------------------------------------------------
# The repository's own evidence.
# --------------------------------------------------------------------------------------------

def read_rows(path: Path) -> list[tuple[str, int, bool]]:
    """`word<TAB>frequency[<TAB>name]`, in the order the file holds them."""
    rows: list[tuple[str, int, bool]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) < 2 or not parts[0]:
            continue
        try:
            frequency = int(parts[1])
        except ValueError:
            continue
        rows.append((parts[0], frequency, len(parts) > 2 and parts[2] == "name"))
    return rows


def read_word_file(path: Path) -> set[str]:
    """One word per line; `#` starts a comment."""
    if not path.is_file():
        return set()
    words = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        word = line.split("#", 1)[0].strip()
        if word:
            words.add(word)
    return words


def treebank_vocabulary(path: Path) -> set[str]:
    """Every word the language's treebank tagged, lower-cased."""
    if not path.is_file():
        return set()
    tags = json.loads(path.read_text(encoding="utf-8")).get("tags", {})
    return {word.lower() for word in tags if any(c.isalpha() for c in word)}


def pair_vouched(path: Path) -> set[str]:
    """Rows the corpus wrote beside at least PAIR_MIN_PARTNERS different words, each pair at
    least PAIR_MIN_COUNT times."""
    if not path.is_file():
        return set()
    partners: dict[str, set[str]] = {}
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 3:
                continue
            try:
                count = int(parts[2])
            except ValueError:
                continue
            if count < PAIR_MIN_COUNT:
                continue
            first, second = parts[0], parts[1]
            if first != "start":
                partners.setdefault(first, set()).add(second)
            partners.setdefault(second, set()).add(first)
    return {word for word, others in partners.items() if len(others) >= PAIR_MIN_PARTNERS}


def contraction_forms(tag: str) -> set[str]:
    """Both columns of the language's contraction table."""
    path = REPOSITORY / "keyboard/src/main/assets/contractions" / f"{tag.replace('_', '-')}.txt"
    forms: set[str] = set()
    if not path.is_file():
        return forms
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("#"):
            continue
        parts = line.split("\t")
        forms.update(part for part in parts[:2] if part)
    return forms


def zipf_table(rows: list[tuple[str, int, bool]]) -> dict[str, float]:
    total = sum(frequency for _, frequency, _ in rows) or 1
    return {word: math.log10(frequency / total * 1e9) for word, frequency, _ in rows}


# --------------------------------------------------------------------------------------------
# Rules.
# --------------------------------------------------------------------------------------------

def marks(word: str) -> int:
    """How many diacritics [word] carries."""
    return sum(1 for c in unicodedata.normalize("NFD", word) if unicodedata.combining(c))


def base_of(word: str) -> str | None:
    """The row a possessive or an elision is built on, or None."""
    if word.endswith("'s") and len(word) > 3:
        return word[:-2]
    for elision in ELISIONS:
        if word.startswith(elision) and len(word) > len(elision) + 1:
            return word[len(elision):]
    return None


def neighbours(word: str, alphabet: str) -> set[str]:
    """Every string one deletion, insertion, substitution or transposition from [word]."""
    out: set[str] = set()
    for i in range(len(word) + 1):
        for letter in alphabet:
            out.add(word[:i] + letter + word[i:])
    for i in range(len(word)):
        out.add(word[:i] + word[i + 1:])
        if i + 1 < len(word):
            out.add(word[:i] + word[i + 1] + word[i] + word[i + 2:])
        for letter in alphabet:
            if letter != word[i]:
                out.add(word[:i] + letter + word[i + 1:])
    out.discard(word)
    return out


def elongated(word: str, vouched: set[str]) -> bool:
    """Whether [word] repeats a letter three times running and collapsing the runs gives a row
    with evidence."""
    if not re.search(r"(.)\1\1", word):
        return False
    return re.sub(r"(.)\1+", r"\1", word) in vouched


# --------------------------------------------------------------------------------------------
# The classification.
# --------------------------------------------------------------------------------------------

@dataclass
class Result:
    """The rows kept, in the list's order, and every row's reason: (kind, detail)."""
    kept: list[tuple[str, int, bool]]
    keep_reason: dict[str, tuple[str, str]]
    drop_reason: dict[str, tuple[str, str]]
    zipf: dict[str, float]


def classify(tag: str, rows: list[tuple[str, int, bool]], evidence_dir: Path,
             other_zipfs: dict[str, dict[str, float]]) -> tuple[Result, dict]:
    sources = SOURCES[tag]
    dictionaries = spelling_dictionaries(evidence_dir, sources)
    for dictionary in dictionaries:
        check_dictionary(tag, dictionary)
    keyboard_lower, keyboard_capitalised = keyboard_words(evidence_dir, sources)
    spoken = spoken_counts(evidence_dir, sources)
    listed = REPOSITORY / "dictionaries"

    words = [word for word, _, _ in rows]
    count = {word: frequency for word, frequency, _ in rows}
    zipf = zipf_table(rows)

    def accepted(transform) -> set[str]:
        out: set[str] = set()
        for dictionary in dictionaries:
            out |= hunspell_accepted(words, dictionary, transform)
        return out

    lower_ok = accepted(lambda w: w.lower())
    cap_ok = accepted(lambda w: w[:1].upper() + w[1:].lower())
    upper_ok = accepted(lambda w: w.upper())
    if tag in CAPITALISES_EVERY_NOUN:
        spelled = lower_ok | cap_ok
        checker_name: set[str] = set()
    else:
        spelled = lower_ok
        checker_name = cap_ok - lower_ok
    acronym = upper_ok - cap_ok - lower_ok

    def rows_in(source: set[str]) -> set[str]:
        return {word for word in words if word in source or word.lower() in source}

    flagged = {word for word, _, is_name in rows if is_name}
    name_list_rows = {word for word, frequency, is_name in rows
                      if is_name and frequency == FLAT_FREQUENCY}
    keyboard = rows_in(keyboard_lower)
    keyboard_name = rows_in(keyboard_capitalised)
    spoken_rows = {word for word in words if spoken.get(word.lower(), 0) >= SPOKEN_MIN_COUNT}
    treebank = rows_in(treebank_vocabulary(listed / f"{tag}.pos"))
    pairs = rows_in(pair_vouched(listed / f"{tag}.ngrams"))
    contraction = rows_in(contraction_forms(tag))
    included = rows_in(read_word_file(listed / f"{tag}.words-include"))
    excluded = rows_in(read_word_file(listed / f"{tag}.words-exclude"))

    strong = spelled | acronym | keyboard | contraction | treebank | included
    any_evidence = strong | spoken_rows | pairs | flagged
    based = {word for word in words
             if word not in any_evidence and (base_of(word) or "") in any_evidence}
    any_evidence |= based
    capitals = capital_counts(evidence_dir, sources, {word.lower() for word in words})

    def written_as_name(word: str) -> bool:
        lower, capitalised = capitals[word.lower()]
        return capitalised >= CAPITALISED_MIN and capitalised >= CAPITALISED_RATIO * lower

    capitalised_rows = {word for word in words if written_as_name(word)}
    any_evidence |= capitalised_rows
    written_rows = {word for word in words
                    if capitals[word.lower()][0] >= WRITTEN_MIN
                    and capitals[word.lower()][0] >= WRITTEN_RATIO * capitals[word.lower()][1]}
    any_evidence |= written_rows
    native_rows = {word for word in words if any(c in NATIVE_LETTERS[tag] for c in word.lower())}
    any_evidence |= native_rows
    named = flagged | checker_name | keyboard_name | capitalised_rows
    shield_common = any_evidence | named
    shield_rare = strong | named | spoken_rows

    # Twins: a row beside an ordinary word of the same folded key that carries more diacritics,
    # is accepted by a spelling dictionary and is at least as common, when no spelling
    # dictionary accepts the row, or, where KEYBOARD_OVERRULES_SPELLING, the stock keyboard list
    # holds the other spelling and not this one. A row that may be a name goes only when the
    # corpus writes it in lower case at least as often as capitalised inside a sentence; any
    # other row stays only when the corpus writes it as a name.
    by_key: dict[tuple[int, ...], list[str]] = {}
    for word in words:
        by_key.setdefault(fold_word(word.lower()), []).append(word)
    twin: dict[str, str] = {}
    twin_detail: dict[str, str] = {}
    for group in by_key.values():
        if len(group) < 2:
            continue
        for word in group:
            if word in acronym:
                continue
            fuller = [v for v in group
                      if marks(v) > marks(word) and v in spelled and count[v] >= count[word]]
            if not fuller:
                continue
            if word in spelled:
                if (tag not in KEYBOARD_OVERRULES_SPELLING or word in keyboard
                        or word in keyboard_name or not any(v in keyboard for v in fuller)):
                    continue
            best = max(fuller, key=lambda v: count[v])
            lower, capitalised = capitals[word.lower()]
            if tag in CAPITALS_DROP_ACCENTS and count[best] >= TWIN_DOMINANCE * count[word]:
                goes = True
            elif word in flagged or word in checker_name or word in keyboard_name:
                goes = lower >= max(1, capitalised)
            else:
                goes = not written_as_name(word)
            if goes:
                twin[word] = best
                detail = f"lower case {lower}, capitalised {capitalised}"
                if word in spelled:
                    detail += "; the keyboard list holds only the other"
                twin_detail[word] = detail

    # Typos: rows one edit from a commoner row with strong evidence.
    alphabet = "".join(sorted({c for word in words for c in word if c.isalpha()}))
    targets = {word for word in strong if zipf[word] >= TYPO_TARGET_ZIPF}
    corpus_rows = [word for word, frequency, is_name in rows if word not in name_list_rows]
    ranked = sorted(corpus_rows, key=lambda w: -count[w])
    rank_of = {word: place for place, word in enumerate(ranked)}
    band = BANDS[tag]
    typo: dict[str, tuple[str, float]] = {}
    for place, word in enumerate(ranked):
        shield = shield_common if place < band else shield_rare
        if len(word) < 3 or word in shield or word in twin or elongated(word, any_evidence):
            continue
        margin = next((g for low, high, g in TYPO_GAPS if low <= len(word) <= high), None)
        if margin is None:
            continue
        best, best_zipf = "", 0.0
        for other in neighbours(word, alphabet):
            if other in targets and zipf[other] > best_zipf:
                best, best_zipf = other, zipf[other]
        if best and best_zipf >= zipf[word] + margin:
            typo[word] = (best, best_zipf - zipf[word])

    foreign: dict[str, tuple[str, float]] = {}
    for word in ranked:
        if word in strong or word in flagged or elongated(word, any_evidence):
            continue
        best, best_zipf = "", 0.0
        for other_tag, table in other_zipfs.items():
            score = table.get(word, 0.0)
            if score > best_zipf:
                best, best_zipf = other_tag, score
        if best_zipf > FOREIGN_FLOOR and best_zipf > zipf[word] + FOREIGN_MARGIN:
            foreign[word] = (best, best_zipf)

    def evidence_kind(word: str) -> str:
        return next(kind for kind, members in (
            ("spelled", spelled), ("acronym", acronym), ("keyboard", keyboard),
            ("contraction", contraction), ("treebank", treebank), ("included", included),
            ("spoken", spoken_rows), ("written", written_rows), ("native", native_rows),
            ("pairs", pairs),
            ("name", flagged), ("base", based), ("capitalised", capitalised_rows),
        ) if word in members)

    keep_reason: dict[str, tuple[str, str]] = {}
    drop_reason: dict[str, tuple[str, str]] = {}
    for word in words:
        if word in excluded:
            drop_reason[word] = ("excluded", "")
        elif word in included:
            keep_reason[word] = ("included", "")
        elif word in twin:
            detail = f"of {twin[word]}" + (f"; {twin_detail[word]}" if word in twin_detail else "")
            drop_reason[word] = ("twin", detail)
        elif len(word) == 1:
            keep_reason[word] = ("one letter", "")
        elif len(word) == 2:
            if word in strong or word in flagged or zipf[word] >= SHORT_ZIPF:
                keep_reason[word] = ("two letters", "")
            else:
                drop_reason[word] = ("two letters", "rare, no strong evidence")
        elif word in name_list_rows:
            keep_reason[word] = ("name list", "")
        elif word in foreign:
            drop_reason[word] = ("foreign", f"{foreign[word][0]} at {foreign[word][1]:.1f} zipf")
        elif word in typo:
            drop_reason[word] = ("typo", f"of {typo[word][0]} by {typo[word][1]:.1f} zipf")
        elif rank_of[word] < band:
            if len(word) == 3 and word not in shield_common and zipf[word] < SHORT_ZIPF:
                drop_reason[word] = ("three letters", "rare, no evidence")
            else:
                keep_reason[word] = ("common", "")
        elif word in any_evidence:
            keep_reason[word] = ("rare", evidence_kind(word))
        else:
            drop_reason[word] = ("rare", "no evidence")

    kept = [row for row in rows if row[0] in keep_reason]
    stats = {
        "spelled": len(spelled), "checker name": len(checker_name),
        "acronym": len(acronym), "keyboard": len(keyboard), "spoken": len(spoken_rows),
        "treebank": len(treebank), "pairs": len(pairs), "flagged": len(flagged),
        "contraction": len(contraction), "base": len(based), "twin": len(twin),
        "capitalised": len(capitalised_rows), "written": len(written_rows),
        "native": len(native_rows),
        "typo": len(typo), "foreign": len(foreign), "spoken list": spoken,
        "keyboard list": keyboard_lower,
    }
    return Result(kept, keep_reason, drop_reason, zipf), stats


def coverage(sample: list[str], before: set, after: set) -> str:
    """The share of [sample] whose folded key is in [before], then in [after]."""
    if not sample:
        return "-"
    keys = [fold_word(word) for word in sample]
    return (f"{sum(1 for k in keys if k in before) / len(sample):.1%} -> "
            f"{sum(1 for k in keys if k in after) / len(sample):.1%}")


def report(tag: str, rows, result: Result, stats: dict, review: Path | None, sample: int) -> bool:
    before = {word.lower() for word, _, _ in rows}
    after = {word.lower() for word, _, _ in result.kept}
    before_keys = {fold_word(word) for word in before}
    after_keys = {fold_word(word) for word in after}
    print(f"{tag}: {len(rows):,} rows, {len(result.drop_reason):,} dropped, "
          f"{len(result.kept):,} kept")
    print("    evidence: " + ", ".join(f"{stats[k]:,} {k}" for k in (
        "spelled", "checker name", "acronym", "keyboard", "spoken", "treebank", "pairs",
        "flagged", "contraction", "base", "capitalised", "written", "native")))
    kinds = Counter(kind for kind, _ in result.drop_reason.values())
    for kind, total in kinds.most_common():
        print(f"    dropped, {kind}: {total:,}")
        shown = sorted((w for w, (k, _) in result.drop_reason.items() if k == kind),
                       key=lambda w: -result.zipf[w])
        for word in shown[:sample]:
            detail = result.drop_reason[word][1]
            print(f"        {word:<22} {detail:<40} zipf {result.zipf[word]:.2f}")
    for (kind, detail), total in Counter(result.keep_reason.values()).most_common():
        print(f"    kept, {kind}{', ' + detail if detail else ''}: {total:,}")

    spoken_ranked = sorted(stats["spoken list"], key=lambda w: -stats["spoken list"][w])
    for top in COVERAGE_TOPS:
        print(f"    commonest {top:,} subtitle words reachable: "
              f"{coverage(spoken_ranked[:top], before_keys, after_keys)}")

    failed = False
    lost = [w for w in GUARD_WORDS.get(tag, ()) if w in before and w not in after]
    if lost:
        print(f"    GUARD WORDS DROPPED: {', '.join(lost)}")
        failed = True
    kept_bad = [w for w in GUARD_DROPS.get(tag, ()) if w in after]
    if kept_bad:
        print(f"    GUARD DROPS KEPT: {', '.join(kept_bad)}")
        failed = True

    if review is not None:
        review.mkdir(parents=True, exist_ok=True)
        ordered = sorted(result.drop_reason, key=lambda w: -result.zipf[w])

        def line(word: str, reason: tuple[str, str]) -> str:
            return f"{word}\t{reason[0]}\t{reason[1]}\t{result.zipf[word]:.2f}\n"

        with (review / f"{tag}.dropped.tsv").open("w", encoding="utf-8") as handle:
            handle.writelines(line(w, result.drop_reason[w]) for w in ordered)
        with (review / f"{tag}.borderline.tsv").open("w", encoding="utf-8") as handle:
            handle.writelines(line(w, result.drop_reason[w]) for w in ordered
                              if result.zipf[w] >= BORDERLINE_ZIPF)
        with (review / f"{tag}.kept.tsv").open("w", encoding="utf-8") as handle:
            handle.writelines(line(w, result.keep_reason[w]) for w, _, _ in result.kept)
        print(f"    review: {review}/{tag}.{{dropped,borderline,kept}}.tsv")
    return not failed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--tag", action="append", default=[], choices=sorted(SOURCES))
    parser.add_argument("--all", action="store_true", help="every bundled list")
    parser.add_argument("--evidence", type=Path,
                        default=Path.home() / ".cache" / "borderkeys" / "word-evidence")
    parser.add_argument("--review", type=Path, default=None,
                        help="a directory for <tag>.dropped.tsv, .borderline.tsv and .kept.tsv")
    parser.add_argument("--apply", action="store_true", help="rewrite the list; otherwise report")
    parser.add_argument("--sample", type=int, default=12, help="dropped rows to print per reason")
    arguments = parser.parse_args()
    tags = sorted(SOURCES) if arguments.all else arguments.tag
    if not tags:
        parser.error("name a --tag or give --all")

    listed = REPOSITORY / "dictionaries"
    all_rows = {tag: read_rows(listed / f"{tag}.tsv") for tag in SOURCES}
    zipfs = {tag: zipf_table(rows) for tag, rows in all_rows.items()}

    status = 0
    for tag in tags:
        rows = all_rows[tag]
        others = {other: table for other, table in zipfs.items() if other != tag}
        result, stats = classify(tag, rows, arguments.evidence, others)
        if not report(tag, rows, result, stats, arguments.review, arguments.sample):
            print(f"    {tag}: nothing written")
            status = 1
            continue
        if arguments.apply:
            (listed / f"{tag}.tsv").write_text(
                "".join(f"{w}\t{f}\tname\n" if n else f"{w}\t{f}\n" for w, f, n in result.kept),
                encoding="utf-8",
            )
            print(f"    written: dictionaries/{tag}.tsv")
        print()
    return status


if __name__ == "__main__":
    raise SystemExit(main())
