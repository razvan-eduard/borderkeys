<!--
SPDX-License-Identifier: GPL-3.0-or-later
SPDX-FileCopyrightText: 2026 BorderKeys contributors
-->

# Swipe-gesture TCN training pipeline

Offline PyTorch training for the swipe-typing model. This is the *training* side only — the
runtime that actually ships in the app is a hand-written C++ inference implementation under
`keyboard/src/main/cpp/gesture/` (`tcn_encoder.*`, `tcn_decoder.*`, `tcn_ctc_decoder.*`,
`tcn_weights.*`, `tcn_features.*`), which reads the weights this pipeline exports. No ML runtime
(TFLite/ONNX/NNAPI) is used anywhere in this project — the model is small enough (~640K params)
that a bespoke C++ decoder was worth writing instead.

This directory is tracked in git, and the weights it exports ship as
`keyboard/src/plus/assets/model.bkw` (the `plus` flavor only; `core` compiles the decoder out).

The app reads that asset only while the "Experimental swipe model" preference is on, and frees
the decoder holding it -- weights included, they live in it by value -- the moment the
preference goes off. So a replacement checkpoint is picked up by turning the switch off and on
again rather than only by restarting the keyboard, and a file that does not parse disables the
option for that installation instead of failing quietly into the geometric decoder. After
loading, one synthetic gesture is decoded and discarded to warm the model before the settings
row reports it ready.

## What's here

- `train.py` — training entry point. PyTorch, CTC loss + an emission-count regularizer, AdamW
  with warmup+cosine decay. Run as `python -u train.py --device mps` (or `cuda`/`cpu`,
  auto-detected). Supports `--resume` to recover after a crash/kill/forced restart — checkpoints
  (model + optimizer + epoch + step) are written every epoch to `checkpoint.pt`.
- `model.py` — `TcnEncoder` architecture (629,601 parameters in the encoder; the exported weight
  file also carries the 12,640-parameter key embedding, 642,241 in all), `dct_basis`/`key_log_probs`.
- `features_np.py` — feature extraction: 64-point resampling, Savitzky-Golay smoothing.
- `augment.py` — trajectory + keyboard-layout augmentation for training-time data variety.
- `prepare_corpus.py`, `futo_layout.py`, `futo_to_layout.py` — corpus/layout prep, built from the
  MIT-licensed `futo-org/swipe.futo.org` corpus (see `data/`).
- `eval_ctc.py`, `eval_layouts.py` — evaluation harness (decode accuracy, per-layout breakdown).
- `export_weights.py` — converts a trained checkpoint into the binary format
  `keyboard/src/main/cpp/gesture/tcn_weights.*` loads at runtime. **This is the step that actually
  gets a trained model into the app** — training alone does nothing until this runs and the
  output is copied into place.
- `requirements.txt` — `torch>=2.2`, `numpy`, `scipy`, `datasets`, `huggingface_hub`.

## Design notes

- Default: 120 epochs, batch size 1024. Per `docs/licensing.md`'s own estimate for this parameter
  count ("Option B2"): a single mid-range GPU, on the order of days rather than weeks. In practice
  here it's been run detached on Apple Silicon via `--device mps`.
- No launcher/daemonizing script is checked in — runs are detached ad hoc at invocation (e.g.
  `nohup python -u train.py --device mps > train.log 2>&1 &`, or equivalent). `train.log`'s own
  `=== restart (detached, no harness task tracking) ...` markers reflect this: training is meant
  to survive the invoking shell/session closing, and `--resume` is how it picks back up.
- To check on a run in progress: `ps aux | grep train.py` and `tail -f train.log` (progress is one
  `epoch N/120: loss X.XXXX` line per epoch, nothing more granular).

## Status

The shipped checkpoint is the one exported on 2026-09-16 (`model.bkw`, weight-file version 3,
642,241 parameters, about 2.5 MB), trained under the fixed feature scaling -- the runtime
scale-compensation shim an earlier checkpoint needed is gone. To replace it: train with `train.py`, evaluate with
`eval_ctc.py`, export with `export_weights.py`, copy the result over
`keyboard/src/plus/assets/model.bkw`, and let `tools/tcn_replay.py` compare it against the
baseline before committing.

`export_weights.py --out` defaults to this directory, which is gitignored: **the file that
ships is `keyboard/src/plus/assets/model.bkw`**, and it is the one every tool and test should be
pointed at. `native-tests` loads it directly (`BORDERKEYS_SWIPE_MODEL`) and fails if its header
disagrees with `tcn_weights.hpp`, so a stale copy is caught rather than quietly evaluated.

`export_weights.py --upgrade <file>` rewrites an older file's header for the current version,
keeping its payload byte for byte. It needs neither a checkpoint nor torch.

## Models for other scripts

The network takes the key centres as an input, so a model for another script is the same
architecture trained on that script's layout and words. There are no recorded swipes outside
QWERTY, so the training swipes are synthesised, and every figure for these scripts is relative
to the synthesiser.

The plus build ships one model per script layout in `keyboard/src/plus/assets/swipe/<layout>.bkw`
(russian, ukrainian, bulgarian, serbian, macedonian, greek, armenian, georgian, hebrew, arabic);
a layout without one decodes geometrically. The models shipped now are the Latin model
fine-tuned for six epochs on 200,000 swipes per layout made by laying the corpus's own QWERTY
swipes onto the layout's words; they stand until the from-scratch models below are trained and
compared.

- `synthesise.py --generator polyline` draws straight lines through a word's key centres with
  `gesture_replay.py`'s jitter (±14 px) and timing (8–14 ms a sample);
  `--generator flow --flow flow.pt` samples `flow.py`'s learned synthesiser around the same path.
  Words are spelled as the layout can type them (ё→е, ъ→ь, ѝ→и, marks dropped), and every seventh
  word by frequency is held out for evaluation.
- `flow.py train` fits the synthesiser: a conditional rectified-flow model (1.88 M parameters, a
  dilated 1-D convolution with a time embedding) of how a recorded swipe departs from its ideal
  path, in key units, with its pace; sampling takes 32 Euler steps, then a capture pass gives the
  stroke the corpus's durations and sample intervals.
- `train_script.sh <layout> <word list> <generator> "<seeds>" [flow.pt]` synthesises once, trains
  from scratch for 80 epochs per seed (`train.py --seed`), resumes from a checkpoint after a stop,
  logs every epoch to `train_<layout>-<generator>-s<seed>.log`, and exports with
  `export_weights.py --half`: weight-file version 4, the payload as IEEE half floats, 1.3 MB
  instead of 2.6 MB. `tcn_weights.cpp` reads versions 3 and 4.
- `tools/script_swipe_eval.py` measures a model against the geometric decoder on held-out words;
  the layout file is `native-tests/data/<name>_1080.layout`, written by `tools/layout_to_replay.py`.
