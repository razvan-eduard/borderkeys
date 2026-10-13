#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""A language's sources as its manifest in tools/languages/<tag>.json names them, and the Leipzig
corpora among them, fetched and checked against their SHA-256.

The keys read here:
  corpora        the Leipzig corpora the word list is counted from
  known_corpora  the corpora the .known words are counted from
  case_corpora   the corpora the .case evidence is counted from
  capitals       the corpus classify_wordlist.py counts capitals in
  heldout        a news corpus none of those is, nor from any of their years, which the test
                 corpora are drawn from
  persona        the name the personal corpus follows
  sha256         the SHA-256 of the archive of each corpus those name, by file name

A corpus is fetched into the cache as its archive and its sentence file, under a lock on the
corpus. A pinned archive is checked before its sentences are taken out: another digest deletes the
archive and any sentence file taken out before, and stops. An archive with no pin yet has its
sentences taken out first, which reads it to its end, and only then, for a corpus the manifest
names, its digest written into the manifest; a corpus it does not name is pinned nowhere. A
sentence file is read only beside a record of the digest it was taken out under, matching the pin,
or the archive when there is none. A manifest is rewritten under a lock on its directory, through
a file renamed into place, and keeps the pins of only the corpora it names.

A word list's `corpora` are found by count: every subset of the corpora the manifest names, and of
any more corpora given, is tried, the words counted as make_pack.py counts them, and
a subset is the list's when it is the only one that gives each of the list's commonest compared
rows its count within TOLERANCE. Left out of the comparison: names, words holding an apostrophe or
a hyphen, words holding a letter normalise_diacritics.py or fold_diacritic_noise.py folds, and
words whose folded spelling is also one of those. A corpus's word counts are kept beside its
sentence file, with the digest of the archive they were counted from.

    python3 tools/language_manifest.py --selftest
    python3 tools/language_manifest.py --list-corpora ro_RO                # report
    python3 tools/language_manifest.py --list-corpora ro_RO --also ron_news_2015_1M
    python3 tools/language_manifest.py --list-corpora en_US --write        # and record it
"""

import argparse
import contextlib
import fcntl
import hashlib
import itertools
import json
import os
import shutil
import sys
import tarfile
import tempfile
import urllib.request
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fold_diacritic_noise  # noqa: E402
import make_pack  # noqa: E402
import normalise_diacritics  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
LANGUAGES = ROOT / "tools" / "languages"
CACHE = Path.home() / ".cache" / "borderkeys" / "leipzig"
LEIPZIG = "https://downloads.wortschatz-leipzig.de/corpora/{name}.tar.gz"
USER_AGENT = "BorderKeys-tools (https://github.com/razvan-eduard/borderkeys)"

# How long a download may stall, in seconds.
DOWNLOAD_TIMEOUT = 600

# The bytes read at a time when copying or hashing.
CHUNK_BYTES = 1 << 20

# The file ending of the sentences inside a Leipzig archive.
SENTENCES_SUFFIX = "-sentences.txt"

# The file ending of the record beside a sentence file: the digest of the archive it came from.
VERIFIED_SUFFIX = ".sha256"

# The file ending of the lock beside a corpus.
LOCK_SUFFIX = ".lock"

# How many of a word list's commonest compared rows a set of corpora is checked against.
COMPARED_ROWS = 200

# How far, as a share of the list's count, a set's count of a compared row may be.
TOLERANCE = 0.0025

# The file ending of a corpus's word counts beside its sentence file.
COUNTS_SUFFIX = "-counts.json"

# The letters the Romanian respelling and the diacritic fold change, and change to.
FOLDED_LETTERS = (
    set(normalise_diacritics.CEDILLA) | set(normalise_diacritics.CEDILLA.values())
    | set(normalise_diacritics.TILDE) | set(normalise_diacritics.TILDE.values())
    | {chr(code) for code in fold_diacritic_noise.FOLD_TABLE}
)

# The marks a word's spellings are merged across (merge_apostrophes.py).
JOINED_BY = "'-"


@dataclass
class Manifest:
    """One manifest's corpus keys; a key the manifest lacks reads as empty."""
    path: Path
    tag: str
    corpora: list
    known_corpora: list
    case_corpora: list
    capitals: str | None
    heldout: str | None
    persona: str | None
    sha256: dict

    @property
    def counted(self) -> list:
        """Every corpus a list of the language is counted from, each once, in the order first
        named."""
        return once(self.corpora + self.known_corpora + self.case_corpora
                    + [name for name in [self.capitals] if name is not None])

    @property
    def named(self) -> list:
        """Every corpus the manifest names, each once."""
        return once(self.counted + [name for name in [self.heldout] if name is not None])


def once(names: list) -> list:
    """[names] without repeats, in the order first given."""
    kept = []
    for name in names:
        if name not in kept:
            kept.append(name)
    return kept


def manifest_path(tag: str, languages: Path = LANGUAGES) -> Path:
    """The manifest of [tag], written either way: en_US or en-US."""
    return languages / f"{tag.replace('-', '_')}.json"


def load(tag: str, languages: Path = LANGUAGES) -> Manifest:
    path = manifest_path(tag, languages)
    if not path.is_file():
        raise SystemExit(f"{path}: no manifest for {tag}")
    return parsed(path, json.loads(path.read_text(encoding="utf-8")))


def parsed(path: Path, data: dict) -> Manifest:
    """The manifest [data] holds, as read from [path]."""
    return Manifest(
        path=path,
        tag=data["tag"],
        corpora=list(data.get("corpora", [])),
        known_corpora=list(data.get("known_corpora", [])),
        case_corpora=list(data.get("case_corpora", [])),
        capitals=data.get("capitals"),
        heldout=data.get("heldout"),
        persona=data.get("persona"),
        sha256=dict(data.get("sha256", {})),
    )


def with_key(key: str, languages: Path = LANGUAGES) -> list:
    """The file tags of the manifests that hold [key], sorted."""
    tags = []
    for path in sorted(languages.glob("*.json")):
        if key in json.loads(path.read_text(encoding="utf-8")):
            tags.append(path.stem)
    return tags


def archive_name(corpus: str) -> str:
    return f"{corpus}.tar.gz"


def digest_of(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(CHUNK_BYTES):
            hasher.update(chunk)
    return hasher.hexdigest()


@contextlib.contextmanager
def locked(path: Path):
    """Holds an exclusive lock on the file beside [path] for the block."""
    lock = path.with_name(path.name + LOCK_SUFFIX)
    lock.parent.mkdir(parents=True, exist_ok=True)
    with lock.open("a") as handle:
        fcntl.flock(handle, fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(handle, fcntl.LOCK_UN)


@contextlib.contextmanager
def locked_directory(directory: Path):
    """Holds an exclusive lock on [directory] itself for the block."""
    handle = os.open(directory, os.O_RDONLY)
    try:
        fcntl.flock(handle, fcntl.LOCK_EX)
        yield
    finally:
        os.close(handle)


def replaced(target: Path, write) -> None:
    """Writes [target] through [write](file) into a file of its own beside it, renamed into place."""
    target.parent.mkdir(parents=True, exist_ok=True)
    handle = tempfile.NamedTemporaryFile(dir=target.parent, prefix=target.name + ".", delete=False)
    try:
        with handle:
            write(handle)
        os.replace(handle.name, target)
    except BaseException:
        Path(handle.name).unlink(missing_ok=True)
        raise


def download(url: str, target: Path) -> None:
    """[url] into [target], renamed into place only once complete."""
    print(f"fetching {url}", file=sys.stderr)
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=DOWNLOAD_TIMEOUT) as response:
        replaced(target, lambda out: shutil.copyfileobj(response, out, length=CHUNK_BYTES))


def update_manifest(manifest: Manifest, change) -> dict:
    """Applies [change](data) to [manifest]'s file under its lock, its other keys as they were,
    and drops the pins of the corpora it does not name; returns what it wrote."""
    with locked_directory(manifest.path.parent):
        data = json.loads(manifest.path.read_text(encoding="utf-8"))
        change(data)
        if "sha256" in data:
            named = {archive_name(name) for name in parsed(manifest.path, data).named}
            data["sha256"] = {name: pin for name, pin in data["sha256"].items() if name in named}
        text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
        replaced(manifest.path, lambda out: out.write(text.encode("utf-8")))
    return data


def record_pin(manifest: Manifest, name: str, digest: str) -> None:
    """Writes [digest] as [name]'s pin into [manifest]'s file, kept there when the manifest names
    the corpus."""
    def change(data):
        pins = data.get("sha256", {})
        pins[name] = digest
        data["sha256"] = dict(sorted(pins.items()))
    if name in update_manifest(manifest, change)["sha256"]:
        manifest.sha256[name] = digest
        print(f"{manifest.path.name}: pinned {name} {digest}", file=sys.stderr)


def extract(archive: Path, target: Path) -> None:
    """[archive]'s sentence file into [target], read to the end of the archive."""
    with tarfile.open(archive) as tar:
        member = next((m for m in tar.getmembers() if m.name.endswith(SENTENCES_SUFFIX)), None)
        if member is None:
            raise SystemExit(f"{archive} holds no sentences file")
        with tar.extractfile(member) as source:
            replaced(target, lambda out: shutil.copyfileobj(source, out, length=CHUNK_BYTES))


def sentence_file(corpus: str, cache: Path = CACHE) -> Path:
    return cache / f"{corpus}{SENTENCES_SUFFIX}"


def taken_out_under(target: Path) -> str | None:
    """The digest of the archive the sentence file [target] was taken out of, as recorded beside
    it, or None when either is missing."""
    verified = target.with_name(target.name + VERIFIED_SUFFIX)
    if not target.is_file() or not verified.is_file():
        return None
    return verified.read_text(encoding="utf-8").strip()


def sentences(manifest: Manifest, corpus: str, cache: Path = CACHE) -> Path:
    """[corpus]'s sentence file in [cache], taken out of its checked archive when the one there
    was not taken out under the pinned digest, or the archive's own when there is no pin."""
    name = archive_name(corpus)
    archive = cache / name
    target = sentence_file(corpus, cache)
    verified = target.with_name(target.name + VERIFIED_SUFFIX)
    with locked(archive):
        pinned = manifest.sha256.get(name)
        if pinned is not None and taken_out_under(target) == pinned:
            return target
        if not archive.is_file():
            download(LEIPZIG.format(name=corpus), archive)
        digest = digest_of(archive)
        if pinned is not None and digest != pinned:
            for path in (archive, target, verified):
                path.unlink(missing_ok=True)
            raise SystemExit(f"{archive}: SHA-256 {digest} is not the pinned {pinned}; deleted")
        if taken_out_under(target) != digest:
            try:
                extract(archive, target)
            except (tarfile.TarError, EOFError, OSError) as error:
                for path in (archive, target, verified):
                    path.unlink(missing_ok=True)
                raise SystemExit(f"{archive}: unreadable ({error}); deleted") from error
        if pinned is None:
            record_pin(manifest, name, digest)
        replaced(verified, lambda out: out.write((digest + "\n").encode("utf-8")))
    return target


def word_counts(manifest: Manifest, corpus: str, cache: Path = CACHE) -> dict:
    """How often [corpus]'s sentences write each word, as make_pack.count_words counts them, kept
    beside the sentence file for the digest of the archive it was taken out of."""
    path = sentences(manifest, corpus, cache)
    kept = path.with_name(f"{corpus}{COUNTS_SUFFIX}")
    digest = taken_out_under(path)
    if kept.is_file():
        data = json.loads(kept.read_text(encoding="utf-8"))
        if data.get("sha256") == digest:
            return data["counts"]
    counts = dict(make_pack.count_words([path]))
    text = json.dumps({"sha256": digest, "counts": counts}, ensure_ascii=False)
    replaced(kept, lambda out: out.write(text.encode("utf-8")))
    return counts


def list_rows(tag: str, dictionaries: Path = ROOT / "dictionaries") -> list:
    """The word list's compared rows, `(word, count)`, commonest first, up to COMPARED_ROWS."""
    rows = []
    path = dictionaries / f"{tag}.tsv"
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 2 or not parts[1].isdigit():
                continue
            if len(parts) >= 3 and parts[2] == "name":
                continue
            rows.append((parts[0], int(parts[1])))
    folded = {fold_diacritic_noise.strip_diacritics(word) for word, _ in rows
              if FOLDED_LETTERS & set(word)}
    kept = [(word, count) for word, count in rows
            if not FOLDED_LETTERS & set(word) and not set(JOINED_BY) & set(word)
            and word not in folded]
    kept.sort(key=lambda row: -row[1])
    return kept[:COMPARED_ROWS]


@dataclass
class SetFit:
    """How a set of corpora counts a list's compared rows."""
    corpora: list
    exact: int
    worst: float


def set_fits(rows: list, counted: dict) -> list:
    """Every subset of [counted]'s corpora, with how it counts [rows], closest first."""
    names = list(counted)
    fits = []
    for size in range(1, len(names) + 1):
        for subset in itertools.combinations(names, size):
            got = [sum(counted[name].get(word, 0) for name in subset) for word, _ in rows]
            fits.append(SetFit(
                corpora=list(subset),
                exact=sum(1 for value, (_, count) in zip(got, rows) if value == count),
                worst=max(abs(value - count) / count for value, (_, count) in zip(got, rows)),
            ))
    fits.sort(key=lambda fit: fit.worst)
    return fits


def list_corpora(manifest: Manifest, also: list, cache: Path = CACHE) -> list:
    """The fits of every subset of the corpora [manifest] names and [also], closest first."""
    return set_fits(list_rows(manifest.path.stem),
                    {name: word_counts(manifest, name, cache)
                     for name in once(manifest.named + also)})


def record_corpora(manifest: Manifest, corpora: list, cache: Path = CACHE) -> None:
    """Writes [corpora] as [manifest]'s word-list corpora, after its name when it has one, each
    pinned to the archive its sentences in [cache] were taken out of when it has no pin."""
    pins = {}
    for name in corpora:
        if archive_name(name) not in manifest.sha256:
            digest = taken_out_under(sentence_file(name, cache))
            if digest is None:
                raise SystemExit(f"{name}: no sentences taken out to pin it to")
            pins[archive_name(name)] = digest

    def change(data):
        ordered = {}
        for key, value in data.items():
            if key != "corpora":
                ordered[key] = value
            if key == "name":
                ordered["corpora"] = corpora
        ordered.setdefault("corpora", corpora)
        ordered["sha256"] = dict(sorted({**ordered.get("sha256", {}), **pins}.items()))
        data.clear()
        data.update(ordered)
    update_manifest(manifest, change)
    manifest.corpora = list(corpora)
    manifest.sha256.update(pins)


def selftest() -> int:
    """Checks a pin against a fake archive: recorded only after the archive reads to its end,
    kept when it matches, an unreadable first fetch pinned nowhere, a wrong pin deleting the
    archive and what was taken out of it, and stopping, a corpus the manifest does not name pinned
    only once recorded as the list's, and a pin no key names dropped; then the compared rows and
    the fits of a set of corpora."""
    with tempfile.TemporaryDirectory() as scratch:
        root = Path(scratch)
        languages = root / "languages"
        cache = root / "cache"
        languages.mkdir()
        cache.mkdir()
        corpus = "xxx_news_2000_1M"
        text = root / f"{corpus}{SENTENCES_SUFFIX}"
        text.write_text("1\tOne sentence.\n2\tAnother one.\n", encoding="utf-8")
        with tarfile.open(cache / archive_name(corpus), "w:gz") as tar:
            tar.add(text, arcname=f"{corpus}/{corpus}{SENTENCES_SUFFIX}")
        broken = "zzz_news_2000_1M"
        manifest_path("xx_XX", languages).write_text(
            json.dumps({"tag": "xx-XX", "corpora": [corpus], "known_corpora": [corpus],
                        "case_corpora": [broken], "capitals": "yyy_wiki_2000_1M",
                        "heldout": "www_news_2001_1M"}) + "\n", encoding="utf-8")

        manifest = load("xx_XX", languages)
        assert manifest.counted == [corpus, broken, "yyy_wiki_2000_1M"], manifest.counted
        assert manifest.named == manifest.counted + ["www_news_2001_1M"], manifest.named
        assert with_key("known_corpora", languages) == ["xx_XX"]
        assert with_key("persona", languages) == []

        extracted = sentences(manifest, corpus, cache)
        assert extracted.read_text(encoding="utf-8") == text.read_text(encoding="utf-8")
        pinned = load("xx_XX", languages).sha256[archive_name(corpus)]
        assert pinned == digest_of(cache / archive_name(corpus))

        extracted.unlink()
        assert sentences(load("xx_XX", languages), corpus, cache) == extracted
        assert extracted.is_file() and (cache / archive_name(corpus)).is_file()
        record = extracted.with_name(extracted.name + VERIFIED_SUFFIX)
        assert record.read_text(encoding="utf-8").strip() == pinned

        (cache / archive_name(broken)).write_bytes(b"not an archive")
        try:
            sentences(load("xx_XX", languages), broken, cache)
        except SystemExit:
            pass
        else:
            raise AssertionError("an unreadable archive was taken")
        assert archive_name(broken) not in load("xx_XX", languages).sha256
        assert not (cache / archive_name(broken)).exists()

        def wrong(data):
            data["sha256"][archive_name(corpus)] = "0" * len(pinned)
        update_manifest(load("xx_XX", languages), wrong)
        try:
            sentences(load("xx_XX", languages), corpus, cache)
        except SystemExit:
            pass
        else:
            raise AssertionError("a wrong pin was accepted")
        assert not (cache / archive_name(corpus)).exists()
        assert not extracted.exists() and not record.exists()

        unnamed = "uuu_news_2000_1M"
        with tarfile.open(cache / archive_name(unnamed), "w:gz") as tar:
            tar.add(text, arcname=f"{unnamed}/{unnamed}{SENTENCES_SUFFIX}")
        manifest = load("xx_XX", languages)
        taken = sentences(manifest, unnamed, cache)
        assert taken.read_text(encoding="utf-8") == text.read_text(encoding="utf-8")
        assert archive_name(unnamed) not in manifest.sha256
        assert archive_name(unnamed) not in load("xx_XX", languages).sha256
        counted = dict(make_pack.count_words([taken]))
        assert word_counts(load("xx_XX", languages), unnamed, cache) == counted
        kept = taken.with_name(f"{unnamed}{COUNTS_SUFFIX}")
        kept.write_text(json.dumps({"sha256": taken_out_under(taken), "counts": {"kept": 1}}),
                        encoding="utf-8")
        assert word_counts(load("xx_XX", languages), unnamed, cache) == {"kept": 1}
        kept.write_text(json.dumps({"sha256": "2" * len(pinned), "counts": {"kept": 1}}),
                        encoding="utf-8")
        assert word_counts(load("xx_XX", languages), unnamed, cache) == counted
        stale = "vvv_news_1999_1M"
        data = json.loads(manifest_path("xx_XX", languages).read_text(encoding="utf-8"))
        data["sha256"][archive_name(stale)] = "1" * len(pinned)
        manifest_path("xx_XX", languages).write_text(json.dumps(data) + "\n", encoding="utf-8")
        record_corpora(load("xx_XX", languages), [unnamed], cache)
        recorded = load("xx_XX", languages)
        assert recorded.corpora == [unnamed], recorded.corpora
        assert recorded.sha256[archive_name(unnamed)] == digest_of(cache / archive_name(unnamed))
        assert archive_name(stale) not in recorded.sha256, recorded.sha256
        assert recorded.sha256[archive_name(corpus)] == "0" * len(pinned)

        (root / "xx_XX.tsv").write_text(
            "of\t400\nthe\t1000\nname\t50\tname\ndon't\t45\nșir\t30\nsir\t20\nbe\t10\n",
            encoding="utf-8")
        rows = list_rows("xx_XX", root)
        assert rows == [("the", 1000), ("of", 400), ("be", 10)], rows
        rows = rows[:2]
        fits = set_fits(rows, {"a": {"the": 600, "of": 250}, "b": {"the": 401, "of": 150},
                               "c": {"the": 400, "of": 100}})
        assert fits[0].corpora == ["a", "b"] and fits[0].exact == 1, fits[0]
        assert [fit.corpora for fit in fits if fit.worst <= TOLERANCE] == [["a", "b"]]
    print("language_manifest selftest: ok")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--list-corpora", metavar="TAG", choices=with_key("known_corpora"))
    parser.add_argument("--also", nargs="+", default=[], metavar="CORPUS")
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    if args.selftest:
        return selftest()
    if args.list_corpora:
        manifest = load(args.list_corpora)
        fits = list_corpora(manifest, args.also)
        within = [fit for fit in fits if fit.worst <= TOLERANCE]
        for fit in fits[:max(len(within), 1) + 1]:
            print(f"{args.list_corpora}: {' + '.join(fit.corpora)}: {fit.exact} of {COMPARED_ROWS} "
                  f"rows exact, worst {fit.worst:.3%}")
        if len(within) != 1:
            print(f"{args.list_corpora}: {len(within)} sets within {TOLERANCE:.2%}; nothing found")
            return 1
        if args.write:
            record_corpora(manifest, within[0].corpora)
            print(f"{manifest.path.name}: corpora {', '.join(within[0].corpora)}")
        return 0
    parser.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
