"""Shared adb helpers for the eval harness. Talks to exactly one shared device (see
tools/eval/README.md for the /tmp/itantra-device.lock protocol - acquire it before calling
anything here that touches the phone, release it right after).
"""

from __future__ import annotations

import json
import os
import shlex
import subprocess
import time
from pathlib import Path
from typing import Optional

ADB_BIN = os.environ.get("ADB_BIN", str(Path.home() / "Library/Android/sdk/platform-tools/adb"))
SERIAL = os.environ.get("ITANTRA_ADB_SERIAL", "10BD1C1C7T000HX")
PACKAGE = "org.itantra.app"
COMPONENT = f"{PACKAGE}/.DebugCommandReceiver"
DEVICE_FILES_ROOT = f"/sdcard/Android/data/{PACKAGE}/files"
METRIC_TAG = "ITANTRA_METRIC"
LOCK_PATH = "/tmp/itantra-device.lock"


def adb(*args: str, check: bool = True, timeout: Optional[float] = 60.0) -> subprocess.CompletedProcess:
    cmd = [ADB_BIN, "-s", SERIAL, *args]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, check=check)


def device_online() -> bool:
    try:
        out = subprocess.run([ADB_BIN, "devices"], capture_output=True, text=True, timeout=10).stdout
    except Exception:
        return False
    for line in out.splitlines():
        if line.startswith(SERIAL) and "device" in line.split("\t")[-1:][0]:
            return True
    return False


def require_lock_held():
    if not os.path.isdir(LOCK_PATH):
        raise SystemExit(
            f"[eval] {LOCK_PATH} not held - acquire it first:\n"
            f"  until mkdir {LOCK_PATH} 2>/dev/null; do sleep 10; done"
        )


def thermal_status() -> str:
    """One-line dumpsys thermalservice snapshot (Android throttling status + peak cached SKIN/CPU
    temp), recorded alongside every run per the task's 'phone runs hot' instruction. Android
    PowerManager THERMAL_STATUS: 0=NONE 1=LIGHT 2=MODERATE 3=SEVERE 4=CRITICAL 5=EMERGENCY 6=SHUTDOWN."""
    try:
        r = adb("shell", "dumpsys", "thermalservice", check=False, timeout=20)
        text = r.stdout
    except Exception as e:
        return f"thermal_check_failed: {e}"
    status = None
    max_temp = None
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("Thermal Status:"):
            status = line.split(":", 1)[1].strip()
        elif line.startswith("Temperature{"):
            for part in line.split(","):
                part = part.strip()
                if part.startswith("mValue="):
                    try:
                        v = float(part.split("=", 1)[1])
                        max_temp = v if max_temp is None else max(max_temp, v)
                    except ValueError:
                        pass
    if status is None and max_temp is None:
        return "unknown (dumpsys thermalservice returned no parseable status)"
    return f"thermalStatus={status} maxCachedTempC={max_temp}"


def wake_device() -> None:
    """Wakes the screen and pushes the screen-off timeout way out, and disables Doze/App
    Standby's device-idle throttling for this session. A screen-off/Doze phone is what broke an
    earlier eval run (a long-idle Wi-Fi TCP connection to peer_sim.py got dropped mid-bench).
    Best-effort: failures here are logged but never abort the caller."""
    for cmd in (
        "input keyevent KEYCODE_WAKEUP",
        "settings put system screen_off_timeout 1800000",
        "dumpsys deviceidle disable",
    ):
        try:
            shell(cmd, timeout=15)
        except Exception as e:
            print(f"[eval] wake_device: {cmd!r} failed: {e}")


def push(local: str, remote: str) -> None:
    adb("push", local, remote, timeout=600)


def pull(remote: str, local: str) -> None:
    adb("pull", remote, local, timeout=600)


def shell(cmd: str, timeout: float = 60.0) -> str:
    r = adb("shell", cmd, check=False, timeout=timeout)
    return r.stdout


def logcat_clear() -> None:
    adb("logcat", "-c", timeout=15)


def logcat_dump() -> str:
    r = adb("logcat", "-d", "-v", "raw", "-s", METRIC_TAG, timeout=60)
    return r.stdout


def broadcast(action: str, extras: dict[str, str | bool]) -> None:
    """`adb shell am broadcast -a <action> -n <component> --es k v --ez k v ...` - each extra is
    sent as its own argv element to `adb`, then joined+shell-quoted for the on-device shell so
    Indic/Unicode text with spaces survives the hop (adb shell always re-parses its argv as one
    string on the remote side)."""
    remote_cmd = "am broadcast -a " + shlex.quote(action) + " -n " + COMPONENT
    for k, v in extras.items():
        if isinstance(v, bool):
            remote_cmd += f" --ez {k} {'true' if v else 'false'}"
        else:
            remote_cmd += f" --es {k} {shlex.quote(str(v))}"
    adb("shell", remote_cmd, timeout=120)


def wait_for_metric_event(event_name: str, match: Optional[dict] = None, timeout_s: float = 120.0, poll_s: float = 1.0) -> Optional[dict]:
    """Polls `adb logcat -d` until a JSON line with `event == event_name` (and every key in
    `match` equal) appears; returns the parsed dict or None on timeout."""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        dump = logcat_dump()
        for line in dump.splitlines():
            idx = line.find("{")
            if idx < 0:
                continue
            try:
                obj = json.loads(line[idx:])
            except json.JSONDecodeError:
                continue
            if obj.get("event") != event_name:
                continue
            if match and any(obj.get(k) != v for k, v in match.items()):
                continue
            return obj
        time.sleep(poll_s)
    return None


def collect_metric_events(event_name: str) -> list[dict]:
    out = []
    for line in logcat_dump().splitlines():
        idx = line.find("{")
        if idx < 0:
            continue
        try:
            obj = json.loads(line[idx:])
        except json.JSONDecodeError:
            continue
        if obj.get("event") == event_name:
            out.append(obj)
    return out
