#!/usr/bin/env python3
"""
peer_sim.py - host-side (Mac) test peer for the iTantra walkie-talkie app.

Speaks the exact wire protocol implemented by :transport:
  - Advertises + discovers `_itantra._tcp.` over mDNS (NSD), same as WifiTransport.kt.
  - Connects over plain TCP using the app's framing: a 4-byte big-endian length prefix
    followed by UTF-8 JSON of a `Frame` (see core/src/main/kotlin/org/itantra/core/Messages.kt
    and transport/src/main/kotlin/org/itantra/transport/FrameCodec.kt). The JSON uses a `type`
    class discriminator ("hello"/"msg"/"ack"/"ptt"/"ping"/"pong") and encodes defaults, matching
    kotlinx.serialization's `Json { classDiscriminator = "type"; encodeDefaults = true }`.
  - Sends Hello on connect, answers Ping with Pong, prints every received Msg/Ack with latency,
    and can push a text message into the mesh with --send.

Requires: Python 3.9+ stdlib, and `zeroconf` (install into tools/peer/.venv):
    python3 -m venv tools/peer/.venv
    tools/peer/.venv/bin/pip install zeroconf

Usage:
    tools/peer/.venv/bin/python tools/peer_sim.py
    tools/peer/.venv/bin/python tools/peer_sim.py --send "namaste, kaise ho" --lang hi
    tools/peer/.venv/bin/python tools/peer_sim.py --send "evacuate now" --lang en --alert
    tools/peer/.venv/bin/python tools/peer_sim.py --timeout 30 --device-id mac-peer-1
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import socket
import struct
import subprocess
import sys
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

try:
    from zeroconf import ServiceInfo, ServiceListener, Zeroconf
except ImportError:
    print(
        "Missing 'zeroconf'. Install it into the project venv:\n"
        "  python3 -m venv tools/peer/.venv\n"
        "  tools/peer/.venv/bin/pip install zeroconf\n"
        "then run with tools/peer/.venv/bin/python tools/peer_sim.py",
        file=sys.stderr,
    )
    raise

SERVICE_TYPE = "_itantra._tcp.local."
MAX_FRAME_BYTES = 64 * 1024

# ---- --bench: adb-driven end-to-end latency bench (Mac plays the second phone) ----
ADB_BIN = os.environ.get("ADB_BIN", str(Path.home() / "Library/Android/sdk/platform-tools/adb"))
ADB_SERIAL = os.environ.get("ITANTRA_ADB_SERIAL", "10BD1C1C7T000HX")
DEVICE_FILES_ROOT = "/sdcard/Android/data/org.itantra.app/files"
DEBUG_COMPONENT = "org.itantra.app/.DebugCommandReceiver"


# ---- Wire framing: 4-byte big-endian length + UTF-8 JSON Frame (FrameCodec.kt) ----


def encode_frame(obj: dict) -> bytes:
    payload = json.dumps(obj, separators=(",", ":")).encode("utf-8")
    if len(payload) > MAX_FRAME_BYTES:
        raise ValueError(f"frame too large: {len(payload)} bytes")
    return struct.pack(">I", len(payload)) + payload


def read_exact(sock: socket.socket, n: int) -> Optional[bytes]:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            return None  # clean EOF
        buf.extend(chunk)
    return bytes(buf)


def read_frame(sock: socket.socket) -> Optional[dict]:
    header = read_exact(sock, 4)
    if header is None:
        return None
    (length,) = struct.unpack(">I", header)
    if length < 0 or length > MAX_FRAME_BYTES:
        raise ValueError(f"invalid/oversize frame length: {length}")
    body = read_exact(sock, length)
    if body is None:
        raise ConnectionError("EOF mid-frame")
    return json.loads(body.decode("utf-8"))


def write_frame(sock: socket.socket, obj: dict) -> None:
    sock.sendall(encode_frame(obj))


# ---- Frame builders, matching core/Messages.kt field-for-field ----


def frame_hello(device_id: str, name: str) -> dict:
    return {"type": "hello", "deviceId": device_id, "name": name, "protocol": 1}


def frame_msg(device_id: str, text: str, lang: str, alert: bool, ssml: bool) -> dict:
    now = now_ms()
    return {
        "type": "msg",
        "message": {
            "id": str(uuid.uuid4()),
            "from": device_id,
            "lang": lang.upper(),
            "text": text,
            "priority": "ALERT" if alert else "NORMAL",
            "emotion": None,
            "ssml": ssml,
            "speechEndAt": now,
            "sttDoneAt": now,
            "sentAt": now,
        },
    }


def frame_ack(msg_id: str, received_at: int, play_started_at: int = 0) -> dict:
    return {"type": "ack", "id": msg_id, "receivedAt": received_at, "playStartedAt": play_started_at}


def frame_ping(t0: int) -> dict:
    return {"type": "ping", "t0": t0}


def frame_pong(t0: int, t1: int) -> dict:
    return {"type": "pong", "t0": t0, "t1": t1}


def now_ms() -> int:
    return int(time.time() * 1000)


# ---- mDNS discovery of the phone's `_itantra._tcp.` service (mirrors WifiTransport's NSD) ----


def _service_name_to_device_id(name: str) -> str:
    """Strips the `._itantra._tcp.local.` suffix zeroconf appends to the instance name -
    Android's NsdServiceInfo.serviceName (== deviceId, see WifiTransport.registerService)."""
    suffix = "." + SERVICE_TYPE
    return name[: -len(suffix)] if name.endswith(suffix) else name


@dataclass
class DiscoveredPeer:
    name: str
    device_id: str
    address: str
    port: int


class _Listener(ServiceListener):
    def __init__(self, zc: Zeroconf, on_found):
        self._zc = zc
        self._on_found = on_found

    def add_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        info = zc.get_service_info(type_, name, timeout=3000)
        if info is None or not info.addresses:
            return
        address = socket.inet_ntoa(info.addresses[0])
        self._on_found(DiscoveredPeer(name=name, device_id=_service_name_to_device_id(name), address=address, port=info.port))

    def update_service(self, zc: Zeroconf, type_: str, name: str) -> None:  # pragma: no cover
        pass

    def remove_service(self, zc: Zeroconf, type_: str, name: str) -> None:  # pragma: no cover
        pass


def discover_peer(zc: Zeroconf, timeout_s: float, own_service_name: str) -> Optional[DiscoveredPeer]:
    found: "list[DiscoveredPeer]" = []
    lock = threading.Event()

    def on_found(peer: DiscoveredPeer):
        if peer.name == own_service_name:
            return  # don't connect to ourselves
        found.append(peer)
        lock.set()

    from zeroconf import ServiceBrowser

    listener = _Listener(zc, on_found)
    browser = ServiceBrowser(zc, SERVICE_TYPE, listener)
    lock.wait(timeout_s)
    browser.cancel()
    return found[0] if found else None


def advertise_self(zc: Zeroconf, device_id: str, port: int, local_ip: str) -> ServiceInfo:
    info = ServiceInfo(
        SERVICE_TYPE,
        f"{device_id}.{SERVICE_TYPE}",
        addresses=[socket.inet_aton(local_ip)],
        port=port,
        properties={},
    )
    zc.register_service(info)
    return info


def local_ip_guess() -> str:
    """Best-effort LAN IP: opens a UDP socket to a public address without sending anything."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


# ---- Peer session: reader thread + Hello/Ping-Pong/Msg-Ack handling ----


@dataclass
class PendingSend:
    msg_id: str
    sent_at_ms: int


@dataclass
class Session:
    sock: socket.socket
    device_id: str
    pending: dict = field(default_factory=dict)  # msg_id -> PendingSend
    stop: threading.Event = field(default_factory=threading.Event)
    # --bench bookkeeping (see run_bench()): appended to by the reader thread, polled by main.
    msg_events: list = field(default_factory=list)  # (recv_at_mac_ms, message_dict)
    ack_results: dict = field(default_factory=dict)  # msg_id -> {receivedAt, playStartedAt, sent_at_mac_ms, recv_at_mac_ms}
    pong_samples: list = field(default_factory=list)  # (t0_sent_mac_ms, t1_phone_ms, t2_recv_mac_ms)

    def reader_loop(self) -> None:
        try:
            while not self.stop.is_set():
                frame = read_frame(self.sock)
                if frame is None:
                    print("[peer_sim] peer closed the connection (clean EOF)")
                    break
                self._handle(frame)
        except (ConnectionError, OSError, ValueError) as e:
            if not self.stop.is_set():
                print(f"[peer_sim] connection error: {e}")
        finally:
            self.stop.set()

    def _handle(self, frame: dict) -> None:
        ftype = frame.get("type")
        recv_at = now_ms()
        if ftype == "hello":
            print(f"[peer_sim] <- hello: deviceId={frame.get('deviceId')} name={frame.get('name')} protocol={frame.get('protocol')}")
        elif ftype == "ping":
            t0 = frame.get("t0", recv_at)
            write_frame(self.sock, frame_pong(t0, recv_at))
            print(f"[peer_sim] <- ping t0={t0} -> replied pong")
        elif ftype == "pong":
            t0, t1 = frame.get("t0"), frame.get("t1")
            rtt = recv_at - t0 if t0 is not None else None
            print(f"[peer_sim] <- pong t0={t0} t1={t1} rttMs={rtt}")
            if t0 is not None and t1 is not None:
                self.pong_samples.append((t0, t1, recv_at))
        elif ftype == "msg":
            message = frame.get("message", {})
            print(
                f"[peer_sim] <- msg id={message.get('id')} from={message.get('from')} "
                f"lang={message.get('lang')} priority={message.get('priority')} text={message.get('text')!r}"
            )
            write_frame(self.sock, frame_ack(message.get("id"), recv_at))
            print(f"[peer_sim] -> ack id={message.get('id')}")
            self.msg_events.append((recv_at, message))
        elif ftype == "ack":
            msg_id = frame.get("id")
            pending = self.pending.pop(msg_id, None)
            play_started_at = frame.get("playStartedAt", 0)
            if pending is not None:
                latency_ms = recv_at - pending.sent_at_ms
                print(
                    f"[peer_sim] <- ack id={msg_id} receivedAt={frame.get('receivedAt')} "
                    f"playStartedAt={play_started_at} roundTripMs={latency_ms}"
                    + (" (TTS did not play: playStartedAt=0, e.g. models missing)" if play_started_at == 0 else "")
                )
                self.ack_results[msg_id] = {
                    "receivedAt": frame.get("receivedAt"),
                    "playStartedAt": play_started_at,
                    "sent_at_mac_ms": pending.sent_at_ms,
                    "recv_at_mac_ms": recv_at,
                }
            else:
                print(f"[peer_sim] <- ack id={msg_id} (no matching pending send)")
        elif ftype == "ptt":
            print(f"[peer_sim] <- ptt talking={frame.get('talking')}")
        else:
            print(f"[peer_sim] <- unknown frame: {frame}")

    def send_msg(self, text: str, lang: str, alert: bool, ssml: bool) -> str:
        frame = frame_msg(self.device_id, text, lang, alert, ssml)
        msg_id = frame["message"]["id"]
        self.pending[msg_id] = PendingSend(msg_id=msg_id, sent_at_ms=now_ms())
        write_frame(self.sock, frame)
        print(f"[peer_sim] -> msg id={msg_id} lang={lang} alert={alert} text={text!r}")
        return msg_id

    def send_ping(self) -> None:
        write_frame(self.sock, frame_ping(now_ms()))


# ---- --bench helpers ----


def percentile(values: list[float], p: float) -> Optional[float]:
    if not values:
        return None
    s = sorted(values)
    k = (len(s) - 1) * (p / 100.0)
    f, c = int(k), min(int(k) + 1, len(s) - 1)
    if f == c:
        return s[f]
    return s[f] + (s[c] - s[f]) * (k - f)


def median(values: list[float]) -> Optional[float]:
    return percentile(values, 50)


def adb_run(*args: str, timeout: float = 60.0) -> subprocess.CompletedProcess:
    return subprocess.run([ADB_BIN, "-s", ADB_SERIAL, *args], capture_output=True, text=True, timeout=timeout)


def adb_broadcast(action: str, extras: dict[str, str]) -> None:
    import shlex

    remote_cmd = f"am broadcast -a {shlex.quote(action)} -n {DEBUG_COMPONENT}"
    for k, v in extras.items():
        remote_cmd += f" --es {k} {shlex.quote(str(v))}"
    adb_run("shell", remote_cmd, timeout=60.0)


def adb_wake_device() -> None:
    """Wakes the screen, extends screen-off timeout, and disables Doze for this session - a
    screen-off/idle phone dropped the Wi-Fi TCP connection mid-bench in an earlier run (see
    tools/eval/_adb.py:wake_device(), duplicated here for the same standalone-peer_sim reason)."""
    for cmd in ("input keyevent KEYCODE_WAKEUP", "settings put system screen_off_timeout 1800000", "dumpsys deviceidle disable"):
        try:
            adb_run("shell", cmd, timeout=15.0)
        except Exception as e:
            print(f"[bench] wake_device: {cmd!r} failed: {e}")


def adb_thermal_status() -> str:
    """See tools/eval/_adb.py:thermal_status() for the same parsing (kept duplicated here so
    peer_sim.py stays runnable standalone, without tools/eval on sys.path)."""
    try:
        r = adb_run("shell", "dumpsys", "thermalservice", timeout=20.0)
    except Exception as e:
        return f"thermal_check_failed: {e}"
    status, max_temp = None, None
    for line in r.stdout.splitlines():
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


def load_stt_manifest(path: Path) -> dict[str, list[dict]]:
    """Groups tools/eval/fetch_fleurs.py's combined manifest.tsv rows (filename like
    "hi/clip_000.wav", lang, transcript, ...) by language."""
    by_lang: dict[str, list[dict]] = {}
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            by_lang.setdefault(row["lang"], []).append(row)
    return by_lang


def sync_clock_offset(session: Session, rounds: int = 5, timeout: float = 8.0) -> Optional[float]:
    """NTP-style: sends `rounds` Pings, averages t1_phone - (t0_sent+t2_recv)/2 over the replies.
    Returns the median offset in ms (phone_clock - mac_clock), or None if no Pongs arrived."""
    baseline = len(session.pong_samples)
    for _ in range(rounds):
        session.send_ping()
        time.sleep(0.15)
    deadline = time.time() + timeout
    while len(session.pong_samples) < baseline + rounds and time.time() < deadline:
        time.sleep(0.1)
    samples = session.pong_samples[baseline:]
    if not samples:
        return None
    offsets = sorted(t1 - (t0 + t2) / 2.0 for t0, t1, t2 in samples)
    return offsets[len(offsets) // 2]


def run_bench(session: Session, n: int, manifest_path: Path, out_path: Path, langs_filter: Optional[list[str]], per_step_timeout: float) -> None:
    """--bench N: estimates "sentence said on phone -> audio starts on the other phone" using the
    Mac as the second phone (see tools/eval/README.md for the full explanation):
      (a) phone -> Mac: adb-triggers DEBUG_STT_WAV on the phone for real FLEURS clips; the app's
          normal orchestrator forwards the recognized sentence as a Frame.Msg, same as if it had
          been spoken live. speechEndAt/sttDoneAt/sentAt travel on the wire (VoiceMessage), so no
          logcat parsing is needed here - just clock-offset-corrected arithmetic on the frame.
      (b) Mac -> phone: sends N Msg frames per language (the manifest's own transcripts) and
          collects Acks, which carry receivedAt/playStartedAt (both phone-clock).
    Combined estimate = phoneA sttLatency + phoneA network + phoneB receiver-TTS-start, per
    language - an approximation of a real two-phone conversation using one physical phone in
    both roles, not a simultaneous two-device measurement.
    """
    adb_wake_device()
    print("[bench] syncing clock offset (phone vs Mac) via Ping/Pong ...")
    offset = sync_clock_offset(session)
    if offset is None:
        print("[bench] WARNING: no Pong received; proceeding with offset=0 (results will include phone/Mac clock skew)")
        offset = 0.0
    else:
        print(f"[bench] clock offset (phone_clock - mac_clock) ~= {offset:.1f} ms")

    thermal_before = adb_thermal_status()
    print(f"[bench] thermal before: {thermal_before}")

    by_lang = load_stt_manifest(manifest_path)
    langs = langs_filter or sorted(by_lang.keys())

    phase_a: dict[str, list[dict]] = {}
    phase_b: dict[str, list[dict]] = {}

    # A dropped TCP connection (e.g. the phone's screen locking mid-run and Wi-Fi going into
    # power-save) must not lose every result gathered so far - each row is best-effort, and both
    # phases bail out early (not raise) once the connection is visibly dead.
    print(f"[bench] phase (a) phone -> Mac: {n} clip(s)/lang via DEBUG_STT_WAV ...")
    for lang in langs:
        if session.stop.is_set():
            print("[bench] connection dropped - stopping phase (a) early")
            break
        rows = by_lang.get(lang, [])[:n]
        results = []
        for row in rows:
            if session.stop.is_set():
                print("[bench] connection dropped - stopping phase (a) early")
                break
            try:
                remote_path = f"{DEVICE_FILES_ROOT}/models/eval/stt/{row['filename']}"
                before = len(session.msg_events)
                adb_broadcast("org.itantra.DEBUG_STT_WAV", {"path": remote_path, "lang": lang})
                deadline = time.time() + per_step_timeout
                while len(session.msg_events) <= before and time.time() < deadline and not session.stop.is_set():
                    time.sleep(0.1)
                if len(session.msg_events) <= before:
                    print(f"[bench]   {row['filename']}: TIMEOUT/no-connection waiting for Frame.Msg")
                    continue
                recv_at_mac, message = session.msg_events[-1]
                stt_latency_ms = message.get("sttDoneAt", 0) - message.get("speechEndAt", 0)
                speech_end_to_received_ms = recv_at_mac - (message.get("speechEndAt", 0) - offset)
                network_ms = recv_at_mac - (message.get("sentAt", 0) - offset)
                results.append(
                    {
                        "filename": row["filename"], "sttLatencyMs": stt_latency_ms,
                        "networkMs": network_ms, "speechEndToReceivedMs": speech_end_to_received_ms,
                    }
                )
                print(f"[bench]   {row['filename']}: sttLatencyMs={stt_latency_ms} networkMs={network_ms:.0f} speechEnd->receivedMs={speech_end_to_received_ms:.0f}")
            except OSError as e:
                print(f"[bench]   {row['filename']}: connection error ({e}), skipping")
        phase_a[lang] = results

    print(f"[bench] phase (b) Mac -> phone: {n} msg(s)/lang, collecting Acks ...")
    for lang in langs:
        if session.stop.is_set():
            print("[bench] connection dropped - stopping phase (b) early")
            break
        rows = by_lang.get(lang, [])[:n]
        results = []
        for row in rows:
            if session.stop.is_set():
                print("[bench] connection dropped - stopping phase (b) early")
                break
            try:
                text = row.get("transcript") or f"test message {row['filename']}"
                msg_id = session.send_msg(text, lang, alert=False, ssml=False)
                deadline = time.time() + per_step_timeout
                while msg_id not in session.ack_results and time.time() < deadline and not session.stop.is_set():
                    time.sleep(0.1)
                ack = session.ack_results.pop(msg_id, None)
                if ack is None:
                    print(f"[bench]   {lang} msg {msg_id}: TIMEOUT/no-connection waiting for Ack")
                    continue
                if not ack["playStartedAt"]:
                    print(f"[bench]   {lang} msg {msg_id}: TTS did not play (playStartedAt=0; models missing?)")
                    continue
                tts_latency_ms = ack["playStartedAt"] - ack["receivedAt"]
                sent_to_play_ms = (ack["playStartedAt"] - offset) - ack["sent_at_mac_ms"]
                results.append({"msg_id": msg_id, "ttsLatencyMs": tts_latency_ms, "sentToPlayMs": sent_to_play_ms})
                print(f"[bench]   {lang} msg {msg_id}: receiverTtsLatencyMs={tts_latency_ms} sent->playMs={sent_to_play_ms:.0f}")
            except OSError as e:
                print(f"[bench]   {lang} msg: connection error ({e}), skipping")
        phase_b[lang] = results

    thermal_after = adb_thermal_status()
    print(f"[bench] thermal after: {thermal_after}")

    summary: dict[str, dict] = {}
    for lang in langs:
        a, b = phase_a.get(lang, []), phase_b.get(lang, [])
        stt_lat = [r["sttLatencyMs"] for r in a]
        net = [r["networkMs"] for r in a]
        tts_lat = [r["ttsLatencyMs"] for r in b]
        combined_ms = (
            (median(stt_lat) + median(net) + median(tts_lat)) if (stt_lat and net and tts_lat) else None
        )
        summary[lang] = {
            "n_phaseA": len(a), "n_phaseB": len(b),
            "sttLatencyMs_p50": percentile(stt_lat, 50), "sttLatencyMs_p95": percentile(stt_lat, 95),
            "networkMs_p50": percentile(net, 50), "networkMs_p95": percentile(net, 95),
            "receiverTtsLatencyMs_p50": percentile(tts_lat, 50), "receiverTtsLatencyMs_p95": percentile(tts_lat, 95),
            "speechEndToReceivedMs_p50": percentile([r["speechEndToReceivedMs"] for r in a], 50),
            "sentToPlayMs_p50": percentile([r["sentToPlayMs"] for r in b], 50),
            "estimatedSpeechSaidToAudioStartedMs": combined_ms,
        }

    out = {
        "clockOffsetMsPhoneMinusMac": offset, "thermal_before": thermal_before, "thermal_after": thermal_after,
        "by_lang": summary, "raw": {"phase_a": phase_a, "phase_b": phase_b},
        "note": "estimatedSpeechSaidToAudioStartedMs = median(phaseA sttLatency) + median(phaseA network) + median(phaseB receiverTtsLatency); phaseA and phaseB use the same physical phone in sender/receiver roles sequentially (only one phone available), not two phones simultaneously.",
    }
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(out, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"[bench] wrote {out_path}")


def main() -> int:
    parser = argparse.ArgumentParser(description="iTantra host-side test peer (mDNS + TCP framing)")
    parser.add_argument("--device-id", default=f"mac-peer-{uuid.uuid4().hex[:6]}", help="this peer's deviceId")
    parser.add_argument("--name", default="peer_sim", help="this peer's display name (sent in Hello)")
    parser.add_argument("--port", type=int, default=0, help="TCP listen port (0 = pick any free port)")
    parser.add_argument("--discover-timeout", type=float, default=20.0, help="seconds to wait for a phone to be discovered")
    parser.add_argument("--timeout", type=float, default=60.0, help="seconds to stay connected before exiting")
    parser.add_argument("--send", metavar="TEXT", help="send one Msg after connecting, then keep listening for its Ack")
    parser.add_argument("--lang", default="hi", help="ISO 639-1 code for --send, e.g. hi/gu/mr/kn/ml/ta/te/or/bn/en")
    parser.add_argument("--alert", action="store_true", help="mark --send as Priority.ALERT")
    parser.add_argument("--ssml", action="store_true", help="mark --send's text as an SSML document")
    parser.add_argument("--bench", type=int, default=0, metavar="N", help="run the adb-driven end-to-end latency bench with N samples/language (see run_bench())")
    parser.add_argument("--bench-manifest", default=str(Path(__file__).resolve().parents[1] / "models" / "eval" / "stt" / "manifest.tsv"), help="tools/eval/fetch_fleurs.py combined manifest.tsv")
    parser.add_argument("--bench-out", default=str(Path(__file__).resolve().parents[1] / "models" / "eval" / "results" / "e2e_bench.json"))
    parser.add_argument("--bench-langs", default=None, help="comma-separated subset of languages (default: all in the manifest)")
    parser.add_argument("--bench-step-timeout", type=float, default=20.0, help="seconds to wait for each Frame.Msg/Ack in the bench")
    args = parser.parse_args()
    if args.bench and args.timeout < 60.0:
        args.timeout = 60.0  # --bench drives the session itself; the outer timeout is just a safety net

    zc = Zeroconf()
    server: Optional[socket.socket] = None
    session: Optional[Session] = None
    service_info: Optional[ServiceInfo] = None
    own_service_name = f"{args.device_id}.{SERVICE_TYPE}"

    try:
        local_ip = local_ip_guess()
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("0.0.0.0", args.port))
        server.listen(1)
        listen_port = server.getsockname()[1]
        server.settimeout(1.0)

        print(f"[peer_sim] deviceId={args.device_id} listening on {local_ip}:{listen_port}, advertising {SERVICE_TYPE}")
        service_info = advertise_self(zc, args.device_id, listen_port, local_ip)

        print(f"[peer_sim] discovering a phone advertising {SERVICE_TYPE} (up to {args.discover_timeout:.0f}s)...")
        peer = discover_peer(zc, args.discover_timeout, own_service_name)

        # Mirror WifiTransport.kt's dialer rule exactly (lexicographically smaller deviceId
        # dials; the other side just accepts) so we never race the phone into forming two TCP
        # connections at once.
        we_should_dial = peer is not None and args.device_id < peer.device_id

        sock: Optional[socket.socket] = None
        if we_should_dial:
            print(f"[peer_sim] found peer {peer.device_id} at {peer.address}:{peer.port}; our id sorts lower, dialing...")
            try:
                sock = socket.create_connection((peer.address, peer.port), timeout=8.0)
            except OSError as e:
                print(f"[peer_sim] dial failed ({e}); falling back to waiting for the phone to dial us")
                sock = None
        elif peer is not None:
            print(f"[peer_sim] found peer {peer.device_id}; its id sorts lower, so it dials us - waiting to accept")
        else:
            print("[peer_sim] no peer discovered via mDNS yet; waiting for the phone to dial us instead")

        if sock is None:
            accept_deadline = time.time() + max(args.discover_timeout, args.timeout)
            while sock is None and time.time() < accept_deadline:
                try:
                    sock, addr = server.accept()
                    print(f"[peer_sim] accepted inbound connection from {addr}")
                except socket.timeout:
                    continue
            if sock is None:
                print("[peer_sim] no phone connected (neither dial nor accept succeeded) - giving up")
                return 1

        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        session = Session(sock=sock, device_id=args.device_id)
        reader_thread = threading.Thread(target=session.reader_loop, daemon=True)
        reader_thread.start()

        write_frame(sock, frame_hello(args.device_id, args.name))
        print(f"[peer_sim] -> hello deviceId={args.device_id} name={args.name}")

        if args.send:
            time.sleep(0.3)  # let Hello land first
            session.send_msg(args.send, args.lang, args.alert, args.ssml)

        if args.bench:
            time.sleep(0.3)  # let Hello land first
            langs_filter = [l.strip() for l in args.bench_langs.split(",")] if args.bench_langs else None
            run_bench(session, args.bench, Path(args.bench_manifest), Path(args.bench_out), langs_filter, args.bench_step_timeout)

        if not args.bench:
            deadline = time.time() + args.timeout
            while time.time() < deadline and not session.stop.is_set():
                time.sleep(0.2)

        if args.send:
            still_pending = list(session.pending.keys())
            if still_pending:
                print(f"[peer_sim] WARNING: no Ack received for {still_pending} within {args.timeout:.0f}s")

        session.stop.set()
        return 0
    finally:
        if session is not None:
            try:
                session.sock.close()
            except OSError:
                pass
        if server is not None:
            server.close()
        if service_info is not None:
            zc.unregister_service(service_info)
        zc.close()


if __name__ == "__main__":
    raise SystemExit(main())
