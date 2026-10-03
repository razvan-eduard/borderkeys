#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
#
# Trains one script's swipe model from scratch on synthetic swipes, once per seed. A seed whose
# weights exist is skipped; a seed whose checkpoint exists resumes after its last saved epoch.
# Arguments: layout, word list, generator (polyline or flow), seeds, and the flow checkpoint.
#
# Writes data/<layout>-<generator>/train.jsonl, and checkpoint_<layout>-<generator>-s<seed>.pt,
# train_<layout>-<generator>-s<seed>.log (every epoch's loss) and <layout>-<generator>-s<seed>.bkw
# here; nothing is copied into the app.

set -euo pipefail
shopt -s inherit_errexit 2>/dev/null || true
layout=$1
list=$2
generator=$3
seeds=${4:-"1 2 3"}
flow=${5:-}
swipes=${SWIPES:-200000}
epochs=${EPOCHS:-80}
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
cd "$here"
export HF_HUB_OFFLINE=1

data="data/${layout}-${generator}"
if [ ! -s "$data/train.jsonl.done" ]; then
  .venv/bin/python -u synthesise.py --generator "$generator" ${flow:+--flow "$flow"} \
    --layout "$root/native-tests/data/${layout}_1080.layout" \
    --words "$root/dictionaries/extra/${list}.tsv" \
    --count "$swipes" --out "$data/train.jsonl"
  echo "$swipes" > "$data/train.jsonl.done"
fi

for seed in $seeds; do
  name="${layout}-${generator}-s${seed}"
  if [ -s "${name}.bkw" ]; then
    continue
  fi
  resume=()
  if [ -s "checkpoint_${name}.pt" ]; then
    resume=(--resume)
    echo "=== restart $(date '+%F %T') ===" >> "train_${name}.log"
  else
    echo "=== start $(date '+%F %T') ===" > "train_${name}.log"
  fi
  .venv/bin/python -u train.py \
    --data "$data" --layout "$root/native-tests/data/${layout}_1080.layout" \
    --epochs "$epochs" --seed "$seed" --checkpoint "checkpoint_${name}.pt" ${resume[@]+"${resume[@]}"} \
    2>&1 | tee -a "train_${name}.log"
  .venv/bin/python export_weights.py --checkpoint "checkpoint_${name}.pt" --out "${name}.bkw" --half
done
