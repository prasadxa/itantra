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
import json
import socket
import struct
import sys
import threading
import time
import uuid
from dataclasses import dataclass, field
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
        elif ftype == "msg":
            message = frame.get("message", {})
            print(
                f"[peer_sim] <- msg id={message.get('id')} from={message.get('from')} "
                f"lang={message.get('lang')} priority={message.get('priority')} text={message.get('text')!r}"
            )
            write_frame(self.sock, frame_ack(message.get("id"), recv_at))
            print(f"[peer_sim] -> ack id={message.get('id')}")
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
    args = parser.parse_args()

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
