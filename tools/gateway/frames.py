"""
frames.py - thin import shim onto tools/peer_sim.py's wire-protocol helpers.

peer_sim.py already implements the app's exact framing (4-byte BE length + UTF-8 JSON,
class-discriminator "type") and mDNS discovery of `_itantra._tcp.`. The gateway is owned
separately from peer_sim.py (do not edit peer_sim.py here) but reuses its frame builders
and codec so the two stay byte-for-byte compatible with core/Messages.kt.
"""

from __future__ import annotations

import sys
from pathlib import Path

_TOOLS_DIR = Path(__file__).resolve().parents[1]
if str(_TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(_TOOLS_DIR))

from peer_sim import (  # noqa: E402
    MAX_FRAME_BYTES,
    SERVICE_TYPE,
    DiscoveredPeer,
    advertise_self,
    discover_peer,
    encode_frame,
    frame_ack,
    frame_hello,
    frame_msg,
    frame_ping,
    frame_pong,
    local_ip_guess,
    now_ms,
    read_exact,
    read_frame,
    write_frame,
    _service_name_to_device_id,
)

__all__ = [
    "MAX_FRAME_BYTES",
    "SERVICE_TYPE",
    "DiscoveredPeer",
    "advertise_self",
    "discover_peer",
    "encode_frame",
    "frame_ack",
    "frame_hello",
    "frame_msg",
    "frame_ping",
    "frame_pong",
    "local_ip_guess",
    "now_ms",
    "read_exact",
    "read_frame",
    "write_frame",
    "service_name_to_device_id",
    "frame_partial",
]

service_name_to_device_id = _service_name_to_device_id


def frame_partial(msg_id: str, from_id: str, lang: str, text: str) -> dict:
    """Frame.Partial - not built by peer_sim.py (it only builds Msg/Ack/Hello/Ping/Pong),
    so the gateway builds it directly, matching core/Messages.kt's field-for-field shape."""
    return {"type": "partial", "id": msg_id, "from": from_id, "lang": lang.upper(), "text": text}
