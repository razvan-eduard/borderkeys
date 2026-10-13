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
corpus; a download shorter than the server announced is dropped. A pinned archive is checked
before its sentences are taken out: another digest deletes the archive and any sentence file taken
out before, and stops. Taking the sentences out reads the archive to its end, its gzip check
included; an archive that fails is deleted with what was taken out of it, and pinned nowhere. Only
then is a corpus with no pin pinned, when the manifest names it and holds no pin for it by then;
a pin it holds by then that differs deletes the archive and stops. A sentence file is read only
beside a record of the digest it was taken out under, matching the pin, or the archive when there
is none. Files are written through a file of their own, synced and renamed into place with the
mode the file had. A manifest is rewritten under the lock in its directory, only when it changes,
and keeps the pins of only the corpora it names.

A word list's `corpora` are found by count: every subset of the corpora the manifest names, and of
any more corpora given, is tried, the words counted as make_pack.py counts them, and a subset is
the list's when it is the only one that gives each of the list's commonest compared rows its count
within TOLERANCE. Left out of the comparison: names, rows of no count, words holding an apostrophe
and the words an apostrophe joins in the list, words holding a letter normalise_diacritics.py
respells or fold_diacritic_noise.py folds, and words that are the folded spelling of one of those.
A corpus's word counts are kept beside its sentence file, under the digest of the archive they
were counted from and of the code that counted them.

    python3 tools/language_manifest.py --selftest
    python3 tools/language_manifest.py --check                             # every manifest's pins
    python3 tools/language_manifest.py --list-corpora ro_RO                # report
    python3 tools/language_manifest.py --list-corpora ro_RO --also ron_news_2015_1M
    python3 tools/language_manifest.py --list-corpora en_US --write        # and record it
"""

import argparse
import contextlib
import dataclasses
import fcntl
import gzip
import hashlib
import io
import itertools
import json
import os
import shutil
import stat
import sys
import tarfile
import tempfile
import unicodedata
import urllib.request
import zlib
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fold_diacritic_noise  # noqa: E402
import make_pack  # noqa: E402
import normalise_diacritics  # noqa: E402

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
LANGUAGES = TOOLS / "languages"
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

# The lock in a manifests' directory that its manifests are rewritten under.
MANIFESTS_LOCK = ".lock"

# What a broken or cut archive raises while it is read.
UNREADABLE = (tarfile.TarError, EOFError, zlib.error, gzip.BadGzipFile)

# How many of a word list's commonest compared rows a set of corpora is checked against.
COMPARED_ROWS = 200

# How far, as a share of the list's count, a set's count of a compared row may be.
TOLERANCE = 0.0025

# The file ending of a corpus's word counts beside its sentence file.
COUNTS_SUFFIX = "-counts.json"

# The tools whose code makes the word counts.
COUNTER_SOURCES = ("make_pack.py", "build_dict.py")

# The letters the Romanian respelling changes and changes to, and the letters the diacritic fold
# changes.
FOLDED_LETTERS = (
    set(normalise_diacritics.CEDILLA) | set(normalise_diacritics.CEDILLA.values())
    | set(normalise_diacritics.TILDE) | set(normalise_diacritics.TILDE.values())
    | {chr(code) for code in fold_diacritic_noise.FOLD_TABLE}
)

# The apostrophe merge_apostrophes.py writes every apostrophe of a list as.
APOSTROPHE = "'"


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


def pin_problems(manifest: Manifest) -> list:
    """Each corpus [manifest] names with no pin, and each pin of a corpus it does not name."""
    named = {archive_name(name) for name in manifest.named}
    return ([f"{name}: named, not pinned" for name in sorted(named - set(manifest.sha256))]
            + [f"{name}: pinned, not named" for name in sorted(set(manifest.sha256) - named)])


@contextlib.contextmanager
def locked(lock: Path):
    """Holds an exclusive lock on the file [lock] for the block."""
    lock.parent.mkdir(parents=True, exist_ok=True)
    with lock.open("a") as handle:
        fcntl.flock(handle, fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(handle, fcntl.LOCK_UN)


def new_file_mode() -> int:
    """The mode a file created now gets: read and write for all, less the umask."""
    mask = os.umask(0)
    os.umask(mask)
    return 0o666 & ~mask


def replaced(target: Path, write) -> None:
    """Writes [target] through [write](file) into a file of its own beside it, synced, and renamed
    into place with the mode [target] had, or a new file's."""
    target.parent.mkdir(parents=True, exist_ok=True)
    mode = stat.S_IMODE(target.stat().st_mode) if target.exists() else new_file_mode()
    handle = tempfile.NamedTemporaryFile(dir=target.parent, prefix=target.name + ".", delete=False)
    try:
        with handle:
            write(handle)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(handle.name, mode)
        os.replace(handle.name, target)
    except BaseException:
        Path(handle.name).unlink(missing_ok=True)
        raise
    directory = os.open(target.parent, os.O_RDONLY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def download(url: str, target: Path) -> None:
    """[url] into [target], renamed into place only once as long as the server announced."""
    print(f"fetching {url}", file=sys.stderr)
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=DOWNLOAD_TIMEOUT) as response:
        announced = response.headers.get("Content-Length")

        def write(out):
            shutil.copyfileobj(response, out, length=CHUNK_BYTES)
            if announced is not None and out.tell() != int(announced):
                raise SystemExit(f"{url}: {out.tell()} bytes of the {announced} announced")
        replaced(target, write)


def update_manifest(manifest: Manifest, change) -> None:
    """Applies [change](data) to [manifest]'s file under the manifests' lock, its other keys as
    they were, drops the pins of the corpora it does not name, writes it when that changes it, and
    leaves [manifest] as the file is."""
    with locked(manifest.path.parent / MANIFESTS_LOCK):
        before = manifest.path.read_text(encoding="utf-8")
        data = json.loads(before)
        had_pins = "sha256" in data
        change(data)
        if "sha256" in data:
            named = {archive_name(name) for name in parsed(manifest.path, data).named}
            data["sha256"] = {name: pin for name, pin in data["sha256"].items() if name in named}
            if not data["sha256"] and not had_pins:
                del data["sha256"]
        text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
        if text != before:
            replaced(manifest.path, lambda out: out.write(text.encode("utf-8")))
    written = parsed(manifest.path, data)
    for field in dataclasses.fields(Manifest):
        setattr(manifest, field.name, getattr(written, field.name))


def record_pin(manifest: Manifest, name: str, digest: str) -> str | None:
    """Writes [digest] as [name]'s pin into [manifest]'s file unless the file holds one, and
    returns the pin the file holds then: None when the manifest does not name the corpus."""
    def change(data):
        pins = data.get("sha256", {})
        pins.setdefault(name, digest)
        data["sha256"] = dict(sorted(pins.items()))
    update_manifest(manifest, change)
    held = manifest.sha256.get(name)
    if held == digest:
        print(f"{manifest.path.name}: pinned {name} {digest}", file=sys.stderr)
    return held


def extract(archive: Path, target: Path) -> None:
    """[archive]'s sentence file into [target], then the rest of the archive read to its end."""
    with tarfile.open(archive) as tar:
        member = next((m for m in tar.getmembers() if m.name.endswith(SENTENCES_SUFFIX)), None)
        if member is None:
            raise SystemExit(f"{archive} holds no sentences file")
        with tar.extractfile(member) as source:
            replaced(target, lambda out: shutil.copyfileobj(source, out, length=CHUNK_BYTES))
        while tar.fileobj.read(CHUNK_BYTES):
            pass


def sentence_file(corpus: str, cache: Path = CACHE) -> Path:
    return cache / f"{corpus}{SENTENCES_SUFFIX}"


def record_of(target: Path) -> Path:
    """The record beside the sentence file [target]."""
    return target.with_name(target.name + VERIFIED_SUFFIX)


def taken_out_under(target: Path) -> str | None:
    """The digest of the archive the sentence file [target] was taken out of, as recorded beside
    it, or None when either is missing."""
    if not target.is_file() or not record_of(target).is_file():
        return None
    return record_of(target).read_text(encoding="utf-8").strip()


def discard(*paths: Path) -> None:
    for path in paths:
        path.unlink(missing_ok=True)


def sentences(manifest: Manifest, corpus: str, cache: Path = CACHE) -> Path:
    """[corpus]'s sentence file in [cache], taken out of its checked archive when the one there
    was not taken out under the pinned digest, or the archive's own when there is no pin."""
    name = archive_name(corpus)
    archive = cache / name
    target = sentence_file(corpus, cache)
    verified = record_of(target)
    with locked(archive.with_name(name + LOCK_SUFFIX)):
        pinned = manifest.sha256.get(name)
        if pinned is not None and taken_out_under(target) == pinned:
            return target
        if not archive.is_file():
            download(LEIPZIG.format(name=corpus), archive)
        digest = digest_of(archive)
        if pinned is not None and digest != pinned:
            discard(archive, target, verified)
            raise SystemExit(f"{archive}: SHA-256 {digest} is not the pinned {pinned}; deleted")
        if taken_out_under(target) != digest:
            verified.unlink(missing_ok=True)
            try:
                extract(archive, target)
            except UNREADABLE as error:
                discard(archive, target, verified)
                raise SystemExit(f"{archive}: unreadable ({error}); deleted") from error
            replaced(verified, lambda out: out.write((digest + "\n").encode("utf-8")))
        if pinned is None:
            held = record_pin(manifest, name, digest)
            if held is not None and held != digest:
                discard(archive, target, verified)
                raise SystemExit(f"{archive}: SHA-256 {digest} is not the pinned {held}; deleted")
    return target


def counter_digest() -> str:
    """A digest of the code the word counts are made by, and of the Unicode tables it reads."""
    hasher = hashlib.sha256(unicodedata.unidata_version.encode("utf-8"))
    for name in COUNTER_SOURCES:
        hasher.update(digest_of(TOOLS / name).encode("utf-8"))
    return hasher.hexdigest()


def word_counts(manifest: Manifest, corpus: str, cache: Path = CACHE) -> tuple[str, dict]:
    """The digest of the archive [corpus]'s sentences were taken out of, and how often they write
    each word, as make_pack.count_words counts them, kept beside the sentence file for that
    archive and that code."""
    path = sentences(manifest, corpus, cache)
    kept = path.with_name(f"{corpus}{COUNTS_SUFFIX}")
    key = {"sha256": taken_out_under(path), "counter": counter_digest()}
    if key["sha256"] is not None and kept.is_file():
        data = json.loads(kept.read_text(encoding="utf-8"))
        if {name: data.get(name) for name in key} == key:
            return key["sha256"], data["counts"]
    counts = dict(make_pack.count_words([path]))
    if key["sha256"] is None or taken_out_under(path) != key["sha256"]:
        raise SystemExit(f"{path}: taken out again while it was counted; run again")
    text = json.dumps({**key, "counts": counts}, ensure_ascii=False)
    replaced(kept, lambda out: out.write(text.encode("utf-8")))
    return key["sha256"], counts


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
    joined = {part for word, _ in rows if APOSTROPHE in word for part in word.split(APOSTROPHE)}
    folded = {fold_diacritic_noise.strip_diacritics(word) for word, _ in rows
              if FOLDED_LETTERS & set(word)}
    kept = [(word, count) for word, count in rows
            if count > 0 and APOSTROPHE not in word and word not in joined
            and not FOLDED_LETTERS & set(word) and word not in folded]
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


def found_set(fits: list) -> tuple[SetFit | None, int]:
    """The fit within TOLERANCE on every row when it is the only one, else None; and how many
    are."""
    within = [fit for fit in fits if fit.worst <= TOLERANCE]
    return (within[0] if len(within) == 1 else None), len(within)


def candidates(manifest: Manifest, also: list) -> list:
    """The corpora a word list's set is sought among: those [manifest] names, then [also]."""
    return once(manifest.named + also)


def list_corpora(manifest: Manifest, also: list, cache: Path = CACHE) -> tuple[list, dict]:
    """The fits of every subset of the candidates, closest first, and the digest of the archive
    each candidate's counts came from."""
    rows = list_rows(manifest.path.stem)
    if not rows:
        raise SystemExit(f"{manifest.path.stem}: the word list has no compared rows")
    digests = {}
    counted = {}
    for name in candidates(manifest, also):
        digests[name], counted[name] = word_counts(manifest, name, cache)
    return set_fits(rows, counted), digests


def record_corpora(manifest: Manifest, corpora: list, digests: dict) -> None:
    """Writes [corpora] as [manifest]'s word-list corpora, after its name when it has one, each
    pinned to the digest its counts came from, by corpus in [digests], where the file has no pin;
    a pin the file holds that differs stops it."""
    def change(data):
        pins = dict(data.get("sha256", {}))
        for name in corpora:
            held = pins.setdefault(archive_name(name), digests[name])
            if held != digests[name]:
                raise SystemExit(f"{name}: counted from {digests[name]}, pinned to {held}")
        ordered = {}
        for key, value in data.items():
            if key != "corpora":
                ordered[key] = value
            if key == "name":
                ordered["corpora"] = corpora
        ordered.setdefault("corpora", corpora)
        ordered["sha256"] = dict(sorted(pins.items()))
        data.clear()
        data.update(ordered)
    update_manifest(manifest, change)


def selftest() -> int:
    """Checks, against fake archives: a first fetch pinned only after the archive reads to its end,
    a broken or cut one deleted with what was taken out of it and pinned nowhere, a matching pin
    kept and a wrong one deleting the archive and what was taken out of it, a pin another holder
    wrote meanwhile winning over the fetch, a corpus the manifest does not name taken out, reused
    under its record and pinned nowhere, a record dropped before its sentences are replaced, the
    word counts kept for their archive and their code, a manifest written only when it changes and
    with its mode kept, a pin no key names dropped, the recorded corpora pinned to the digests
    they were counted from, and a download shorter than announced dropped; then the candidates,
    the compared rows, the fits and the one set within the tolerance."""
    with tempfile.TemporaryDirectory() as scratch:
        root = Path(scratch)
        languages = root / "languages"
        cache = root / "cache"
        languages.mkdir()
        cache.mkdir()
        text = "1\tOne sentence.\n2\tAnother one.\n"

        def archive(corpus, member=SENTENCES_SUFFIX):
            source = root / f"{corpus}{member}"
            source.write_text(text, encoding="utf-8")
            built = cache / archive_name(corpus)
            with tarfile.open(built, "w:gz") as tar:
                tar.add(source, arcname=f"{corpus}/{corpus}{member}")
            return built

        def refused(call, what):
            try:
                with contextlib.redirect_stderr(io.StringIO()):
                    call()
            except SystemExit:
                return
            raise AssertionError(what)

        corpus = "xxx_news_2000_1M"
        broken = "zzz_news_2000_1M"
        cut = "ccc_news_2000_1M"
        capitals = "yyy_wiki_2000_1M"
        heldout = "www_news_2001_1M"
        path = manifest_path("xx_XX", languages)
        path.write_text(json.dumps({"tag": "xx-XX", "name": "X", "corpora": [corpus],
                                    "known_corpora": [corpus], "case_corpora": [broken, cut],
                                    "capitals": capitals, "heldout": heldout}) + "\n",
                        encoding="utf-8")
        os.chmod(path, 0o640)

        manifest = load("xx_XX", languages)
        assert manifest.counted == [corpus, broken, cut, capitals], manifest.counted
        assert manifest.named == manifest.counted + [heldout], manifest.named
        assert with_key("known_corpora", languages) == ["xx_XX"]
        assert with_key("persona", languages) == []
        assert candidates(manifest, [heldout, "uuu", corpus]) == manifest.named + ["uuu"]
        assert pin_problems(manifest) == [f"{name}: named, not pinned" for name in
                                          sorted(archive_name(name) for name in manifest.named)]

        archive(corpus)
        shown = io.StringIO()
        with contextlib.redirect_stderr(shown):
            extracted = sentences(manifest, corpus, cache)
        assert extracted.read_text(encoding="utf-8") == text
        pinned = digest_of(cache / archive_name(corpus))
        assert load("xx_XX", languages).sha256[archive_name(corpus)] == pinned
        assert manifest.sha256[archive_name(corpus)] == pinned
        assert f"pinned {archive_name(corpus)}" in shown.getvalue()
        assert stat.S_IMODE(path.stat().st_mode) == 0o640
        assert stat.S_IMODE(extracted.stat().st_mode) == new_file_mode()

        extracted.unlink()
        assert sentences(load("xx_XX", languages), corpus, cache) == extracted
        assert extracted.is_file() and (cache / archive_name(corpus)).is_file()
        record = record_of(extracted)
        assert record.read_text(encoding="utf-8").strip() == pinned

        whole = archive(cut).read_bytes()
        for name, data in ((broken, b"not an archive"), (cut, whole[:-4])):
            (cache / archive_name(name)).write_bytes(data)
            refused(lambda: sentences(load("xx_XX", languages), name, cache),
                    f"{name}: an unreadable archive was taken")
            assert archive_name(name) not in load("xx_XX", languages).sha256
            assert not (cache / archive_name(name)).exists()
            assert not sentence_file(name, cache).exists()

        def wrong(data):
            data["sha256"][archive_name(corpus)] = "0" * len(pinned)
        update_manifest(load("xx_XX", languages), wrong)
        refused(lambda: sentences(load("xx_XX", languages), corpus, cache),
                "a wrong pin was accepted")
        assert not (cache / archive_name(corpus)).exists()
        assert not extracted.exists() and not record.exists()

        held = load("xx_XX", languages)
        archive(heldout)

        def meanwhile(data):
            data["sha256"][archive_name(heldout)] = "3" * len(pinned)
        update_manifest(load("xx_XX", languages), meanwhile)
        refused(lambda: sentences(held, heldout, cache), "a pin written meanwhile was overwritten")
        assert load("xx_XX", languages).sha256[archive_name(heldout)] == "3" * len(pinned)
        assert not (cache / archive_name(heldout)).exists()
        assert not sentence_file(heldout, cache).exists()

        unnamed = "uuu_news_2000_1M"
        archive(unnamed)
        before = path.stat().st_ino
        manifest = load("xx_XX", languages)
        shown = io.StringIO()
        with contextlib.redirect_stderr(shown):
            taken = sentences(manifest, unnamed, cache)
        assert taken.read_text(encoding="utf-8") == text
        assert archive_name(unnamed) not in manifest.sha256
        assert archive_name(unnamed) not in load("xx_XX", languages).sha256
        assert "pinned" not in shown.getvalue(), shown.getvalue()
        assert path.stat().st_ino == before
        taken.write_text("changed\n", encoding="utf-8")
        assert sentences(load("xx_XX", languages), unnamed, cache).read_text(
            encoding="utf-8") == "changed\n"
        record_of(taken).unlink()
        assert sentences(load("xx_XX", languages), unnamed, cache).read_text(
            encoding="utf-8") == text
        archive(unnamed, member="-words.txt")
        refused(lambda: sentences(load("xx_XX", languages), unnamed, cache),
                "an archive with no sentences was taken")
        assert not record_of(taken).exists()
        archive(unnamed)
        sentences(load("xx_XX", languages), unnamed, cache)

        counted = dict(make_pack.count_words([taken]))
        digest = digest_of(cache / archive_name(unnamed))
        assert word_counts(load("xx_XX", languages), unnamed, cache) == (digest, counted)
        kept = taken.with_name(f"{unnamed}{COUNTS_SUFFIX}")
        for key, used in (({"sha256": digest, "counter": counter_digest()}, True),
                          ({"sha256": "2" * len(pinned), "counter": counter_digest()}, False),
                          ({"sha256": digest, "counter": "2" * len(pinned)}, False)):
            kept.write_text(json.dumps({**key, "counts": {"kept": 1}}), encoding="utf-8")
            got = word_counts(load("xx_XX", languages), unnamed, cache)[1]
            assert got == ({"kept": 1} if used else counted), (key, got)

        stale = "vvv_news_1999_1M"
        data = json.loads(path.read_text(encoding="utf-8"))
        data["sha256"][archive_name(stale)] = "1" * len(pinned)
        data["sha256"][archive_name(unnamed)] = digest
        path.write_text(json.dumps(data) + "\n", encoding="utf-8")
        assert f"{archive_name(stale)}: pinned, not named" in pin_problems(
            load("xx_XX", languages))
        manifest = load("xx_XX", languages)
        elsewhere = load("xx_XX", languages)
        update_manifest(elsewhere, lambda data: None)
        assert archive_name(unnamed) not in elsewhere.sha256, elsewhere.sha256
        assert archive_name(stale) not in elsewhere.sha256, elsewhere.sha256
        before = path.read_text(encoding="utf-8")
        refused(lambda: record_corpora(load("xx_XX", languages), [corpus], {corpus: pinned}),
                "corpora counted from another archive than the pinned were recorded")
        assert path.read_text(encoding="utf-8") == before
        record_corpora(manifest, [unnamed], {unnamed: digest})
        assert manifest.corpora == [unnamed], manifest.corpora
        recorded = load("xx_XX", languages)
        assert recorded.corpora == [unnamed], recorded.corpora
        assert list(json.loads(path.read_text(encoding="utf-8")))[:3] == ["tag", "name", "corpora"]
        assert recorded.sha256[archive_name(unnamed)] == digest
        assert archive_name(stale) not in recorded.sha256, recorded.sha256
        assert recorded.sha256[archive_name(corpus)] == "0" * len(pinned)
        assert pin_problems(recorded) == [f"{name}: named, not pinned" for name in
                                          sorted(archive_name(name)
                                                 for name in (broken, cut, capitals))]

        manifest_path("yy_YY", languages).write_text(json.dumps({"tag": "yy-YY"}) + "\n",
                                                     encoding="utf-8")
        sentences(load("yy_YY", languages), unnamed, cache)
        assert "sha256" not in json.loads(manifest_path("yy_YY", languages).read_text(
            encoding="utf-8"))

        class Response(io.BytesIO):
            headers = {"Content-Length": "10"}

        opened = urllib.request.urlopen
        try:
            urllib.request.urlopen = lambda request, timeout: Response(b"short")
            refused(lambda: download(LEIPZIG.format(name=corpus), root / "short.tar.gz"),
                    "a download shorter than announced was kept")
            assert not (root / "short.tar.gz").exists()
            urllib.request.urlopen = lambda request, timeout: Response(b"0123456789")
            with contextlib.redirect_stderr(io.StringIO()):
                download(LEIPZIG.format(name=corpus), root / "whole.tar.gz")
            assert (root / "whole.tar.gz").read_bytes() == b"0123456789"
        finally:
            urllib.request.urlopen = opened

        (root / "xx_XX.tsv").write_text(
            "of\t400\nthe\t1000\nname\t50\tname\ndon't\t45\ndon\t44\nșir\t30\nsir\t20\n"
            "well-known\t15\nnone\t0\nbe\t10\n", encoding="utf-8")
        rows = list_rows("xx_XX", root)
        assert rows == [("the", 1000), ("of", 400), ("well-known", 15), ("be", 10)], rows
        (root / "yy_YY.tsv").write_text(
            "".join(f"w{n}\t{n + 1}\n" for n in range(COMPARED_ROWS + 5)), encoding="utf-8")
        many = list_rows("yy_YY", root)
        assert len(many) == COMPARED_ROWS and many[-1] == ("w5", 6), many[-1]

        fits = set_fits(rows[:2], {"a": {"the": 600, "of": 250}, "b": {"the": 401, "of": 150},
                                   "c": {"the": 400, "of": 100}})
        assert fits[0].corpora == ["a", "b"] and fits[0].exact == 1, fits[0]
        assert found_set(fits) == (fits[0], 1)
        edge = SetFit(["e"], 0, TOLERANCE)
        assert found_set([edge]) == (edge, 1)
        assert found_set([SetFit(["f"], 0, 0.0), edge]) == (None, 2)
        assert found_set([SetFit(["g"], 0, TOLERANCE * 1.01)]) == (None, 0)
    print("language_manifest selftest: ok")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--list-corpora", metavar="TAG", choices=with_key("known_corpora"))
    parser.add_argument("--also", nargs="+", default=[], metavar="CORPUS")
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    if args.selftest:
        return selftest()
    if args.check:
        problems = [f"{tag}: {problem}" for tag in with_key("sha256")
                    for problem in pin_problems(load(tag))]
        for problem in problems:
            print(problem)
        return 1 if problems else 0
    if args.list_corpora:
        manifest = load(args.list_corpora)
        fits, digests = list_corpora(manifest, args.also)
        found, within = found_set(fits)
        for fit in fits[:within + 1]:
            print(f"{args.list_corpora}: {' + '.join(fit.corpora)}: {fit.exact} of {COMPARED_ROWS} "
                  f"rows exact, worst {fit.worst:.3%}")
        if found is None:
            print(f"{args.list_corpora}: {within} sets within {TOLERANCE:.2%}; nothing found")
            return 1
        if args.write:
            record_corpora(manifest, found.corpora, digests)
            print(f"{manifest.path.name}: corpora {', '.join(found.corpora)}")
        return 0
    parser.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
