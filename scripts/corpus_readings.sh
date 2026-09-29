#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

set -euo pipefail

# Writes every corpus reading into <out dir>:
#   native/  suggest_eval, per case: what the space bar commits, and the strip ranks for suggest_en
#   kotlin/  the pipeline tests, per case: typed, committed, reason, and the engine's ranking
# Two runs compare with `diff -r`.
#
# Usage: scripts/corpus_readings.sh <out dir>

if [ $# -ne 1 ]; then
    echo "usage: $0 <out dir>" >&2
    exit 2
fi

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
    "$EVAL" "$PACKS" --autocorrect "$CORPUS" "$TAG" > "$OUT/native/$NAME.txt"
done
"$EVAL" "$PACKS" "$DATA/suggest_en.tsv" en-US > "$OUT/native/suggest_en.txt"

"$ROOT/gradlew" -p "$ROOT" -q :keyboard:testCoreDebugUnitTest \
    --tests 'com.borderkeys.predict.PipelineCorpusTest' \
    --tests 'com.borderkeys.predict.PipelineTest' \
    --rerun -Pborderkeys.readings="$OUT/kotlin"

echo "native: $(ls "$OUT/native" | wc -l | tr -d ' ') files, kotlin: $(ls "$OUT/kotlin" | wc -l | tr -d ' ') files in $OUT"
