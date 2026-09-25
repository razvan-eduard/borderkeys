#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Writes the corrupt language packs under native-tests/data/corpus.

Each file is the self-test pack of build_dict.py damaged in one named way. Where the damage is
structural, both checksums are repaired afterwards so the loader reaches the bounds check the
file exists to exercise rather than refusing it on the CRC. `pack_corpus_test` loads every one
and requires it refused.

    python3 tools/make_corrupt_packs.py native-tests/data/corpus
"""

from __future__ import annotations

import struct
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_dict  # noqa: E402

# Header field offsets -- see BkdHeader in bkd_format.hpp.
AT_MAGIC = 0
AT_VERSION = 4
AT_FILE_BYTES = 16
AT_CONTENT_CRC = 24
AT_HEADER_CRC = 28
AT_TAG = 32
AT_WORD_COUNT = 48
AT_NODE_COUNT = 52
AT_ALPHABET_COUNT = 56
AT_TRIGRAM_CAPACITY = 64
AT_LOG_PROB_SCALE = 68


def section_at(index: int, length: bool = False) -> int:
    """The byte offset of a section descriptor's offset, or of its length."""
    return build_dict.SECTION_TABLE_OFFSET + 16 * index + (8 if length else 0)


def put(blob: bytearray, at: int, layout: str, value: int) -> None:
    struct.pack_into(layout, blob, at, value)


def repair(blob: bytearray, keep_file_bytes: bool = False) -> None:
    """Recomputes both checksums, and the declared size unless the lie is the point."""
    if not keep_file_bytes:
        put(blob, AT_FILE_BYTES, "<Q", len(blob))
    put(blob, AT_CONTENT_CRC, "<I", zlib.crc32(bytes(blob[build_dict.HEADER_BYTES:])) & 0xFFFFFFFF)
    put(blob, AT_HEADER_CRC, "<I", 0)
    put(blob, AT_HEADER_CRC, "<I", zlib.crc32(bytes(blob[:build_dict.HEADER_BYTES])) & 0xFFFFFFFF)


def cases(good: bytes) -> dict[str, bytes]:
    out: dict[str, bytes] = {}

    def damaged(name: str, edit, repaired: bool = True, keep_file_bytes: bool = False) -> None:
        blob = bytearray(good)
        edit(blob)
        if repaired:
            repair(blob, keep_file_bytes)
        out[name] = bytes(blob)

    out["01_truncated_header.bkd"] = good[:100]
    out["02_empty.bkd"] = b""
    damaged("03_bad_magic.bkd", lambda b: put(b, AT_MAGIC, "<I", 0x44414542), repaired=False)
    damaged("04_future_version.bkd", lambda b: put(b, AT_VERSION, "<I", build_dict.VERSION + 1))
    damaged("05_size_lies_large.bkd", lambda b: put(b, AT_FILE_BYTES, "<Q", len(b) + 4096),
            keep_file_bytes=True)
    damaged("06_size_lies_small.bkd", lambda b: put(b, AT_FILE_BYTES, "<Q", len(b) - 64),
            keep_file_bytes=True)

    def flip(b: bytearray) -> None:
        b[len(b) // 2] ^= 0xAA
    damaged("07_content_bitflip.bkd", flip, repaired=False)

    damaged("08_offset_past_end.bkd",
            lambda b: put(b, section_at(build_dict.S_TRIE_BASE), "<Q", 0xFFFFFFFF))
    damaged("09_length_past_end.bkd",
            lambda b: put(b, section_at(build_dict.S_TRIE_BASE, length=True), "<Q", 0xFFFFFFFF))
    damaged("10_offset_wraps.bkd",
            lambda b: put(b, section_at(build_dict.S_TRIE_CHECK), "<Q", 0xFFFFFFFFFFFFFFF0))
    damaged("11_offset_in_header.bkd",
            lambda b: put(b, section_at(build_dict.S_ALPHABET), "<Q", 8))
    damaged("12_word_count_over_cap.bkd",
            lambda b: put(b, AT_WORD_COUNT, "<I", build_dict.MAX_WORDS + 1))
    damaged("13_node_count_over_cap.bkd",
            lambda b: put(b, AT_NODE_COUNT, "<I", build_dict.MAX_NODES + 1))
    damaged("14_trigram_capacity_odd.bkd", lambda b: put(b, AT_TRIGRAM_CAPACITY, "<I", 12345))
    damaged("15_empty_alphabet.bkd", lambda b: put(b, AT_ALPHABET_COUNT, "<I", 0))
    damaged("16_zero_logprob_scale.bkd", lambda b: put(b, AT_LOG_PROB_SCALE, "<I", 0))

    def overlap(b: bytearray) -> None:
        base_offset = struct.unpack_from("<Q", b, section_at(build_dict.S_TRIE_BASE))[0]
        put(b, section_at(build_dict.S_TRIE_CHECK), "<Q", base_offset)
    damaged("17_sections_overlap.bkd", overlap)

    def unterminated(b: bytearray) -> None:
        b[AT_TAG:AT_TAG + 16] = b"x" * 16
    damaged("18_unterminated_tag.bkd", unterminated)
    return out


def main(argv: list[str]) -> int:
    if len(argv) != 1:
        print(__doc__, file=sys.stderr)
        return 2
    directory = Path(argv[0])
    directory.mkdir(parents=True, exist_ok=True)
    good = build_dict.build_pack("ro-RO", build_dict.SAMPLE_WORDS, build_dict.SAMPLE_NGRAMS,
                                 proper_nouns=frozenset({"border"}))
    for stale in directory.glob("*.bkd"):
        stale.unlink()
    written = cases(good)
    for name, blob in written.items():
        (directory / name).write_bytes(blob)
    print(f"wrote {len(written)} packs to {directory}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
