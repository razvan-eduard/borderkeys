#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Builds a downloadable language pack from a manifest, end to end.

A manifest in tools/languages/<tag>.json names the corpora, the spelling dictionary, the
treebank and the long-press letters of one language. This runs the pipeline the six bundled
packs went through, step by step, each step skipped when its output is already there:

    fetch      the Leipzig corpora, the LibreOffice Hunspell dictionary, the UD treebank
    overlay    the long-press letters into keyboard/src/main/assets/accents/<tag>.json
    names      the person and entity lists from Wikidata
    grammar    the tag per word and the transition matrix into dictionaries/extra/<tag>.pos
    corpus     a first count of the corpora, for the ordinary-word list
    ordinary   the corpus words the spelling dictionary lists in lower case
    pack       the count again, names merged and flagged, into dictionaries/extra/<tag>.tsv
    clean      mojibake, unreachable rows, another language's words, the misspelling report
    compile    the .bkd, with its size and word count
    check      every row of the list reachable from the pack, against the manifest's budget

Nothing here is bundled: the lists live in dictionaries/extra/, which the Gradle task does
not compile into the application, and the .bkd is published as a release asset.

    python3 tools/new_language.py tools/languages/nl_NL.json
    python3 tools/new_language.py tools/languages/nl_NL.json --only fetch,names
"""

import argparse
import json
import shutil
import subprocess
import sys
import tarfile
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
EXTRA = ROOT / "dictionaries" / "extra"
ACCENTS = ROOT / "keyboard" / "src" / "main" / "assets" / "accents"
BUNDLED = ("en_US", "ro_RO", "de_DE", "es_ES", "fr_FR", "it_IT")

LEIPZIG = "https://downloads.wortschatz-leipzig.de/corpora/{name}.tar.gz"
HUNSPELL = "https://raw.githubusercontent.com/LibreOffice/dictionaries/master/{path}.{ext}"
ENGLISH_HUNSPELL = "en/en_US"
TREEBANK = "https://raw.githubusercontent.com/UniversalDependencies/{repository}/master/{file}"
USER_AGENT = "BorderKeys-tools (https://github.com/razvan-eduard/borderkeys)"

STEPS = ("fetch", "overlay", "names", "grammar", "corpus", "ordinary", "pack", "clean", "compile", "check")

# Rows per Wikidata query, and where the queries go: the QLever mirror answers a page of
# twenty thousand rows in seconds where Wikidata's own endpoint rate-limits the query into
# failure, and each page costs the same fixed time whatever its size.
NAMES_PAGE_SIZE = 20000
NAMES_ENDPOINT = "https://qlever.cs.uni-freiburg.de/api/wikidata"


def log(message: str) -> None:
    print(f"[new_language] {message}", flush=True)


def run(command: list, **kwargs) -> None:
    log(" ".join(str(part) for part in command))
    subprocess.run([str(part) for part in command], check=True, **kwargs)


def download(url: str, target: Path) -> None:
    if target.is_file() and target.stat().st_size > 0:
        log(f"have {target.name}")
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    log(f"fetching {url}")
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    partial = target.with_suffix(target.suffix + ".part")
    with urllib.request.urlopen(request, timeout=120) as response, partial.open("wb") as out:
        shutil.copyfileobj(response, out, length=1 << 20)
    partial.rename(target)


class Language:
    def __init__(self, manifest: Path, work_root: Path, names_page_size: int = NAMES_PAGE_SIZE,
                 names_endpoint: str = NAMES_ENDPOINT):
        self.names_page_size = names_page_size
        self.names_endpoint = names_endpoint
        self.manifest = json.loads(manifest.read_text(encoding="utf-8"))
        self.tag = self.manifest["tag"]
        self.file_tag = self.tag.replace("-", "_")
        self.work = work_root / self.file_tag
        self.work.mkdir(parents=True, exist_ok=True)
        EXTRA.mkdir(parents=True, exist_ok=True)

    # ---- where things go -------------------------------------------------------------------

    @property
    def sentences(self) -> list:
        return [self.work / "corpora" / f"{name}-sentences.txt" for name in self.manifest["corpora"]]

    @property
    def hunspell_sources(self) -> list:
        """(base path in the work dir, .dic URL, .aff URL) per spelling dictionary the manifest names.

        A string names a directory and base in LibreOffice's dictionaries repository; a
        mapping names a base and the two files' own URLs, for a language that repository
        does not carry.
        """
        sources = []
        for entry in self.manifest["hunspell"]:
            if isinstance(entry, str):
                sources.append((self.work / "hunspell" / Path(entry).name,
                                HUNSPELL.format(path=entry, ext="dic"), HUNSPELL.format(path=entry, ext="aff")))
            else:
                sources.append((self.work / "hunspell" / entry["base"], entry["dic"], entry["aff"]))
        return sources

    @property
    def hunspell_bases(self) -> list:
        return [base for base, _, _ in self.hunspell_sources]

    @property
    def english_hunspell(self) -> Path:
        return self.work / "hunspell" / Path(ENGLISH_HUNSPELL).name

    @property
    def treebank_files(self) -> list:
        return [self.work / "treebank" / name for name in self.manifest["treebank"]["files"]]

    @property
    def persons(self) -> Path:
        return self.work / "names-persons.tsv"

    @property
    def entities(self) -> Path:
        return self.work / "names-entities.tsv"

    @property
    def grammar(self) -> Path:
        return EXTRA / f"{self.file_tag}.pos"

    @property
    def first_count(self) -> Path:
        return self.work / "first" / f"{self.file_tag}.tsv"

    @property
    def ordinary(self) -> Path:
        return EXTRA / f"{self.file_tag}.names-ordinary"

    @property
    def words(self) -> Path:
        return EXTRA / f"{self.file_tag}.tsv"

    @property
    def ngrams(self) -> Path:
        return EXTRA / f"{self.file_tag}.ngrams"

    @property
    def pack(self) -> Path:
        return self.work / "packs" / f"{self.file_tag}.bkd"

    # ---- the steps -------------------------------------------------------------------------

    def fetch(self) -> None:
        for name, target in zip(self.manifest["corpora"], self.sentences):
            if target.is_file():
                log(f"have {target.name}")
                continue
            archive = self.work / "corpora" / f"{name}.tar.gz"
            download(LEIPZIG.format(name=name), archive)
            with tarfile.open(archive) as tar:
                member = next(m for m in tar.getmembers() if m.name.endswith("-sentences.txt"))
                with tar.extractfile(member) as source, target.open("wb") as out:
                    shutil.copyfileobj(source, out, length=1 << 20)
            archive.unlink()
        english = (self.english_hunspell,
                   HUNSPELL.format(path=ENGLISH_HUNSPELL, ext="dic"),
                   HUNSPELL.format(path=ENGLISH_HUNSPELL, ext="aff"))
        for base, dic, aff in self.hunspell_sources + [english]:
            download(dic, base.with_suffix(".dic"))
            download(aff, base.with_suffix(".aff"))
        repository = self.manifest["treebank"]["repository"]
        for name, target in zip(self.manifest["treebank"]["files"], self.treebank_files):
            download(TREEBANK.format(repository=repository, file=name), target)

    def overlay(self) -> None:
        target = ACCENTS / f"{self.tag}.json"
        keys = ", ".join(f'"{k}": "{v}"' for k, v in self.manifest["overlay"].items())
        target.write_text(f'{{ "tag": "{self.tag}", "keys": {{ {keys} }} }}\n', encoding="utf-8")
        log(f"wrote {target.relative_to(ROOT)}")

    def names(self) -> None:
        language = self.manifest["wikidata"]
        options = ["--page-size", str(self.names_page_size), "--endpoint", self.names_endpoint]
        if not self.persons.is_file():
            run([sys.executable, HERE / "make_names.py", "--language", language,
                 "--min-family-uses", "5", *options, "--out", self.persons])
        if not self.entities.is_file():
            run([sys.executable, HERE / "make_names.py", "--language", language,
                 "--kind", "entities", *options, "--out", self.entities])

    def grammar_step(self) -> None:
        if self.grammar.is_file():
            log(f"have {self.grammar.name}")
            return
        run([sys.executable, HERE / "build_pos.py", "--treebank", *self.treebank_files,
             "--out", self.grammar])

    def corpus(self) -> None:
        if self.first_count.is_file():
            log(f"have {self.first_count.relative_to(self.work)}")
            return
        run([sys.executable, HERE / "make_pack.py", "--corpus", *self.sentences,
             "--tag", self.tag, "--out", self.first_count.with_suffix(".bkd"), "--keep-intermediate"])

    def ordinary_step(self) -> None:
        if self.ordinary.is_file():
            log(f"have {self.ordinary.name}")
            return
        if not self.hunspell_bases:
            log("no spelling dictionary for this language: the treebank alone guards the names")
            return
        run([sys.executable, HERE / "make_ordinary.py", "--words", self.first_count,
             "--names", self.persons, "--dictionary", *self.hunspell_bases, self.english_hunspell,
             "--out", self.ordinary])

    def pack_step(self) -> None:
        if self.words.is_file() and self.ngrams.is_file():
            log(f"have {self.words.name}")
            return
        out = self.work / "second" / f"{self.file_tag}.bkd"
        command = [sys.executable, HERE / "make_pack.py", "--corpus", *self.sentences,
                   "--names", self.entities, "--names-flag-only", self.persons,
                   "--grammar", self.grammar,
                   "--tag", self.tag, "--out", out, "--keep-intermediate"]
        if self.ordinary.is_file():
            command += ["--names-ordinary", self.ordinary]
        exclude = EXTRA / f"{self.file_tag}.names-exclude"
        if exclude.is_file():
            command += ["--names-exclude", exclude]
        include = EXTRA / f"{self.file_tag}.names-include"
        if include.is_file():
            command += ["--names-include", include]
        run(command)
        shutil.copyfile(out.with_suffix(".tsv"), self.words)
        shutil.copyfile(out.with_suffix(".ngrams"), self.ngrams)

    def clean(self) -> None:
        # The cleaning tools read every list in one directory and compare them, so the six
        # bundled lists sit beside this one in a working copy and only this one is written back.
        lists = self.work / "lists"
        lists.mkdir(exist_ok=True)
        for tag in BUNDLED:
            for ext in ("tsv", "pos"):
                source = ROOT / "dictionaries" / f"{tag}.{ext}"
                if source.is_file():
                    shutil.copyfile(source, lists / source.name)
        shutil.copyfile(self.words, lists / self.words.name)
        shutil.copyfile(self.grammar, lists / self.grammar.name)
        run([sys.executable, HERE / "drop_mojibake.py", lists / self.words.name])
        run([sys.executable, HERE / "drop_unreachable.py", lists / self.words.name])
        oracles = sum((["--hunspell", f"{self.file_tag}={base}"] for base in self.hunspell_bases), [])
        foreign = [sys.executable, HERE / "drop_foreign.py", "--dictionaries", lists, "--tag", self.file_tag,
                   *oracles, "--pos", lists, "--apply"]
        if not oracles:
            foreign.append("--no-oracle")
        run(foreign)
        if oracles:
            run([sys.executable, HERE / "drop_misspellings.py", "--dictionaries", lists, "--tag", self.file_tag,
                 *oracles, "--report", self.work / "misspellings.tsv"])
            log(f"misspelling candidates for review: {self.work / 'misspellings.tsv'}")
        shutil.copyfile(lists / self.words.name, self.words)

    def compile(self) -> None:
        self.pack.parent.mkdir(exist_ok=True)
        run([sys.executable, HERE / "build_dict.py", "--words", self.words, "--ngrams", self.ngrams,
             "--grammar", self.grammar, "--tag", self.tag, "--out", self.pack])
        words = sum(1 for _ in self.words.open(encoding="utf-8"))
        log(f"{self.pack.name}: {self.pack.stat().st_size:,} bytes, {words:,} words")

    def check(self) -> None:
        evaluator = ROOT / "native-tests" / "build" / "suggest_eval"
        if not evaluator.is_file():
            raise SystemExit("build native-tests first: suggest_eval is what checks reachability")
        budget = self.manifest.get("reachability_budget")
        command = [evaluator, self.pack.parent, "--reachable", self.words, self.tag]
        if budget is not None:
            command.append(str(budget))
        run(command)

    def run_steps(self, only: set) -> None:
        actions = {
            "fetch": self.fetch, "overlay": self.overlay, "names": self.names,
            "grammar": self.grammar_step, "corpus": self.corpus, "ordinary": self.ordinary_step,
            "pack": self.pack_step, "clean": self.clean, "compile": self.compile, "check": self.check,
        }
        for step in STEPS:
            if step in only:
                log(f"== {step}")
                actions[step]()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--work", type=Path, default=ROOT / "build" / "languages",
                        help="where downloads and intermediate files go (default build/languages)")
    parser.add_argument("--only", default=",".join(STEPS),
                        help="comma-separated steps to run, in pipeline order: " + ", ".join(STEPS))
    parser.add_argument("--names-page-size", type=int, default=NAMES_PAGE_SIZE,
                        help=f"rows per Wikidata query (default {NAMES_PAGE_SIZE})")
    parser.add_argument("--names-endpoint", default=NAMES_ENDPOINT,
                        help=f"the SPARQL endpoint the name lists come from (default {NAMES_ENDPOINT})")
    arguments = parser.parse_args()
    only = {step.strip() for step in arguments.only.split(",")}
    unknown = only - set(STEPS)
    if unknown:
        raise SystemExit(f"unknown steps: {', '.join(sorted(unknown))}")
    Language(arguments.manifest, arguments.work, arguments.names_page_size, arguments.names_endpoint).run_steps(only)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
