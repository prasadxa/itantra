#!/usr/bin/env python3
"""
demo.py - exercises the gateway end-to-end without a real phone.

Spins up two in-process "fake phones" that speak the exact same wire protocol as
tools/peer_sim.py (mDNS `_itantra._tcp.` + 4-byte-length-prefixed JSON frames), lets the
real Gateway discover and connect to both of them, then:
  1. each fake phone sends a few Msg frames (one ALERT) in different languages
  2. the gateway broadcasts a message to all connected phones
  3. the gateway ingests a sample CAP 1.2 alert and sends it as an ALERT
  4. prints what ended up in SQLite (message counts, per-language counts, latency)

Run: tools/gateway/.venv/bin/python tools/gateway/demo.py
"""

from __future__ import annotations

import socket
import sys
import threading
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from frames import (  # noqa: E402
    SERVICE_TYPE,
    advertise_self,
    frame_ack,
    frame_hello,
    frame_msg,
    frame_pong,
    local_ip_guess,
    now_ms,
    read_frame,
    write_frame,
)
from gateway import Gateway  # noqa: E402
from zeroconf import Zeroconf  # noqa: E402


class FakePhone:
    """A minimal stand-in for the Android app: advertises `_itantra._tcp.`, accepts the
    gateway's inbound connection (the gateway always dials, since its "gateway-..."
    deviceId sorts before "phone-..." lexicographically - see WifiTransport's/peer_sim's
    dial rule), answers Hello/Ping, and auto-Acks every Msg like a phone that played it."""

    def __init__(self, device_id: str, name: str):
        self.device_id = device_id
        self.name = name
        self._zc = Zeroconf()
        self._server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._server.bind(("0.0.0.0", 0))
        self._server.listen(1)
        self._server.settimeout(1.0)
        self.port = self._server.getsockname()[1]
        self._service_info = None
        self._sock: socket.socket | None = None
        self._stop = threading.Event()
        self.received: list[dict] = []
        self._threads: list[threading.Thread] = []

    def start(self) -> None:
        local_ip = local_ip_guess()
        self._service_info = advertise_self(self._zc, self.device_id, self.port, local_ip)
        t = threading.Thread(target=self._accept_loop, daemon=True)
        t.start()
        self._threads.append(t)

    def _accept_loop(self) -> None:
        while not self._stop.is_set():
            try:
                sock, _addr = self._server.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self._sock = sock
            write_frame(sock, frame_hello(self.device_id, self.name))
            t = threading.Thread(target=self._reader_loop, args=(sock,), daemon=True)
            t.start()
            self._threads.append(t)

    def _reader_loop(self, sock: socket.socket) -> None:
        try:
            while not self._stop.is_set():
                frame = read_frame(sock)
                if frame is None:
                    return
                ftype = frame.get("type")
                if ftype == "msg":
                    message = frame.get("message", {})
                    self.received.append(message)
                    recv_at = now_ms()
                    time.sleep(0.03)  # simulate a little TTS spin-up before playback starts
                    write_frame(sock, frame_ack(message.get("id"), recv_at, now_ms()))
                elif ftype == "ping":
                    write_frame(sock, frame_pong(frame.get("t0", now_ms()), now_ms()))
        except (ConnectionError, OSError, ValueError):
            return

    def send_msg(self, text: str, lang: str, alert: bool = False) -> None:
        if self._sock is None:
            raise RuntimeError(f"{self.device_id}: not connected yet")
        write_frame(self._sock, frame_msg(self.device_id, text, lang, alert, False))

    def stop(self) -> None:
        self._stop.set()
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass
        try:
            self._server.close()
        except OSError:
            pass
        if self._service_info is not None:
            self._zc.unregister_service(self._service_info)
        self._zc.close()


def wait_until(predicate, timeout: float, interval: float = 0.2) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    return predicate()


def main() -> int:
    demo_db = Path(__file__).resolve().parent / "data" / "demo_log.db"
    demo_db.parent.mkdir(parents=True, exist_ok=True)
    if demo_db.exists():
        demo_db.unlink()

    print("[demo] starting gateway...")
    gw = Gateway(device_id="gateway-demo", name="control-room-demo", db_path=demo_db)
    gw.start()

    print("[demo] starting two fake phones...")
    phone_a = FakePhone(f"phone-flood-team-{uuid.uuid4().hex[:4]}", "Flood Response Unit")
    phone_b = FakePhone(f"phone-relief-camp-{uuid.uuid4().hex[:4]}", "Relief Camp Radio")
    phone_a.start()
    phone_b.start()

    ok = wait_until(lambda: len(gw.connected_device_ids()) >= 2, timeout=25.0)
    if not ok:
        print(f"[demo] WARNING: only {len(gw.connected_device_ids())}/2 phones connected after 25s "
              f"(mDNS on this network may be slow/blocked); continuing anyway")
    else:
        print(f"[demo] gateway connected to: {gw.connected_device_ids()}")
    time.sleep(0.5)

    print("[demo] fake phones sending messages...")
    phone_a.send_msg("bandh ho gaya hai rasta, madad chahiye", "hi", alert=False)
    time.sleep(0.2)
    phone_b.send_msg("water level rising fast near the camp", "en", alert=True)
    time.sleep(0.2)
    phone_a.send_msg("ration truck arrived at checkpoint two", "hi", alert=False)
    time.sleep(0.5)

    print("[demo] gateway broadcasting a message to all connected phones...")
    gw.broadcast("Control room acknowledges. Reinforcements dispatched, ETA thirty minutes.", "en", alert=False)
    time.sleep(0.5)

    print("[demo] gateway ingesting a sample CAP 1.2 alert (flood_warning.cap.xml, hi)...")
    cap_path = Path(__file__).resolve().parent / "samples" / "flood_warning.cap.xml"
    cap_result = gw.send_cap_file(cap_path, "hi")
    print(f"[demo] CAP sent: {cap_result}")
    time.sleep(1.0)

    snap = gw.state_snapshot()
    print("\n[demo] ---- summary ----")
    print(f"connected peers: {snap['connectedPeers']}")
    print(f"messages logged: {len(snap['recentMessages'])}")
    print(f"per-language counts: {snap['langCounts']}")
    print(f"latency: {snap['latency']}")
    print(f"phone_a received {len(phone_a.received)} msg(s), phone_b received {len(phone_b.received)} msg(s)")

    phone_a.stop()
    phone_b.stop()
    gw.stop()
    print(f"\n[demo] done. SQLite log at {demo_db}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
