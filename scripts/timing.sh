#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

set -euo pipefail

# Times the engine call by call on an optimised build: native-tests/timing_eval in each language,
# every workload with no personal model and, where a persona's history is committed, after it.
# Two builds are timed in alternation, five runs unless --runs says otherwise, the first of the two
# swapped every other run (scripts/timing_summary.py order), one language after another, never two
# at once; then scripts/timing_summary.py gives each group's p50 and p95 and its verdict. --one
# times one build alone and gives its p50 and p95.
#
# A build is a timing_eval binary, or a source tree, which is built into its
# native-tests/build-timing as RelWithDebInfo. Both builds read this tree's inputs: the packs
# (:keyboard:buildDictionaries), the held-out text (tools/make_context_corpus.py, kept where CI
# keeps it), the persona histories, a tap corpus (tools/make_tap_corpus.py, the centred profile),
# FUTO's swipes and the Latin model; the taps and the swipes in English.
#
# Usage: scripts/timing.sh <out dir> <before> <after> [--runs N] [--limit N]
#        scripts/timing.sh <out dir> --one <build> [--runs N] [--limit N]
#
# --limit N times the first N calls of each workload, for a quick look. Writes
# <out dir>/<build>-<run>/<language>.tsv (the build `before`, `after`, or `build` with --one),
# <out dir>/builds.txt, <out dir>/build.log and <out dir>/SUMMARY.txt, and exits with
# timing_summary.py's status.

usage() {
    echo "usage: $0 <out dir> <before> <after> [--runs N] [--limit N]" >&2
    echo "       $0 <out dir> --one <build> [--runs N] [--limit N]" >&2
    exit 2
}
[ $# -ge 3 ] || usage
TARGET="$1"
if [ "$2" = "--one" ]; then
    FIRST="$3"
    SECOND=""
else
    FIRST="$2"
    SECOND="$3"
fi
shift 3
RUNS=5
LIMIT=""
while [ $# -gt 0 ]; do
    [ $# -ge 2 ] || usage
    case "$1" in
        --runs) RUNS="$2" ;;
        --limit) LIMIT="$2" ;;
        *) usage ;;
    esac
    shift 2
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$TARGET"
OUT="$(cd "$TARGET" && pwd)"
PACKS="$ROOT/keyboard/build/generated/dictionaries/dict"
DATA="$ROOT/native-tests/data"
# The held-out text, fetched once and kept where CI keeps it.
CONTEXT="$ROOT/native-tests/build/context"
INPUTS="$OUT/inputs"
TAPS="$INPUTS/centred.tsv"
LAYOUT="$DATA/qwerty_1080.layout"
LATIN_MODEL="$ROOT/keyboard/src/plus/assets/model.bkw"

# Builds the timing_eval of [build] when it is a source tree, its output in <out dir>/build.log.
build_tree() {
    if [ -d "$1/native-tests" ]; then
        if ! { cmake -S "$1/native-tests" -B "$1/native-tests/build-timing" \
            -DCMAKE_BUILD_TYPE=RelWithDebInfo &&
            cmake --build "$1/native-tests/build-timing" --parallel --target timing_eval; } \
            >> "$OUT/build.log" 2>&1; then
            echo "the timing build of $1 failed: $OUT/build.log" >&2
            exit 1
        fi
    elif [ ! -x "$1" ]; then
        echo "not a timing_eval binary or a source tree: $1" >&2
        exit 2
    fi
}

# The timing_eval binary of [build].
binary_of() {
    if [ -d "$1/native-tests" ]; then
        echo "$(cd "$1" && pwd)/native-tests/build-timing/timing_eval"
    else
        echo "$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
    fi
}

rm -f "$OUT/build.log"
build_tree "$FIRST"
if [ -n "$SECOND" ]; then
    build_tree "$SECOND"
fi
"$ROOT/gradlew" -p "$ROOT" -q --offline :keyboard:buildDictionaries
python3 "$ROOT/tools/make_context_corpus.py" "$CONTEXT" > /dev/null
mkdir -p "$INPUTS"
python3 "$ROOT/tools/make_tap_corpus.py" --profile centred --taps 60000 --seed 1 > "$TAPS"

rm -rf "$OUT"/before-* "$OUT"/after-* "$OUT"/build-[0-9]* "$OUT/SUMMARY.txt"
{
    if [ -n "$SECOND" ]; then
        echo "before $(binary_of "$FIRST")"
        echo "after $(binary_of "$SECOND")"
    else
        echo "build $(binary_of "$FIRST")"
    fi
    echo "load at the start:$(uptime | sed 's/.*load averages*://')"
} > "$OUT/builds.txt"

# The pack each language is timed on.
tag_for() {
    case "$1" in
        en) echo en-US ;;
        ro) echo ro-RO ;;
        fr) echo fr-FR ;;
        es) echo es-ES ;;
        it) echo it-IT ;;
        de) echo de-DE ;;
    esac
}

# One run of [binary] into [directory], every language in turn.
time_build() {
    local BINARY="$1" DIRECTORY="$2" LANGUAGE TAG
    mkdir -p "$DIRECTORY"
    for LANGUAGE in en ro de fr es it; do
        TAG="$(tag_for "$LANGUAGE")"
        local ARGUMENTS=(--context "$CONTEXT/context_$LANGUAGE.txt")
        if [ -f "$DATA/personal_history_$LANGUAGE.txt" ]; then
            ARGUMENTS+=(--history "$DATA/personal_history_$LANGUAGE.txt")
        fi
        if [ "$LANGUAGE" = en ]; then
            ARGUMENTS+=(--taps "$TAPS" --gestures "$DATA/gestures_futo.csv" --weights "$LATIN_MODEL")
        fi
        if [ -n "$LIMIT" ]; then
            ARGUMENTS+=(--limit "$LIMIT")
        fi
        "$BINARY" "$PACKS" "$TAG" "$LAYOUT" "$DIRECTORY/$LANGUAGE.tsv" "${ARGUMENTS[@]}" > /dev/null
    done
}

if [ -n "$SECOND" ]; then
    python3 "$ROOT/scripts/timing_summary.py" order "$RUNS" | while read -r RUN ONE OTHER; do
        for BUILD in "$ONE" "$OTHER"; do
            if [ "$BUILD" = before ]; then
                time_build "$(binary_of "$FIRST")" "$OUT/before-$RUN"
            else
                time_build "$(binary_of "$SECOND")" "$OUT/after-$RUN"
            fi
        done
        echo "run $RUN of $RUNS timed" >&2
    done
else
    for RUN in $(seq 1 "$RUNS"); do
        time_build "$(binary_of "$FIRST")" "$OUT/build-$RUN"
        echo "run $RUN of $RUNS timed" >&2
    done
fi

STATUS=0
python3 "$ROOT/scripts/timing_summary.py" "$OUT" > "$OUT/SUMMARY.txt" || STATUS=$?
cat "$OUT/SUMMARY.txt"
exit "$STATUS"
