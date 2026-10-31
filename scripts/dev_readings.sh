#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

set -euo pipefail

# Writes the dev readings into <out dir>: for each language with a dev corpus
# (tools/make_dev_corpus.py), suggest_eval --context on it, per word and letters typed, into
# <language>.tsv, and each run's first word with nothing before it into <language>.starts.tsv;
# then CorrectionDevReadingsTest types the correction rows and the personal DEV rows the same
# directory holds through the app's own commit, one rows file each into kotlin/. SUMMARY.txt,
# written last, holds the context reports' headlines and the Kotlin rows' tallies.
# The language model's constants are chosen on these rows; the test readings
# (scripts/corpus_readings.sh) are read once, at the end. Compare two with
# scripts/compare_readings.py <before dir> <after dir>. Each language's DEV sentences are then
# counted by genre beside its TEST sentences (native-tests/build/context), and a TEST genre DEV
# lacks is named (scripts/judge_arms.py genres).
#
# Usage: scripts/dev_readings.sh <out dir> [--packs DIR] [--eval BIN] [--library DIR] [--dev DIR]
#
# --packs reads another directory of packs, named <language>_<REGION>.bkd, in both readings; by
# default the bundled packs, built here. --eval runs another suggest_eval, and --library loads
# another host library (libborderkeys) for the Kotlin rows; by default the tree's, built here.
# --dev reads the dev corpora from another directory; by default native-tests/build/dev.

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ $# -lt 1 ]; then
    echo "usage: $0 <out dir> [--packs DIR] [--eval BIN] [--library DIR] [--dev DIR]" >&2
    exit 2
fi
mkdir -p "$1"
OUT="$(cd "$1" && pwd)"
shift
PACKS=""
EVAL=""
LIBRARY=""
DEV="$ROOT/native-tests/build/dev"
# The absolute path of the directory option $1 names in $2, as the Gradle run needs it; the
# script stops when $2 is no directory.
directory() {
    { [ -n "$2" ] && cd "$2" 2>/dev/null && pwd; } || { echo "$1: no directory '$2'" >&2; exit 2; }
}
while [ $# -gt 0 ]; do
    case "$1" in
        --packs) PACKS="$(directory "$1" "$2")"; shift 2 ;;
        --eval) EVAL="$2"; shift 2 ;;
        --library) LIBRARY="$(directory "$1" "$2")"; shift 2 ;;
        --dev) DEV="$(directory "$1" "$2")"; shift 2 ;;
        *) echo "unknown option $1" >&2; exit 2 ;;
    esac
done

# The Gradle properties that hand the Kotlin rows another engine's packs and host library.
ENGINE_PROPERTIES=()
TARGETS=()
if [ -z "$EVAL" ]; then
    TARGETS+=(suggest_eval)
    EVAL="$ROOT/native-tests/build/suggest_eval"
fi
if [ -z "$LIBRARY" ]; then
    TARGETS+=(borderkeys)
else
    ENGINE_PROPERTIES+=("-Pborderkeys.libraryDir=$LIBRARY")
fi
if [ "${#TARGETS[@]}" -gt 0 ]; then
    cmake -S "$ROOT/native-tests" -B "$ROOT/native-tests/build" -DCMAKE_BUILD_TYPE=Debug > /dev/null
    cmake --build "$ROOT/native-tests/build" --parallel --target "${TARGETS[@]}" > /dev/null
fi
if [ -z "$PACKS" ]; then
    "$ROOT/gradlew" -p "$ROOT" -q --offline :keyboard:buildDictionaries
    PACKS="$ROOT/keyboard/build/generated/dictionaries/dict"
else
    ENGINE_PROPERTIES+=("-Pborderkeys.packsDir=$PACKS")
fi

rm -rf "$OUT/kotlin" "$OUT/SUMMARY.txt"
pids=()
read=0
for CORPUS in "$DEV"/dev_*.txt; do
    [ -f "$CORPUS" ] || continue
    LANGUAGE="$(basename "$CORPUS" .txt)"
    LANGUAGE="${LANGUAGE#dev_}"
    PACK="$(ls "$PACKS/${LANGUAGE}"_*.bkd 2>/dev/null | head -n 1)"
    if [ -z "$PACK" ]; then
        echo "$LANGUAGE: no pack in $PACKS" >&2
        exit 1
    fi
    NAME="$(basename "$PACK" .bkd)"
    "$EVAL" "$PACKS" --context "$CORPUS" "${NAME/_/-}" --rows "$OUT/$LANGUAGE.tsv" \
        --start-rows "$OUT/$LANGUAGE.starts.tsv" > "$OUT/$LANGUAGE.txt" &
    pids+=("$!")
    read=$((read + 1))
done
if [ "$read" -eq 0 ]; then
    echo "no dev corpora in $DEV; write them with tools/make_dev_corpus.py" >&2
    exit 1
fi
for PID in "${pids[@]}"; do
    wait "$PID"
done
echo "$read languages read into $OUT"

# The correction and personal DEV rows through the whole Kotlin path; the summary is still
# gathered when they fail, and the script exits with the tests' status.
PIPELINE_STATUS=0
"$ROOT/gradlew" -p "$ROOT" -q --offline :keyboard:testCoreDebugUnitTest \
    --tests 'com.borderkeys.predict.CorrectionDevReadingsTest' \
    --rerun -Pborderkeys.dev="$DEV" -Pborderkeys.readings="$OUT/kotlin" \
    ${ENGINE_PROPERTIES[@]+"${ENGINE_PROPERTIES[@]}"} || PIPELINE_STATUS=$?
if [ "$PIPELINE_STATUS" -eq 0 ] && ! ls "$OUT"/kotlin/*.tsv > /dev/null 2>&1; then
    echo "CorrectionDevReadingsTest wrote no rows into $OUT/kotlin" >&2
    PIPELINE_STATUS=1
fi

{
    for FILE in "$OUT"/*.txt; do
        [ -e "$FILE" ] || continue
        grep -E '^(context model|  (next word|[0-9] letters? typed) )' "$FILE"
        echo
    done
    if [ -f "$OUT/kotlin/SUMMARY.txt" ]; then
        cat "$OUT/kotlin/SUMMARY.txt"
        rm "$OUT/kotlin/SUMMARY.txt"
    fi
} > "$OUT/SUMMARY.txt.partial"
python3 "$ROOT/scripts/judge_arms.py" genres "$DEV" "$ROOT/native-tests/build/context" || true
mv "$OUT/SUMMARY.txt.partial" "$OUT/SUMMARY.txt"
if [ "$PIPELINE_STATUS" -ne 0 ]; then
    echo "CorrectionDevReadingsTest exited $PIPELINE_STATUS" >&2
fi
exit "$PIPELINE_STATUS"
