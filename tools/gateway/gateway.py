#!/usr/bin/env python3
"""
gateway.py - offline control-room gateway for the iTantra walkie-talkie mesh.

Joins the phone network as just another peer: advertises + discovers `_itantra._tcp.`
over mDNS exactly like the app's WifiTransport (see tools/peer_sim.py, which this module
imports its frame codec/builders from via frames.py), and connects to *every* phone it
finds (not just one), applying the same "lexicographically smaller deviceId dials"
pairing rule per-peer so it never races a phone into opening two TCP connections.

Every Msg/Partial/Ack frame seen, in either direction, is logged to SQLite
(tools/gateway/data/log.db). Pings are answered. Latency is measured two ways:
  - sent -> received: gateway_sent_at (our clock) -> Ack.receivedAt (phone clock)
  - received -> playStarted: Ack.receivedAt -> Ack.playStartedAt (both phone clock,
    so this one is clock-skew free)

Run standalone:
    tools/gateway/.venv/bin/python tools/gateway/gateway.py
    tools/gateway/.venv/bin/python tools/gateway/gateway.py --cap samples/flood_warning.cap.xml --cap-lang hi

Or import Gateway from demo.py / tests without touching the network.
"""

from __future__ import annotations

import argparse
import queue
import socket
import sys
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional

from cap import parse_cap_file
from db import GatewayDB
from frames import (
    DiscoveredPeer,
    SERVICE_TYPE,
    advertise_self,
    encode_frame,
    frame_ack,
    frame_hello,
    frame_msg,
    frame_ping,
    frame_pong,
    local_ip_guess,
    now_ms,
    read_frame,
    service_name_to_device_id,
    write_frame,
)

try:
    from zeroconf import ServiceListener, Zeroconf, ServiceBrowser
except ImportError:  # pragma: no cover
    print("Missing 'zeroconf'. Run: tools/gateway/.venv/bin/pip install -r tools/gateway/requirements.txt", file=sys.stderr)
    raise

GATEWAY_NAME_PREFIX = "gateway"


def _extract_location(message: dict) -> tuple[Optional[float], Optional[float]]:
    """VoiceMessage has no lat/lon field yet (see core/Messages.kt) - handle its future
    arrival gracefully by looking for a few plausible shapes without requiring any of them."""
    lat = message.get("lat")
    lon = message.get("lon") or message.get("lng")
    if lat is None or lon is None:
        loc = message.get("location")
        if isinstance(loc, dict):
            lat = lat if lat is not None else loc.get("lat")
            lon = lon if lon is not None else (loc.get("lon") or loc.get("lng"))
    try:
        return (float(lat), float(lon)) if lat is not None and lon is not None else (None, None)
    except (TypeError, ValueError):
        return (None, None)


@dataclass
class PeerLink:
    sock: socket.socket
    address: str
    port: int
    device_id: Optional[str] = None
    name: Optional[str] = None
    connected_at: int = field(default_factory=now_ms)
    last_rtt_ms: Optional[int] = None
    send_lock: threading.Lock = field(default_factory=threading.Lock)
    stop: threading.Event = field(default_factory=threading.Event)

    def send(self, frame: dict) -> bool:
        try:
            with self.send_lock:
                write_frame(self.sock, frame)
            return True
        except OSError:
            self.stop.set()
            return False

    def close(self) -> None:
        self.stop.set()
        try:
            self.sock.close()
        except OSError:
            pass


class _BrowserListener(ServiceListener):
    def __init__(self, zc: Zeroconf, on_change: Callable[[str, Optional[DiscoveredPeer]], None]):
        self._zc = zc
        self._on_change = on_change

    def add_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        info = zc.get_service_info(type_, name, timeout=3000)
        if info is None or not info.addresses:
            return
        peer = DiscoveredPeer(
            name=name,
            device_id=service_name_to_device_id(name),
            address=socket.inet_ntoa(info.addresses[0]),
            port=info.port,
        )
        self._on_change(peer.device_id, peer)

    def update_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        self.add_service(zc, type_, name)

    def remove_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        self._on_change(service_name_to_device_id(name), None)


class Gateway:
    def __init__(
        self,
        device_id: Optional[str] = None,
        name: str = "control-room-gateway",
        listen_port: int = 0,
        db_path: Optional[Path] = None,
        advertise: bool = True,
    ):
        self.device_id = device_id or f"{GATEWAY_NAME_PREFIX}-{uuid.uuid4().hex[:6]}"
        self.name = name
        self.listen_port_requested = listen_port
        self.listen_port = 0
        self.db = GatewayDB(db_path or Path(__file__).resolve().parent / "data" / "log.db")
        self._advertise = advertise

        self._links: dict[str, PeerLink] = {}
        self._links_lock = threading.RLock()
        self._connecting: set[str] = set()
        self._known_peers: dict[str, DiscoveredPeer] = {}
        self._pending_sends: dict[str, dict] = {}  # msg_id -> {"sent_at": int}

        self._subscribers: list[queue.Queue] = []
        self._subscribers_lock = threading.Lock()

        self._zc: Optional[Zeroconf] = None
        self._server_sock: Optional[socket.socket] = None
        self._service_info = None
        self._stop = threading.Event()
        self._threads: list[threading.Thread] = []

    # ---- pub/sub for the dashboard SSE feed ----

    def subscribe(self) -> "queue.Queue[dict]":
        q: "queue.Queue[dict]" = queue.Queue(maxsize=200)
        with self._subscribers_lock:
            self._subscribers.append(q)
        return q

    def unsubscribe(self, q: "queue.Queue[dict]") -> None:
        with self._subscribers_lock:
            if q in self._subscribers:
                self._subscribers.remove(q)

    def _publish(self, event: dict) -> None:
        event.setdefault("ts", now_ms())
        with self._subscribers_lock:
            subs = list(self._subscribers)
        for q in subs:
            try:
                q.put_nowait(event)
            except queue.Full:
                pass

    # ---- lifecycle ----

    def start(self) -> None:
        self._server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._server_sock.bind(("0.0.0.0", self.listen_port_requested))
        self._server_sock.listen(8)
        self.listen_port = self._server_sock.getsockname()[1]
        self._server_sock.settimeout(1.0)

        if self._advertise:
            self._zc = Zeroconf()
            local_ip = local_ip_guess()
            self._service_info = advertise_self(self._zc, self.device_id, self.listen_port, local_ip)
            listener = _BrowserListener(self._zc, self._on_peer_change)
            self._browser = ServiceBrowser(self._zc, SERVICE_TYPE, listener)
            print(f"[gateway] deviceId={self.device_id} listening on {local_ip}:{self.listen_port}, advertising {SERVICE_TYPE}")

        self._spawn(self._accept_loop, name="gw-accept")
        self._spawn(self._connector_loop, name="gw-connector")
        self._spawn(self._keepalive_loop, name="gw-keepalive")

    def stop(self) -> None:
        self._stop.set()
        with self._links_lock:
            links = list(self._links.values())
        for link in links:
            link.close()
        if self._server_sock is not None:
            try:
                self._server_sock.close()
            except OSError:
                pass
        if self._zc is not None:
            if self._service_info is not None:
                self._zc.unregister_service(self._service_info)
            self._zc.close()
        for t in self._threads:
            t.join(timeout=2.0)

    def _spawn(self, target, *, name: str) -> None:
        t = threading.Thread(target=target, name=name, daemon=True)
        t.start()
        self._threads.append(t)

    # ---- connection management ----

    def _on_peer_change(self, device_id: str, peer: Optional[DiscoveredPeer]) -> None:
        if peer is None:
            self._known_peers.pop(device_id, None)
            return
        if device_id == self.device_id:
            return
        self._known_peers[device_id] = peer

    def _connector_loop(self) -> None:
        while not self._stop.is_set():
            for device_id, peer in list(self._known_peers.items()):
                with self._links_lock:
                    already = device_id in self._links or device_id in self._connecting
                if already:
                    continue
                if self.device_id < device_id:
                    with self._links_lock:
                        self._connecting.add(device_id)
                    self._spawn(lambda p=peer: self._dial(p), name=f"gw-dial-{device_id}")
            self._stop.wait(3.0)

    def _dial(self, peer: DiscoveredPeer) -> None:
        try:
            sock = socket.create_connection((peer.address, peer.port), timeout=8.0)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self._register_link(sock, peer.address, peer.port, expected_device_id=peer.device_id)
        except OSError as e:
            print(f"[gateway] dial to {peer.device_id} failed: {e}")
        finally:
            with self._links_lock:
                self._connecting.discard(peer.device_id)

    def _accept_loop(self) -> None:
        while not self._stop.is_set():
            try:
                sock, addr = self._server_sock.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self._spawn(lambda s=sock, a=addr: self._register_link(s, a[0], a[1]), name="gw-accepted")

    def _register_link(self, sock: socket.socket, address: str, port: int, expected_device_id: Optional[str] = None) -> None:
        link = PeerLink(sock=sock, address=address, port=port, device_id=expected_device_id)
        if expected_device_id is not None:
            with self._links_lock:
                if expected_device_id in self._links:
                    sock.close()
                    return
                self._links[expected_device_id] = link
            self.db.upsert_peer(device_id=expected_device_id, name="", address=address, port=port, connected=True)
        link.send(frame_hello(self.device_id, self.name))
        self._reader_loop(link)

    def _reader_loop(self, link: PeerLink) -> None:
        try:
            while not link.stop.is_set():
                frame = read_frame(link.sock)
                if frame is None:
                    break
                self._on_frame(link, frame)
        except (ConnectionError, OSError, ValueError) as e:
            print(f"[gateway] link {link.device_id or link.address} error: {e}")
        finally:
            link.stop.set()
            with self._links_lock:
                if link.device_id and self._links.get(link.device_id) is link:
                    del self._links[link.device_id]
            if link.device_id:
                self.db.upsert_peer(device_id=link.device_id, name=link.name or "", address=link.address, port=link.port, connected=False)
                self._publish({"type": "peer_disconnected", "deviceId": link.device_id})
            link.close()

    def _keepalive_loop(self) -> None:
        while not self._stop.is_set():
            self._stop.wait(20.0)
            with self._links_lock:
                links = list(self._links.values())
            for link in links:
                link.send(frame_ping(now_ms()))

    # ---- frame handling ----

    def _on_frame(self, link: PeerLink, frame: dict) -> None:
        ftype = frame.get("type")
        recv_at = now_ms()

        if ftype == "hello":
            device_id = frame.get("deviceId")
            name = frame.get("name", "")
            if device_id and link.device_id is None:
                with self._links_lock:
                    existing = self._links.get(device_id)
                    if existing is not None and existing is not link:
                        # race: both dial and accept succeeded; keep the existing one.
                        link.stop.set()
                        link.close()
                        return
                    link.device_id = device_id
                    link.name = name
                    self._links[device_id] = link
            elif link.device_id:
                link.name = name
            if link.device_id:
                self.db.upsert_peer(device_id=link.device_id, name=name, address=link.address, port=link.port, connected=True)
                self._publish({"type": "peer_connected", "deviceId": link.device_id, "name": name, "address": link.address})

        elif ftype == "msg":
            message = frame.get("message", {}) or {}
            msg_id = message.get("id", "")
            lat, lon = _extract_location(message)
            self.db.log_message(
                msg_id=msg_id,
                direction="in",
                peer_device_id=link.device_id or link.address,
                from_id=message.get("from"),
                lang=message.get("lang"),
                text=message.get("text"),
                priority=message.get("priority"),
                emotion=message.get("emotion"),
                ssml=bool(message.get("ssml", False)),
                speech_end_at=message.get("speechEndAt", 0),
                stt_done_at=message.get("sttDoneAt", 0),
                sent_at=message.get("sentAt", 0),
                lat=lat,
                lon=lon,
            )
            # Gateway "plays" (displays) the message immediately on the dashboard - ack right away.
            play_started_at = now_ms()
            link.send(frame_ack(msg_id, recv_at, play_started_at))
            self.db.log_ack(
                msg_id=msg_id, peer_device_id=link.device_id or link.address, direction="out",
                received_at=recv_at, play_started_at=play_started_at, sent_at_gateway=None,
            )
            self._publish({"type": "message", "peerDeviceId": link.device_id, "message": message, "lat": lat, "lon": lon})

        elif ftype == "partial":
            self.db.log_partial(
                msg_id=frame.get("id", ""),
                peer_device_id=link.device_id or link.address,
                from_id=frame.get("from", ""),
                lang=frame.get("lang", ""),
                text=frame.get("text", ""),
            )
            self._publish({"type": "partial", "peerDeviceId": link.device_id, "partial": frame})

        elif ftype == "ack":
            msg_id = frame.get("id", "")
            received_at = frame.get("receivedAt")
            play_started_at = frame.get("playStartedAt", 0)
            pending = self._pending_sends.pop(msg_id, None)
            sent_at_gateway = pending["sent_at"] if pending else None
            self.db.log_ack(
                msg_id=msg_id, peer_device_id=link.device_id or link.address, direction="in",
                received_at=received_at, play_started_at=play_started_at, sent_at_gateway=sent_at_gateway,
            )
            sent_to_recv = (received_at - sent_at_gateway) if (sent_at_gateway and received_at) else None
            recv_to_play = (play_started_at - received_at) if (play_started_at and received_at) else None
            self._publish({
                "type": "ack", "peerDeviceId": link.device_id, "msgId": msg_id,
                "sentToReceivedMs": sent_to_recv, "receivedToPlayMs": recv_to_play,
            })

        elif ftype == "ping":
            t0 = frame.get("t0", recv_at)
            link.send(frame_pong(t0, recv_at))

        elif ftype == "pong":
            t0 = frame.get("t0")
            if t0 is not None:
                link.last_rtt_ms = recv_at - t0
                self._publish({"type": "peer_rtt", "deviceId": link.device_id, "rttMs": link.last_rtt_ms})

        elif ftype == "ptt":
            self._publish({"type": "ptt", "peerDeviceId": link.device_id, "talking": frame.get("talking")})

    # ---- outbound: broadcast + CAP ingestion ----

    def connected_device_ids(self) -> list[str]:
        with self._links_lock:
            return [d for d, link in self._links.items() if not link.stop.is_set()]

    def broadcast(self, text: str, lang: str, alert: bool = False, ssml: bool = False) -> dict:
        """Sends one Frame.Msg (fresh id) to every connected peer. Returns a summary dict."""
        msg_id = str(uuid.uuid4())
        sent_at = now_ms()
        frame = frame_msg(self.device_id, text, lang, alert, ssml)
        frame["message"]["id"] = msg_id  # keep one shared id across all recipients
        frame["message"]["sentAt"] = sent_at
        frame["message"]["speechEndAt"] = sent_at
        frame["message"]["sttDoneAt"] = sent_at

        with self._links_lock:
            links = list(self._links.values())
        sent_to: list[str] = []
        for link in links:
            if link.send(frame):
                sent_to.append(link.device_id or link.address)
                self.db.log_message(
                    msg_id=msg_id, direction="out", peer_device_id=link.device_id or link.address,
                    from_id=self.device_id, lang=lang.upper(), text=text,
                    priority="ALERT" if alert else "NORMAL", emotion=None, ssml=ssml,
                    speech_end_at=sent_at, stt_done_at=sent_at, sent_at=sent_at,
                )
        if sent_to:
            self._pending_sends[msg_id] = {"sent_at": sent_at}
        self._publish({"type": "broadcast_sent", "msgId": msg_id, "text": text, "lang": lang, "alert": alert, "sentTo": sent_to})
        return {"msgId": msg_id, "sentTo": sent_to}

    def send_cap_file(self, path: str | Path, target_lang: str = "en") -> dict:
        alert, info = parse_cap_file(path, target_lang)
        text = info.spoken_text()
        result = self.broadcast(text, info.language.split("-")[0] or target_lang, alert=True)
        result.update({
            "event": info.event, "headline": info.headline, "language": info.language,
            "severity": info.severity, "urgency": info.urgency,
        })
        return result

    # ---- state snapshot for the dashboard's initial GET ----

    def state_snapshot(self) -> dict:
        from db import percentile

        lat = self.db.latency_samples()
        return {
            "deviceId": self.device_id,
            "listenPort": self.listen_port,
            "connectedPeers": self.connected_device_ids(),
            "peers": self.db.connected_peers(),
            "recentMessages": self.db.recent_messages(100),
            "recentPartials": self.db.recent_partials(50),
            "langCounts": self.db.lang_counts(),
            "locations": self.db.locations(200),
            "latency": {
                "sentToReceivedP50": percentile(lat["sent_to_received_ms"], 50),
                "sentToReceivedP95": percentile(lat["sent_to_received_ms"], 95),
                "receivedToPlayP50": percentile(lat["received_to_play_ms"], 50),
                "receivedToPlayP95": percentile(lat["received_to_play_ms"], 95),
                "n": len(lat["sent_to_received_ms"]),
            },
        }


def main() -> int:
    parser = argparse.ArgumentParser(description="iTantra control-room gateway")
    parser.add_argument("--device-id", default=None)
    parser.add_argument("--name", default="control-room-gateway")
    parser.add_argument("--port", type=int, default=0, help="TCP peer-protocol listen port (0 = any free port)")
    parser.add_argument("--http-port", type=int, default=8787, help="dashboard HTTP port")
    parser.add_argument("--db", default=None, help="path to the SQLite log (default tools/gateway/data/log.db)")
    parser.add_argument("--cap", default=None, help="CAP 1.2 XML file to ingest and broadcast once at startup")
    parser.add_argument("--cap-lang", default="en", help="target language code for --cap (falls back to English)")
    parser.add_argument("--no-http", action="store_true", help="run the TCP peer side only, no dashboard")
    args = parser.parse_args()

    gw = Gateway(
        device_id=args.device_id, name=args.name, listen_port=args.port,
        db_path=Path(args.db) if args.db else None,
    )
    gw.start()

    if args.cap:
        # give discovery a moment so the CAP alert reaches phones that are already up.
        time.sleep(2.0)
        result = gw.send_cap_file(args.cap, args.cap_lang)
        print(f"[gateway] CAP ingested: {result}")

    http_server = None
    if not args.no_http:
        from dashboard import make_server

        http_server = make_server(gw, args.http_port)
        print(f"[gateway] dashboard: http://localhost:{args.http_port}/")
        _spawn_http = threading.Thread(target=http_server.serve_forever, daemon=True)
        _spawn_http.start()

    try:
        while True:
            time.sleep(1.0)
    except KeyboardInterrupt:
        pass
    finally:
        if http_server is not None:
            http_server.shutdown()
        gw.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
