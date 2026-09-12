# Swipe-gesture TCN training pipeline

Offline PyTorch training for the swipe-typing model. This is the *training* side only — the
runtime that actually ships in the app is a hand-written C++ inference implementation under
`keyboard/src/main/cpp/gesture/` (`tcn_encoder.*`, `tcn_decoder.*`, `tcn_ctc_decoder.*`,
`tcn_weights.*`, `tcn_features.*`), which reads the weights this pipeline exports. No ML runtime
(TFLite/ONNX/NNAPI) is used anywhere in this project — the model is small enough (~630K params)
that a bespoke C++ decoder was worth writing instead.

**This whole directory is currently untracked in git.** Nothing here has ever been committed.
Whether/when to commit it (and the exported weights) is a decision for whoever's driving that
session, not assumed here — but treat it as at-risk until it is.

## What's here

- `train.py` — training entry point. PyTorch, CTC loss + an emission-count regularizer, AdamW
  with warmup+cosine decay. Run as `python -u train.py --device mps` (or `cuda`/`cpu`,
  auto-detected). Supports `--resume` to recover after a crash/kill/forced restart — checkpoints
  (model + optimizer + epoch + step) are written every epoch to `checkpoint.pt`.
- `model.py` — `TcnEncoder` architecture (629,601 parameters), `dct_basis`/`key_log_probs`.
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

## Status as of 2026-09-12

A training run was in progress: PID 7294, started 2026-09-11 ~23:10 local time, at epoch 97/120
(loss 0.8547, decreasing steadily) as of the last check. **Re-check `ps aux`/`train.log` before
assuming anything about where it landed** — it may have finished, be further along, or have
stopped. If it finished, `export_weights.py` has **not** been run against the result yet as of
this note — the runtime `tcn_weights.*` files have not been regenerated from this run.
