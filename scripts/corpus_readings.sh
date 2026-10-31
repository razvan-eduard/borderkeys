#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

set -euo pipefail

# Writes every corpus reading into <out dir>:
#   native/     suggest_eval, per case: where the strip ranks the word expected, on every autocorrect
#               corpus with a shipped pack and on suggest_*
#   context/    suggest_eval --context on each language's held-out text, per word and letters typed;
#               <language>.starts.tsv asks each run's first word with nothing before it
#   reachable/  suggest_eval --reachable on each shipped pack, per word of its list
#   swipe/      both swipe decoders on the recorded and the synthesised traces, per gesture
#   taps/       touch_eval on the six tap corpora, per slipped word and touch-model setting
#   kotlin/     the pipeline tests, per case: typed, committed, reason, and the engine's ranking,
#               the autocorrect corpora with Romanian and English both on among them; the tapped
#               words through the whole keyboard; the personal rows swiped after a history
# Each reading's .txt is its tool's own report; each .tsv is a rows file, one verdict per
# measure on every row, for scripts/compare_readings.py <before dir> <after dir>.
#
# Usage: scripts/corpus_readings.sh <out dir> [--centre-taps | --fast]
#            [--sheet NAME --ledger FILE [--second-test-read WORDS]]
#
# --centre-taps writes native/ only, each letter tapped at its key's centre, where the default
# touch patterns price every substitution as the key geometry does (suggest_eval --centre-taps).
# --fast writes context/ and reachable/ only. Either leaves out swipe/, taps/ and kotlin/.
# --sheet records the reading as the design sheet's TEST read in the ledger once the builds pass,
# before the first reading (scripts/judge_arms.py ledger), and stops when the ledger already holds
# one, unless --second-test-read gives the words that allow another.

usage() {
    echo "usage: $0 <out dir> [--centre-taps | --fast] [--sheet NAME --ledger FILE" \
        "[--second-test-read WORDS]]" >&2
    exit 2
}
[ $# -ge 1 ] || usage
TARGET="$1"
shift
MODE=""
SHEET=""
LEDGER=""
WORDS=""
while [ $# -gt 0 ]; do
    case "$1" in
        --centre-taps | --fast)
            [ -z "$MODE" ] || usage
            MODE="$1"
            shift
            ;;
        --sheet | --ledger | --second-test-read)
            [ $# -ge 2 ] || usage
            case "$1" in
                --sheet) SHEET="$2" ;;
                --ledger) LEDGER="$2" ;;
                *) WORDS="$2" ;;
            esac
            shift 2
            ;;
        *) usage ;;
    esac
done
if { [ -n "$SHEET" ] && [ -z "$LEDGER" ]; } || { [ -z "$SHEET" ] && [ -n "$LEDGER" ]; } ||
    { [ -n "$WORDS" ] && [ -z "$SHEET" ]; }; then
    usage
fi
TAPS=""
if [ "$MODE" = "--centre-taps" ]; then
    TAPS="--centre-taps"
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$TARGET"
OUT="$(cd "$TARGET" && pwd)"
PACKS="$ROOT/keyboard/build/generated/dictionaries/dict"
DATA="$ROOT/native-tests/data"
EVAL="$ROOT/native-tests/build/suggest_eval"
# The held-out text --context reads, fetched once and kept where CI keeps it.
CONTEXT="$ROOT/native-tests/build/context"
# The tap corpora, written where CI writes them, and the personal rows' swipe traces.
TAP_CORPORA="$ROOT/native-tests/build/taps"
SWIPE_TRACES="$ROOT/native-tests/build/swipes"
LAYOUT="$DATA/qwerty_1080.layout"
LATIN_MODEL="$ROOT/keyboard/src/plus/assets/model.bkw"

cmake -S "$ROOT/native-tests" -B "$ROOT/native-tests/build" -DCMAKE_BUILD_TYPE=Debug > /dev/null
cmake --build "$ROOT/native-tests/build" --parallel \
    --target borderkeys suggest_eval gesture_replay tcn_replay touch_eval > /dev/null
"$ROOT/gradlew" -p "$ROOT" -q --offline :keyboard:buildDictionaries

if [ -n "$SHEET" ]; then
    python3 "$ROOT/scripts/judge_arms.py" ledger "$LEDGER" "$SHEET" --second-test-read "$WORDS"
fi
rm -rf "$OUT/native" "$OUT/context" "$OUT/reachable" "$OUT/swipe" "$OUT/taps" "$OUT/kotlin" "$OUT/SUMMARY.txt"

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

# The context model and reachability, every language at once.
held_out_readings() {
    mkdir -p "$OUT/context" "$OUT/reachable"
    python3 "$ROOT/tools/make_context_corpus.py" "$CONTEXT" > /dev/null
    local pids=()
    for LANGUAGE in en ro de fr es it; do
        TAG="$(tag_for "$LANGUAGE")"
        "$EVAL" "$PACKS" --context "$CONTEXT/context_$LANGUAGE.txt" "$TAG" \
            --rows "$OUT/context/$LANGUAGE.tsv" \
            --start-rows "$OUT/context/$LANGUAGE.starts.tsv" > "$OUT/context/$LANGUAGE.txt" &
        pids+=("$!")
        # Without a budget the exit status reports any unreachable word; the report's own
        # count line says the reading finished.
        "$EVAL" "$PACKS" --reachable "$ROOT/dictionaries/${TAG/-/_}.tsv" "$TAG" \
            --rows "$OUT/reachable/$TAG.tsv" > "$OUT/reachable/$TAG.txt" || true &
        pids+=("$!")
    done
    for PID in "${pids[@]}"; do
        wait "$PID"
    done
    for FILE in "$OUT"/reachable/*.txt; do
        if ! grep -q ' unreachable (' "$FILE"; then
            echo "$(basename "$FILE" .txt): no reachability reading" >&2
            exit 1
        fi
    done
}

if [ "$MODE" != "--fast" ]; then
    mkdir -p "$OUT/native"
    for CORPUS in "$DATA"/autocorrect_*.tsv; do
        NAME="$(basename "$CORPUS" .tsv)"
        TAG="$(tag_for "${NAME##*_}")"
        if [ -z "$TAG" ] || [ ! -f "$PACKS/${TAG/-/_}.bkd" ]; then
            echo "skipped $NAME: no shipped pack" >&2
            continue
        fi
        "$EVAL" "$PACKS" --autocorrect "$CORPUS" "$TAG" $TAPS --rows "$OUT/native/$NAME.tsv" \
            > "$OUT/native/$NAME.txt"
    done
    for CORPUS in "$DATA"/suggest_*.tsv; do
        NAME="$(basename "$CORPUS" .tsv)"
        "$EVAL" "$PACKS" "$CORPUS" en-US $TAPS --rows "$OUT/native/$NAME.tsv" > "$OUT/native/$NAME.txt"
    done
fi
if [ "$MODE" != "--centre-taps" ]; then
    held_out_readings
fi

# Both swipe decoders on FUTO's recorded traces and the synthesised ones, the neural decoder
# with the Latin model.
swipe_readings() {
    mkdir -p "$OUT/swipe"
    local TRACES NAME
    for TRACES in futo:gestures_futo synthetic:gestures; do
        NAME="${TRACES%%:*}"
        python3 "$ROOT/tools/gesture_replay.py" --binary "$ROOT/native-tests/build/gesture_replay" \
            --pack "$PACKS/en_US.bkd" --layout "$LAYOUT" --corpus "$DATA/${TRACES#*:}.csv" \
            --rows "$OUT/swipe/shark2_$NAME.tsv" > "$OUT/swipe/shark2_$NAME.txt"
        python3 "$ROOT/tools/tcn_replay.py" --binary "$ROOT/native-tests/build/tcn_replay" \
            --pack "$PACKS/en_US.bkd" --weights "$LATIN_MODEL" --layout "$LAYOUT" \
            --corpus "$DATA/${TRACES#*:}.csv" --rows "$OUT/swipe/tcn_$NAME.tsv" \
            > "$OUT/swipe/tcn_$NAME.txt" 2>&1
    done
}

# The touch model alone on each tap corpus, as CI's floors read it.
tap_readings() {
    mkdir -p "$OUT/taps" "$TAP_CORPORA"
    local pids=()
    local PROFILE
    for PROFILE in centred low thumbs right-thumb precise sloppy; do
        python3 "$ROOT/tools/make_tap_corpus.py" --profile "$PROFILE" --taps 60000 --seed 1 \
            > "$TAP_CORPORA/$PROFILE.tsv"
        "$ROOT/native-tests/build/touch_eval" "$PACKS" en-US "$LAYOUT" "$TAP_CORPORA/$PROFILE.tsv" \
            --train-taps 20000 --test-from-taps 40000 --test-words 4000 \
            --rows "$OUT/taps/touch_$PROFILE.tsv" > "$OUT/taps/touch_$PROFILE.txt" &
        pids+=("$!")
    done
    for PID in "${pids[@]}"; do
        wait "$PID"
    done
}

# The traces SwipePersonalCorpusTest replays: each language's clean personal rows, in order.
swipe_traces() {
    mkdir -p "$SWIPE_TRACES"
    local LANGUAGE
    for LANGUAGE in en ro es; do
        python3 "$ROOT/tools/gesture_replay.py" --synthesise --fold --layout "$LAYOUT" \
            --words "$(awk -F'\t' '!/^#/ && $3 == "clean" { print $2 }' "$DATA/personal_rows_$LANGUAGE.tsv" | paste -sd, -)" \
            --out "$SWIPE_TRACES/swipe_personal_$LANGUAGE.csv" > /dev/null
    done
}

if [ -z "$MODE" ]; then
    swipe_readings
    tap_readings
fi

# SUMMARY.txt: one line per native reading with its headline count, the context and
# reachability reports' headlines, then the pipeline tests' reports. Excluded from the
# comparison of two runs, read on its own.
{
    for FILE in "$OUT"/native/*.txt; do
        [ -e "$FILE" ] || continue
        printf '%-28s %s\n' "$(basename "$FILE" .txt)" \
            "$(grep -E '^(cases|first|top three) ' "$FILE" | tr -s ' ' | paste -sd ';' -)"
    done
    for FILE in "$OUT"/context/*.txt; do
        [ -e "$FILE" ] || continue
        echo
        grep -E '^(context model|  (next word|[0-9] letters? typed) )' "$FILE"
    done
    for FILE in "$OUT"/reachable/*.txt; do
        [ -e "$FILE" ] || continue
        grep -E ' unreachable \(' "$FILE"
    done
    for FILE in "$OUT"/swipe/*.txt; do
        [ -e "$FILE" ] || continue
        printf '%-28s %s\n' "swipe/$(basename "$FILE" .txt)" \
            "$(grep -E '^(top-1|decoded) ' "$FILE" | paste -sd ';' -)"
    done
    for FILE in "$OUT"/taps/*.txt; do
        [ -e "$FILE" ] || continue
        printf '%-28s %s\n' "taps/$(basename "$FILE" .txt)" \
            "$(grep -E '^(tested|default) ' "$FILE" | tr -s ' ' | paste -sd ';' -)"
    done
} > "$OUT/SUMMARY.txt"

if [ -n "$MODE" ]; then
    echo "$(find "$OUT" -type f | wc -l | tr -d ' ') files, ${MODE#--}, in $OUT"
    exit 0
fi

# A floor the pipeline tests miss fails them after their rows are written; the summary is still
# gathered, and the script exits with the tests' status.
PIPELINE_STATUS=0
swipe_traces
"$ROOT/gradlew" -p "$ROOT" -q --offline :keyboard:testCoreDebugUnitTest \
    --tests 'com.borderkeys.predict.PipelineCorpusTest' \
    --tests 'com.borderkeys.predict.TwoLanguageCorpusTest' \
    --tests 'com.borderkeys.predict.PipelineTest' \
    --tests 'com.borderkeys.predict.PersonalCorpusTest' \
    --tests 'com.borderkeys.predict.TapCorpusTest' \
    --tests 'com.borderkeys.predict.SwipePersonalCorpusTest' \
    --rerun -Pborderkeys.readings="$OUT/kotlin" -Pborderkeys.taps="$TAP_CORPORA" \
    -Pborderkeys.swipes="$SWIPE_TRACES" || PIPELINE_STATUS=$?

if [ -f "$OUT/kotlin/SUMMARY.txt" ]; then
    { echo; cat "$OUT/kotlin/SUMMARY.txt"; } >> "$OUT/SUMMARY.txt"
    rm "$OUT/kotlin/SUMMARY.txt"
fi

echo "$(find "$OUT" -type f | wc -l | tr -d ' ') files in $OUT"
if [ "$PIPELINE_STATUS" -ne 0 ]; then
    echo "the pipeline tests exited $PIPELINE_STATUS" >&2
fi
exit "$PIPELINE_STATUS"
