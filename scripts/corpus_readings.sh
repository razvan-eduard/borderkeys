#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

set -euo pipefail

# Writes every corpus reading into <out dir>:
#   native/  suggest_eval, per case: what the space bar commits, and the strip ranks for suggest_en
#   kotlin/  the pipeline tests, per case: typed, committed, reason, and the engine's ranking
# Two runs compare with `diff -r`.
#
# Usage: scripts/corpus_readings.sh <out dir> [--centre-taps]
#
# --centre-taps writes native/ only, each letter tapped at its key's centre, where the default
# touch patterns price every substitution as the key geometry does (suggest_eval --centre-taps).

if [ $# -lt 1 ] || [ $# -gt 2 ] || { [ $# -eq 2 ] && [ "$2" != "--centre-taps" ]; }; then
    echo "usage: $0 <out dir> [--centre-taps]" >&2
    exit 2
fi
TAPS="${2:-}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$1"
OUT="$(cd "$1" && pwd)"
PACKS="$ROOT/keyboard/build/generated/dictionaries/dict"
DATA="$ROOT/native-tests/data"
EVAL="$ROOT/native-tests/build/suggest_eval"

cmake -S "$ROOT/native-tests" -B "$ROOT/native-tests/build" -DCMAKE_BUILD_TYPE=Debug > /dev/null
cmake --build "$ROOT/native-tests/build" --parallel --target borderkeys suggest_eval > /dev/null
"$ROOT/gradlew" -p "$ROOT" -q :keyboard:buildDictionaries

rm -rf "$OUT/native" "$OUT/kotlin"
mkdir -p "$OUT/native" "$OUT/kotlin"

# The pack each corpus runs against, from the language in its file name.
tag_for() {
    case "$1" in
        en) echo en-US ;;
        ro) echo ro-RO ;;
        fr) echo fr-FR ;;
        es) echo es-ES ;;
        it) echo it-IT ;;
        de) echo de-DE ;;
        *) echo "" ;;
    esac
}

for CORPUS in "$DATA"/autocorrect_*.tsv; do
    NAME="$(basename "$CORPUS" .tsv)"
    TAG="$(tag_for "${NAME##*_}")"
    if [ -z "$TAG" ] || [ ! -f "$PACKS/${TAG/-/_}.bkd" ]; then
        echo "skipped $NAME: no shipped pack" >&2
        continue
    fi
    "$EVAL" "$PACKS" --autocorrect "$CORPUS" "$TAG" $TAPS > "$OUT/native/$NAME.txt"
done
for CORPUS in "$DATA"/suggest_*.tsv; do
    NAME="$(basename "$CORPUS" .tsv)"
    "$EVAL" "$PACKS" "$CORPUS" en-US $TAPS > "$OUT/native/$NAME.txt"
done

# SUMMARY.txt: one line per native reading with its headline count, then the pipeline tests'
# reports. Excluded from the diff of two runs (`-x SUMMARY.txt`), read on its own.
{
    for FILE in "$OUT"/native/*.txt; do
        printf '%-28s %s\n' "$(basename "$FILE" .txt)" \
            "$(grep -E '^(cases|commits expected|commits nothing|first|top three) ' "$FILE" | tr -s ' ' | paste -sd ';' -)"
    done
} > "$OUT/SUMMARY.txt"

if [ -n "$TAPS" ]; then
    rmdir "$OUT/kotlin"
    echo "native: $(ls "$OUT/native" | wc -l | tr -d ' ') files, centre-tapped, in $OUT"
    exit 0
fi

"$ROOT/gradlew" -p "$ROOT" -q :keyboard:testCoreDebugUnitTest \
    --tests 'com.borderkeys.predict.PipelineCorpusTest' \
    --tests 'com.borderkeys.predict.PipelineTest' \
    --rerun -Pborderkeys.readings="$OUT/kotlin"

if [ -f "$OUT/kotlin/SUMMARY.txt" ]; then
    { echo; cat "$OUT/kotlin/SUMMARY.txt"; } >> "$OUT/SUMMARY.txt"
    rm "$OUT/kotlin/SUMMARY.txt"
fi

echo "native: $(ls "$OUT/native" | wc -l | tr -d ' ') files, kotlin: $(ls "$OUT/kotlin" | wc -l | tr -d ' ') files in $OUT"
