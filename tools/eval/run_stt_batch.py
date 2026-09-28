#!/usr/bin/env python3
"""run_stt_batch.py - pushes models/eval/stt/** to the phone, fires DEBUG_STT_BATCH, waits for
completion, and dumps the per-clip debug_stt_batch_clip events to a JSONL file for score_stt.py.

Must run with the shared-device lock held (see tools/eval/README.md).

Usage:
    tools/.venv/bin/python tools/eval/run_stt_batch.py \
        --local-dir models/eval/stt --results models/eval/results/stt_batch.jsonl
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _adb  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--local-dir", default=str(Path(__file__).resolve().parents[2] / "models" / "eval" / "stt"))
    ap.add_argument("--results", default=str(Path(__file__).resolve().parents[2] / "models" / "eval" / "results" / "stt_batch.jsonl"))
    ap.add_argument("--timeout", type=float, default=600.0, help="max seconds to wait for debug_stt_batch_done")
    ap.add_argument("--skip-push", action="store_true", help="reuse whatever's already on-device (repeat runs)")
    args = ap.parse_args()

    _adb.require_lock_held()
    if not _adb.device_online():
        print(f"[run_stt_batch] device {_adb.SERIAL} not online (adb devices shows nothing) - aborting", file=sys.stderr)
        return 1

    local_dir = Path(args.local_dir)
    remote_dir = f"{_adb.DEVICE_FILES_ROOT}/models/eval/stt"
    _adb.wake_device()
    thermal_before = _adb.thermal_status()
    print(f"[run_stt_batch] thermal before: {thermal_before}")

    if not args.skip_push:
        print(f"[run_stt_batch] pushing {local_dir} -> {remote_dir} ...")
        _adb.shell(f"mkdir -p {remote_dir}")
        _adb.push(str(local_dir), f"{_adb.DEVICE_FILES_ROOT}/models/eval/")

    manifest_remote = f"{remote_dir}/manifest.tsv"
    _adb.logcat_clear()
    t0 = time.time()
    print(f"[run_stt_batch] broadcasting DEBUG_STT_BATCH manifest={manifest_remote} ...")
    _adb.broadcast("org.itantra.DEBUG_STT_BATCH", {"manifest": manifest_remote})

    done = _adb.wait_for_metric_event("debug_stt_batch_done", timeout_s=args.timeout)
    elapsed = time.time() - t0
    thermal_after = _adb.thermal_status()
    print(f"[run_stt_batch] thermal after: {thermal_after}")

    if done is None:
        print(f"[run_stt_batch] TIMEOUT waiting for debug_stt_batch_done after {args.timeout}s", file=sys.stderr)
        return 1
    print(f"[run_stt_batch] done: {done} (wall {elapsed:.1f}s)")

    clips = _adb.collect_metric_events("debug_stt_batch_clip")
    out_path = Path(args.results)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        for c in clips:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")
    print(f"[run_stt_batch] wrote {len(clips)} clip events -> {out_path}")

    meta_path = out_path.with_suffix(".meta.json")
    meta_path.write_text(
        json.dumps(
            {"thermal_before": thermal_before, "thermal_after": thermal_after, "wall_seconds": elapsed, "done_event": done},
            indent=2,
        ),
        encoding="utf-8",
    )
    return 0 if len(clips) > 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
