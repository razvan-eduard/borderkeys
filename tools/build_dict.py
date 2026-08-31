#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Compiles a word list and an n-gram list into a .bkd language pack.

Standard library only, deliberately: this runs in CI, on a maintainer's laptop, and one day
inside the settings process on a phone through the same native code. A dependency here would be
a dependency the project cannot actually check.

The output format is defined by keyboard/src/main/cpp/bkd_format.hpp. That header is the
authority; this file has to agree with it, and --selftest is what proves it still does.

Input
-----
  --words FILE     one "word<TAB>frequency" per line, UTF-8
  --ngrams FILE    optional, tab separated, last field is the count and the ones before it are
                   the words: "de<TAB>la<TAB>1200" is a bigram, "nu<TAB>am<TAB>timp<TAB>90" a
                   trigram. Both may be mixed in one file.
  --tag            BCP-47 tag written into the header, e.g. ro-RO
  --out            destination .bkd

Usage
-----
  ./build_dict.py --words ro.tsv --ngrams ro.ngrams --tag ro-RO \\
                  --out keyboard/src/main/assets/dict/ro_RO.bkd
  ./build_dict.py --selftest
"""

from __future__ import annotations

import argparse
import math
import random
import struct
import sys
import tempfile
import zlib
from pathlib import Path

# --------------------------------------------------------------------------------------
# Format constants. Every one of these has a counterpart in bkd_format.hpp.
# --------------------------------------------------------------------------------------

MAGIC = 0x31444B42  # 'B' 'K' 'D' '1' little endian
VERSION = 1
HEADER_BYTES = 256
MAX_PACK_BYTES = 64 * 1024 * 1024
MAX_WORDS = 4_000_000
MAX_NODES = 32_000_000
MAX_ALPHABET = 1024
MAX_NGRAM_CAPACITY = 1 << 26

FLAG_CASE_FOLDED = 1 << 0
FLAG_CONTENT_CRC = 1 << 1

SECTION_COUNT = 10
(
    S_ALPHABET,
    S_TRIE_BASE,
    S_TRIE_CHECK,
    S_WORD_OFFSETS,
    S_WORD_FREQ,
    S_WORD_TEXT,
    S_BIGRAM_KEYS,
    S_BIGRAM_VALUES,
    S_TRIGRAM_KEYS,
    S_TRIGRAM_VALUES,
) = range(SECTION_COUNT)

# Quantisation scale for log-probabilities: q = round(-logProb * SCALE), saturating at 255,
# which puts the floor at about -25.5 nats. Anything less likely than that is not going to be
# ranked into a three-slot suggestion strip by a difference this coarse.
LOG_PROB_SCALE = 10

# Hash tables are sized so the load factor stays under this. Linear probing degrades sharply
# past roughly 0.7, and the table is looked up several times per candidate.
NGRAM_LOAD_FACTOR = 0.6

TERMINAL_SYMBOL = 0

# --------------------------------------------------------------------------------------
# Character folding.
#
# This MUST agree with foldCodePoint() in proximity.cpp, character for character. The trie is
# indexed on folded forms, so a disagreement does not produce a warning -- it produces a pack
# whose words the engine can never reach, for exactly the characters the two implementations
# disagree about, which on a Romanian keyboard means the interesting ones.
#
# The duplication is deliberate rather than generated: a code generator would be a third thing
# to keep correct. --selftest --dump-folds prints this table so the native tests can diff it.
# --------------------------------------------------------------------------------------

_LATIN1_FOLD = {
    0xE0: "a", 0xE1: "a", 0xE2: "a", 0xE3: "a", 0xE4: "a", 0xE5: "a",
    0xE7: "c",
    0xE8: "e", 0xE9: "e", 0xEA: "e", 0xEB: "e",
    0xEC: "i", 0xED: "i", 0xEE: "i", 0xEF: "i",
    0xF1: "n",
    0xF2: "o", 0xF3: "o", 0xF4: "o", 0xF5: "o", 0xF6: "o", 0xF8: "o",
    0xF9: "u", 0xFA: "u", 0xFB: "u", 0xFC: "u",
    0xFD: "y", 0xFF: "y",
}

_LATIN_EXT_A_FOLD = {
    0x101: "a", 0x103: "a", 0x105: "a",
    0x107: "c", 0x109: "c", 0x10B: "c", 0x10D: "c",
    0x10F: "d", 0x111: "d",
    0x113: "e", 0x115: "e", 0x117: "e", 0x119: "e", 0x11B: "e",
    0x11D: "g", 0x11F: "g", 0x121: "g", 0x123: "g",
    0x125: "h", 0x127: "h",
    0x129: "i", 0x12B: "i", 0x12D: "i", 0x12F: "i", 0x131: "i",
    0x135: "j",
    0x137: "k", 0x138: "k",
    0x13A: "l", 0x13C: "l", 0x13E: "l", 0x140: "l", 0x142: "l",
    0x144: "n", 0x146: "n", 0x148: "n", 0x149: "n", 0x14B: "n",
    0x14D: "o", 0x14F: "o", 0x151: "o",
    0x155: "r", 0x157: "r", 0x159: "r",
    0x15B: "s", 0x15D: "s", 0x15F: "s", 0x161: "s",
    0x163: "t", 0x165: "t", 0x167: "t",
    0x169: "u", 0x16B: "u", 0x16D: "u", 0x16F: "u", 0x171: "u", 0x173: "u",
    0x175: "w",
    0x177: "y", 0x178: "y",
    0x17A: "z", 0x17C: "z", 0x17E: "z",
}


def fold_code_point(code_point: int) -> int:
    """Lowercase and strip the diacritic. Mirrors foldCodePoint() in proximity.cpp."""
    if code_point < 128:
        if ord("A") <= code_point <= ord("Z"):
            return code_point + 32
        return code_point

    if 0xC0 <= code_point <= 0xDE and code_point != 0xD7:
        code_point += 0x20
    if code_point in _LATIN1_FOLD:
        return ord(_LATIN1_FOLD[code_point])

    if 0x100 <= code_point <= 0x17F:
        lower = code_point
        if lower < 0x178 and (lower & 1) == 0:
            lower += 1
        if lower in _LATIN_EXT_A_FOLD:
            return ord(_LATIN_EXT_A_FOLD[lower])
        return lower

    # The comma-below forms Romanian standardised on, which are a different block from the
    # cedilla forms above and are the ones a correct Romanian keyboard produces.
    if code_point in (0x218, 0x219):
        return ord("s")
    if code_point in (0x21A, 0x21B):
        return ord("t")

    return code_point


def fold_word(word: str) -> tuple[int, ...]:
    return tuple(fold_code_point(ord(character)) for character in word)


# --------------------------------------------------------------------------------------
# Hashing. Must match NgramModel::mix() in ngram_model.hpp bit for bit.
# --------------------------------------------------------------------------------------

_U64 = (1 << 64) - 1


def mix64(value: int) -> int:
    value = (value + 0x9E3779B97F4A7C15) & _U64
    value = ((value ^ (value >> 30)) * 0xBF58476D1CE4E5B9) & _U64
    value = ((value ^ (value >> 27)) * 0x94D049BB133111EB) & _U64
    return (value ^ (value >> 31)) & _U64


def next_power_of_two(value: int) -> int:
    result = 1
    while result < value:
        result <<= 1
    return result


# --------------------------------------------------------------------------------------
# Double-array trie construction.
# --------------------------------------------------------------------------------------


class DoubleArrayBuilder:
    """Packs a plain trie into base/check arrays.

    The classic construction: walk the plain trie breadth first, and for each node find a base
    offset such that every one of its children lands on a slot nobody else has taken. Slot reuse
    across nodes is the whole point -- it is why the two arrays stay close to the number of
    transitions rather than growing as nodes times alphabet.

    The free-slot cursor only ever moves forward. Rescanning from zero for every node turns
    construction quadratic on a real dictionary, which is the difference between a build step
    and a build problem.
    """

    def __init__(self) -> None:
        self.base: list[int] = [0]
        self.check: list[int] = [-1]
        self.used: bytearray = bytearray([1])
        self.first_free = 1

    def _ensure(self, index: int) -> None:
        while len(self.base) <= index:
            self.base.append(0)
            self.check.append(-2)  # -2: never assigned; -1 is reserved for "root has no parent"
            self.used.append(0)

    def _is_free(self, index: int) -> bool:
        if index < 0:
            return False
        if index >= len(self.used):
            return True
        return self.used[index] == 0

    def _advance_first_free(self) -> None:
        while self.first_free < len(self.used) and self.used[self.first_free] != 0:
            self.first_free += 1

    def find_base(self, symbols: list[int]) -> int:
        self._advance_first_free()
        smallest = symbols[0]
        candidate_slot = self.first_free
        while True:
            base = candidate_slot - smallest
            # Slot 0 is the root and base must not send a child there, nor to a negative index.
            if base >= 0 and all(
                (base + symbol) > 0 and self._is_free(base + symbol) for symbol in symbols
            ):
                return base
            candidate_slot += 1
            while candidate_slot < len(self.used) and self.used[candidate_slot] != 0:
                candidate_slot += 1

    def occupy(self, parent: int, base: int, symbols: list[int]) -> dict[int, int]:
        placed: dict[int, int] = {}
        self.base[parent] = base
        for symbol in symbols:
            index = base + symbol
            self._ensure(index)
            self.used[index] = 1
            self.check[index] = parent
            placed[symbol] = index
        self._advance_first_free()
        return placed


def build_double_array(words_folded: list[tuple[int, ...]], symbol_of: dict[int, int]):
    """Returns (base, check) with terminals encoding -(wordIndex + 1) in base."""
    # Plain trie first, as nested dicts. Memory-hungry but simple, and this is a build tool.
    root: dict = {}
    for word_index, folded in enumerate(words_folded):
        node = root
        for code_point in folded:
            symbol = symbol_of[code_point]
            node = node.setdefault(symbol, {})
        node[TERMINAL_SYMBOL] = word_index  # int payload rather than a dict marks a terminal

    builder = DoubleArrayBuilder()
    queue: list[tuple[dict, int]] = [(root, 0)]
    while queue:
        plain, dat_index = queue.pop(0)
        symbols = sorted(plain.keys())
        if not symbols:
            builder.base[dat_index] = 0
            continue
        base = builder.find_base(symbols)
        placed = builder.occupy(dat_index, base, symbols)
        for symbol in symbols:
            child = plain[symbol]
            child_index = placed[symbol]
            if symbol == TERMINAL_SYMBOL:
                # A terminal has no children, so its base slot carries the word index instead,
                # negated and offset by one so that word 0 is not an unset zero.
                builder.base[child_index] = -(child + 1)
            else:
                queue.append((child, child_index))
    return builder.base, builder.check


# --------------------------------------------------------------------------------------
# Pack assembly.
# --------------------------------------------------------------------------------------


def quantise_log_prob(probability: float) -> int:
    if probability <= 0.0:
        return 255
    value = int(round(-math.log(probability) * LOG_PROB_SCALE))
    return max(0, min(255, value))


def build_hash_table(entries: dict, key_words: int):
    """Builds an open-addressed table. `entries` maps a tuple of ids to a quantised value."""
    if not entries:
        return 0, b"", b""
    capacity = next_power_of_two(max(8, int(len(entries) / NGRAM_LOAD_FACTOR) + 1))
    if capacity > MAX_NGRAM_CAPACITY:
        raise SystemExit(f"n-gram table would need {capacity} slots, cap is {MAX_NGRAM_CAPACITY}")
    mask = capacity - 1

    if key_words == 2:
        keys = [0] * capacity
        values = bytearray(capacity)
        for (first, second), quantised in entries.items():
            key = ((first + 1) << 32) | (second + 1)
            slot = mix64(key) & 0xFFFFFFFF & mask
            while keys[slot] != 0:
                slot = (slot + 1) & mask
            keys[slot] = key
            values[slot] = quantised
        return capacity, struct.pack(f"<{capacity}Q", *keys), bytes(values)

    keys = [0] * (capacity * 3)
    values = bytearray(capacity)
    for (first, second, third), quantised in entries.items():
        a, b, c = first + 1, second + 1, third + 1
        slot = mix64(((a << 40) ^ (b << 20) ^ c) & _U64) & 0xFFFFFFFF & mask
        while keys[slot * 3] != 0:
            slot = (slot + 1) & mask
        keys[slot * 3] = a
        keys[slot * 3 + 1] = b
        keys[slot * 3 + 2] = c
        values[slot] = quantised
    return capacity, struct.pack(f"<{capacity * 3}I", *keys), bytes(values)


def align_up(value: int, alignment: int) -> int:
    return (value + alignment - 1) & ~(alignment - 1)


def build_pack(tag: str, words: list[tuple[str, int]], ngrams: dict) -> bytes:
    if not words:
        raise SystemExit("the word list is empty")
    if len(words) > MAX_WORDS:
        raise SystemExit(f"{len(words)} words exceeds the format cap of {MAX_WORDS}")
    if len(tag.encode("utf-8")) > 15:
        raise SystemExit("the language tag must fit in 15 bytes plus a terminator")

    # Sorting by folded key makes the output a deterministic function of its input, which is
    # what lets the same sources rebuild byte-identically -- the same property the APK needs.
    prepared = sorted(((fold_word(word), word, frequency) for word, frequency in words),
                      key=lambda item: (item[0], item[1]))

    # A folded key that two different spellings share keeps the more frequent spelling as its
    # display form; the other would be unreachable anyway, since the trie is keyed on the fold.
    deduped: dict[tuple[int, ...], tuple[str, int]] = {}
    for folded, word, frequency in prepared:
        existing = deduped.get(folded)
        if existing is None or frequency > existing[1]:
            deduped[folded] = (word, frequency)
    keys = sorted(deduped.keys())

    words_folded = keys
    display = [deduped[key][0] for key in keys]
    frequencies = [deduped[key][1] for key in keys]
    word_index_of = {word: index for index, word in enumerate(display)}

    alphabet = sorted({code_point for folded in words_folded for code_point in folded})
    if not alphabet:
        raise SystemExit("the word list produced an empty alphabet")
    if len(alphabet) > MAX_ALPHABET:
        raise SystemExit(f"{len(alphabet)} distinct characters exceeds the cap of {MAX_ALPHABET}")
    symbol_of = {code_point: index + 1 for index, code_point in enumerate(alphabet)}

    base, check = build_double_array(words_folded, symbol_of)
    if len(base) > MAX_NODES:
        raise SystemExit(f"{len(base)} nodes exceeds the format cap of {MAX_NODES}")

    total_frequency = float(sum(frequencies)) or 1.0
    word_freq = bytes(quantise_log_prob(frequency / total_frequency) for frequency in frequencies)

    text_blob = bytearray()
    word_offsets = [0]
    for word in display:
        text_blob += word.encode("utf-8")
        word_offsets.append(len(text_blob))

    bigram_entries: dict[tuple[int, int], int] = {}
    trigram_entries: dict[tuple[int, int, int], int] = {}
    frequency_of = dict(zip(display, frequencies))
    for parts, count in ngrams.items():
        if any(part not in word_index_of for part in parts):
            continue
        context = frequency_of[parts[0]] if len(parts) == 2 else None
        if len(parts) == 2:
            denominator = float(context or 1)
            quantised = quantise_log_prob(count / max(denominator, float(count)))
            bigram_entries[(word_index_of[parts[0]], word_index_of[parts[1]])] = quantised
        elif len(parts) == 3:
            preceding = ngrams.get((parts[0], parts[1]))
            denominator = float(preceding or frequency_of[parts[1]] or 1)
            quantised = quantise_log_prob(count / max(denominator, float(count)))
            trigram_entries[
                (word_index_of[parts[0]], word_index_of[parts[1]], word_index_of[parts[2]])
            ] = quantised

    bigram_capacity, bigram_keys, bigram_values = build_hash_table(bigram_entries, 2)
    trigram_capacity, trigram_keys, trigram_values = build_hash_table(trigram_entries, 3)

    payloads = {
        S_ALPHABET: (struct.pack(f"<{len(alphabet)}I", *alphabet), 4),
        S_TRIE_BASE: (struct.pack(f"<{len(base)}i", *base), 4),
        S_TRIE_CHECK: (struct.pack(f"<{len(check)}i", *check), 4),
        S_WORD_OFFSETS: (struct.pack(f"<{len(word_offsets)}I", *word_offsets), 4),
        S_WORD_FREQ: (word_freq, 1),
        S_WORD_TEXT: (bytes(text_blob), 1),
        S_BIGRAM_KEYS: (bigram_keys, 8),
        S_BIGRAM_VALUES: (bigram_values, 1),
        S_TRIGRAM_KEYS: (trigram_keys, 4),
        S_TRIGRAM_VALUES: (trigram_values, 1),
    }

    body = bytearray()
    offsets: dict[int, int] = {}
    for index in range(SECTION_COUNT):
        data, alignment = payloads[index]
        if not data:
            offsets[index] = 0
            continue
        padded = align_up(HEADER_BYTES + len(body), alignment) - HEADER_BYTES
        body += b"\x00" * (padded - len(body))
        offsets[index] = HEADER_BYTES + len(body)
        body += data

    file_bytes = HEADER_BYTES + len(body)
    if file_bytes > MAX_PACK_BYTES:
        raise SystemExit(f"the pack would be {file_bytes} bytes, cap is {MAX_PACK_BYTES}")

    content_crc = zlib.crc32(bytes(body)) & 0xFFFFFFFF

    def pack_header(header_crc: int) -> bytes:
        blob = struct.pack(
            "<IIII Q II 16s IIIIII 6I",
            MAGIC,
            VERSION,
            HEADER_BYTES,
            FLAG_CASE_FOLDED | FLAG_CONTENT_CRC,
            file_bytes,
            content_crc,
            header_crc,
            tag.encode("utf-8"),
            len(display),
            len(base),
            len(alphabet),
            bigram_capacity,
            trigram_capacity,
            LOG_PROB_SCALE,
            0, 0, 0, 0, 0, 0,
        )
        for index in range(SECTION_COUNT):
            data, _ = payloads[index]
            blob += struct.pack("<QQ", offsets[index], len(data))
        assert len(blob) == HEADER_BYTES, len(blob)
        return blob

    header_crc = zlib.crc32(pack_header(0)) & 0xFFFFFFFF
    return pack_header(header_crc) + bytes(body)


# --------------------------------------------------------------------------------------
# Reader, used only by the round-trip test.
#
# An independent implementation on purpose. Verifying the writer with the writer's own idea of
# the layout proves nothing; this reads the bytes back the way bkd_format.hpp says to.
# --------------------------------------------------------------------------------------


class PackReader:
    def __init__(self, blob: bytes) -> None:
        self.blob = blob
        fields = struct.unpack_from("<IIII Q II 16s IIIIII 6I", blob, 0)
        (
            self.magic, self.version, self.header_bytes, self.flags,
            self.file_bytes, self.content_crc, self.header_crc, tag_raw,
            self.word_count, self.node_count, self.alphabet_count,
            self.bigram_capacity, self.trigram_capacity, self.log_prob_scale,
            *_reserved,
        ) = fields
        self.tag = tag_raw.split(b"\x00")[0].decode("utf-8")
        self.sections = [
            struct.unpack_from("<QQ", blob, 96 + 16 * index) for index in range(SECTION_COUNT)
        ]

        if self.magic != MAGIC:
            raise ValueError("magic mismatch")
        if self.version != VERSION:
            raise ValueError("version mismatch")
        if self.file_bytes != len(blob):
            raise ValueError("declared size does not match the actual size")

        recomputed_header = bytearray(blob[:HEADER_BYTES])
        recomputed_header[28:32] = b"\x00\x00\x00\x00"
        if zlib.crc32(bytes(recomputed_header)) & 0xFFFFFFFF != self.header_crc:
            raise ValueError("header checksum mismatch")
        if zlib.crc32(blob[HEADER_BYTES:]) & 0xFFFFFFFF != self.content_crc:
            raise ValueError("content checksum mismatch")

        offset, length = self.sections[S_ALPHABET]
        self.alphabet = list(struct.unpack_from(f"<{self.alphabet_count}I", blob, offset))
        self.symbol_of = {value: index + 1 for index, value in enumerate(self.alphabet)}

        offset, _ = self.sections[S_TRIE_BASE]
        self.base = list(struct.unpack_from(f"<{self.node_count}i", blob, offset))
        offset, _ = self.sections[S_TRIE_CHECK]
        self.check = list(struct.unpack_from(f"<{self.node_count}i", blob, offset))

        offset, _ = self.sections[S_WORD_OFFSETS]
        self.word_offsets = list(struct.unpack_from(f"<{self.word_count + 1}I", blob, offset))
        offset, _ = self.sections[S_WORD_FREQ]
        self.word_freq = blob[offset:offset + self.word_count]
        self.text_offset, self.text_length = self.sections[S_WORD_TEXT]

    def walk(self, node: int, symbol: int) -> int:
        if node < 0 or node >= self.node_count:
            return -1
        target = self.base[node] + symbol
        if target < 0 or target >= self.node_count:
            return -1
        if self.check[target] != node:
            return -1
        return target

    def lookup(self, word: str) -> int:
        node = 0
        for code_point in fold_word(word):
            symbol = self.symbol_of.get(code_point)
            if symbol is None:
                return -1
            node = self.walk(node, symbol)
            if node < 0:
                return -1
        terminal = self.walk(node, TERMINAL_SYMBOL)
        if terminal < 0:
            return -1
        encoded = self.base[terminal]
        if encoded >= 0:
            return -1
        return -encoded - 1

    def text(self, word_index: int) -> str:
        start = self.text_offset + self.word_offsets[word_index]
        end = self.text_offset + self.word_offsets[word_index + 1]
        return self.blob[start:end].decode("utf-8")

    def bigram_slot(self, first: int, second: int):
        if self.bigram_capacity == 0:
            return None
        offset, _ = self.sections[S_BIGRAM_KEYS]
        values_offset, _ = self.sections[S_BIGRAM_VALUES]
        mask = self.bigram_capacity - 1
        key = ((first + 1) << 32) | (second + 1)
        slot = mix64(key) & 0xFFFFFFFF & mask
        for _ in range(self.bigram_capacity):
            stored = struct.unpack_from("<Q", self.blob, offset + slot * 8)[0]
            if stored == 0:
                return None
            if stored == key:
                return self.blob[values_offset + slot]
            slot = (slot + 1) & mask
        return None


# --------------------------------------------------------------------------------------
# Round-trip test.
# --------------------------------------------------------------------------------------


def round_trip(words: list[tuple[str, int]], ngrams: dict, tag: str, samples: int = 100) -> None:
    blob = build_pack(tag, words, ngrams)
    reader = PackReader(blob)

    if reader.tag != tag:
        raise SystemExit(f"tag round-trip failed: wrote {tag!r}, read {reader.tag!r}")

    rng = random.Random(20260831)
    vocabulary = [word for word, _ in words]
    chosen = rng.sample(vocabulary, min(samples, len(vocabulary)))
    for word in chosen:
        index = reader.lookup(word)
        if index < 0:
            raise SystemExit(f"{word!r} was written but cannot be looked up")
        recovered = reader.text(index)
        if fold_word(recovered) != fold_word(word):
            raise SystemExit(f"{word!r} came back as {recovered!r}")

    # A word that is not in the pack must not be found, and must not walk out of bounds while
    # failing to be. This is the case a trie that only ever gets valid input never exercises.
    for absent in ("zzzqqq", "", "șțăxyz", "a" * 60):
        if reader.lookup(absent) >= 0:
            raise SystemExit(f"{absent!r} should not have been found")

    # Diacritic folding, both directions: the undecorated spelling must reach the decorated
    # entry, and the entry must come back with its diacritics intact.
    for word, _ in words:
        stripped = "".join(chr(fold_code_point(ord(character))) for character in word)
        if stripped == word:
            continue
        index = reader.lookup(stripped)
        if index < 0:
            raise SystemExit(f"folded form {stripped!r} does not reach {word!r}")
        if reader.text(index) != word:
            raise SystemExit(f"folded lookup of {stripped!r} returned {reader.text(index)!r}")

    for parts, _count in ngrams.items():
        if len(parts) != 2:
            continue
        indices = [reader.lookup(part) for part in parts]
        if any(index < 0 for index in indices):
            continue
        if reader.bigram_slot(indices[0], indices[1]) is None:
            raise SystemExit(f"bigram {parts} was written but cannot be found")

    print(f"round trip ok: {len(words)} words, {len(blob)} bytes, "
          f"{reader.node_count} nodes, {len(chosen)} sampled lookups")


SAMPLE_WORDS = [
    ("mașina", 9000), ("mașini", 4200), ("masiv", 300), ("masă", 5100), ("mare", 12000),
    ("micuț", 210), ("noapte", 3300), ("nouă", 4800), ("și", 90000), ("sunt", 41000),
    ("ședință", 900), ("ța", 40), ("țară", 7700), ("timp", 15000), ("întreb", 640),
    ("întrebare", 1200), ("înțeleg", 2100), ("acasă", 5600), ("acum", 22000), ("după", 18000),
    ("the", 100000), ("there", 24000), ("their", 21000), ("theme", 900), ("them", 30000),
    ("time", 26000), ("timer", 400), ("test", 5000), ("testing", 900), ("water", 4000),
    ("keyboard", 700), ("key", 3000), ("keys", 1500), ("border", 800), ("borders", 300),
    ("privacy", 600), ("private", 1400), ("prediction", 250), ("predict", 300), ("press", 2000),
    # The words the committed gesture corpus is recorded against. Kept here so the replay
    # harness has a dictionary to decode into without shipping a real lexicon, whose licence is
    # still an open question.
    ("these", 18000), ("people", 22000), ("should", 19000), ("because", 21000),
    ("through", 14000), ("another", 12000), ("between", 11000), ("important", 6000),
    ("different", 7000), ("question", 5000), ("together", 6500), ("water", 4000),
]

SAMPLE_NGRAMS = {
    ("nu", "am"): 500,
    ("am", "timp"): 300,
    ("mașina", "mare"): 120,
    ("the", "time"): 4000,
    ("there", "is"): 3000,
    ("key", "keyboard"): 40,
    ("the", "time", "is"): 900,
}


def load_words(path: Path) -> list[tuple[str, int]]:
    words: list[tuple[str, int]] = []
    with path.open(encoding="utf-8") as handle:
        for number, line in enumerate(handle, start=1):
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) != 2:
                raise SystemExit(f"{path}:{number}: expected 'word<TAB>frequency'")
            try:
                frequency = int(parts[1])
            except ValueError:
                raise SystemExit(f"{path}:{number}: {parts[1]!r} is not an integer") from None
            if frequency <= 0 or not parts[0]:
                continue
            words.append((parts[0], frequency))
    return words


def load_ngrams(path: Path) -> dict:
    ngrams: dict = {}
    with path.open(encoding="utf-8") as handle:
        for number, line in enumerate(handle, start=1):
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) not in (3, 4):
                raise SystemExit(f"{path}:{number}: expected 2 or 3 words then a count")
            try:
                count = int(parts[-1])
            except ValueError:
                raise SystemExit(f"{path}:{number}: {parts[-1]!r} is not an integer") from None
            if count <= 0:
                continue
            ngrams[tuple(parts[:-1])] = count
    return ngrams


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--words", type=Path)
    parser.add_argument("--ngrams", type=Path)
    parser.add_argument("--tag", default="und")
    parser.add_argument("--out", type=Path)
    parser.add_argument("--selftest", action="store_true",
                        help="build a synthetic pack and verify it can be read back")
    parser.add_argument("--dump-folds", action="store_true",
                        help="print every non-identity fold, for cross-checking against C++")
    arguments = parser.parse_args(argv)

    if arguments.dump_folds:
        for code_point in range(0, 0x2000):
            folded = fold_code_point(code_point)
            if folded != code_point:
                print(f"{code_point:04X}\t{folded:04X}")
        return 0

    if arguments.selftest:
        round_trip(SAMPLE_WORDS, SAMPLE_NGRAMS, "ro-RO")
        if arguments.out:
            blob = build_pack(arguments.tag, SAMPLE_WORDS, SAMPLE_NGRAMS)
            arguments.out.parent.mkdir(parents=True, exist_ok=True)
            arguments.out.write_bytes(blob)
            print(f"wrote {arguments.out} ({len(blob)} bytes)")
        return 0

    if not arguments.words or not arguments.out:
        parser.error("--words and --out are required unless --selftest is given")

    words = load_words(arguments.words)
    ngrams = load_ngrams(arguments.ngrams) if arguments.ngrams else {}
    blob = build_pack(arguments.tag, words, ngrams)

    # Written to a temporary file in the destination directory and renamed, so that an
    # interrupted build never leaves a half-written pack where the app would map it.
    arguments.out.parent.mkdir(parents=True, exist_ok=True)
    handle = tempfile.NamedTemporaryFile(dir=arguments.out.parent, delete=False)
    try:
        handle.write(blob)
        handle.close()
        Path(handle.name).replace(arguments.out)
    except BaseException:
        Path(handle.name).unlink(missing_ok=True)
        raise

    reader = PackReader(arguments.out.read_bytes())
    print(f"wrote {arguments.out}: {reader.word_count} words, {reader.node_count} nodes, "
          f"{len(blob)} bytes, tag {reader.tag}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
