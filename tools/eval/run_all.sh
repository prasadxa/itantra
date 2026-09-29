#!/usr/bin/env bash
# run_all.sh - runs the full iTantra eval harness end-to-end and writes docs/eval-results.md.
#
# Owned by this agent (app/src/debug/**, tools/eval/**, tools/peer_sim.py). See README.md for the
# shared-device protocol and what each phase measures.
#
# Usage:
#   tools/eval/run_all.sh                 # full run (30 clips/lang STT, 25 sentences/lang TTS, bench N=5)
#   tools/eval/run_all.sh --quick         # small smoke run (3/3/2) - use to sanity-check the harness
#   SKIP_FETCH=1 tools/eval/run_all.sh    # reuse whatever's already in models/eval/stt
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

PY="$ROOT/tools/.venv/bin/python"
PEER_PY="$ROOT/tools/peer/.venv/bin/python"
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
SERIAL="${ITANTRA_ADB_SERIAL:-10BD1C1C7T000HX}"
LOCK=/tmp/itantra-device.lock
RESULTS_DIR="$ROOT/models/eval/results"
mkdir -p "$RESULTS_DIR"

N_STT=30
N_TTS_FLEURS=20
N_BENCH=5
if [[ "${1:-}" == "--quick" ]]; then
  N_STT=3; N_TTS_FLEURS=3; N_BENCH=2
  echo "[run_all] --quick: N_STT=$N_STT N_TTS_FLEURS=$N_TTS_FLEURS N_BENCH=$N_BENCH"
fi

log() { echo "[run_all] $*"; }

acquire_lock() {
  log "acquiring device lock ($LOCK) ..."
  until mkdir "$LOCK" 2>/dev/null; do sleep 10; done
  log "lock acquired"
}
release_lock() {
  rmdir "$LOCK" 2>/dev/null || true
  log "lock released"
}
trap release_lock EXIT

# ---- Step 0: build + test set (no device needed) ----

log "building debug APK (./gradlew assembleDebug) ..."
if ! ./gradlew assembleDebug -q; then
  log "BUILD FAILED - aborting (fix app/tts/text/core compile errors first; this script does not touch those modules)"
  exit 1
fi

if [[ "${SKIP_FETCH:-0}" != "1" ]]; then
  log "fetching FLEURS STT test set (N=$N_STT/lang, streamed, stops early) ..."
  "$PY" tools/eval/fetch_fleurs.py --n "$N_STT" || { log "fetch_fleurs.py failed"; exit 1; }
else
  log "SKIP_FETCH=1: reusing existing models/eval/stt/"
fi

# ---- Step 1: device-dependent phases, all under one lock hold ----

acquire_lock

"$ADB" -s "$SERIAL" get-state >/dev/null 2>&1
if [[ $? -ne 0 ]]; then
  log "device $SERIAL not reachable (adb get-state failed) - skipping device phases"
  DEVICE_OK=0
else
  DEVICE_OK=1
fi

if [[ "$DEVICE_OK" == "1" ]]; then
  log "installing debug APK ..."
  "$ADB" -s "$SERIAL" install -r -t app/build/outputs/apk/debug/app-debug.apk || log "install failed (continuing; may already be current)"
  log "launching MainActivity to start TalkService ..."
  "$ADB" -s "$SERIAL" shell am start -n org.itantra.app/.MainActivity >/dev/null
  sleep 4

  log "=== phase 1: STT batch (accuracy + latency) ==="
  "$PY" tools/eval/run_stt_batch.py --results "$RESULTS_DIR/stt_batch.jsonl" || log "run_stt_batch.py failed"

  log "=== phase 2: TTS intelligibility ==="
  "$PY" tools/eval/tts_intelligibility.py --n-fleurs "$N_TTS_FLEURS" --out "$RESULTS_DIR/tts_intel.json" || log "tts_intelligibility.py failed"

  log "=== phase 3: end-to-end latency bench (Mac as second phone) ==="
  if [[ -x "$PEER_PY" ]]; then
    "$PEER_PY" tools/peer_sim.py --device-id mac-eval --bench "$N_BENCH" \
      --bench-manifest "$ROOT/models/eval/stt/manifest.tsv" --bench-out "$RESULTS_DIR/e2e_bench.json" \
      --discover-timeout 25 || log "peer_sim.py --bench failed (is the app on-screen/foreground with Wi-Fi transport active?)"
  else
    log "tools/peer/.venv not found (need: python3 -m venv tools/peer/.venv && tools/peer/.venv/bin/pip install zeroconf) - skipping bench"
  fi
else
  log "skipping phases 1-3 (no device)"
fi

release_lock
trap - EXIT

# ---- Step 2: scoring + report (no device needed) ----

if [[ -f "$RESULTS_DIR/stt_batch.jsonl" ]]; then
  log "scoring STT results ..."
  "$PY" tools/eval/score_stt.py --results "$RESULTS_DIR/stt_batch.jsonl" --manifest "$ROOT/models/eval/stt/manifest.tsv" --out "$RESULTS_DIR/stt_scores.json" >/dev/null
fi

log "writing docs/eval-results.md ..."
"$PY" tools/eval/make_report.py --results-dir "$RESULTS_DIR" --out "$ROOT/docs/eval-results.md"

log "done. See docs/eval-results.md"
